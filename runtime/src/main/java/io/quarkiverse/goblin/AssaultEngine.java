package io.quarkiverse.goblin;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class AssaultEngine {

    private static final Logger LOG = Logger.getLogger(AssaultEngine.class);
    static final int MAX_HISTORY = 1000;

    /**
     * Layers whose assault hook is always part of the extension.
     */
    private static final Set<ChaosLayer> BUILT_IN_HOOKS = Collections
            .unmodifiableSet(EnumSet.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_OUT, ChaosLayer.HTTP_IN));

    /**
     * Layers an inbound HTTP request can resolve to. {@link ChaosLayer#MESSAGING} is a message-consumer entry point and
     * {@link ChaosLayer#HTTP_OUT} gates per outgoing call, so neither is part of the per-request draw.
     */
    public static final Set<ChaosLayer> HTTP_REQUEST_LAYERS = Collections
            .unmodifiableSet(EnumSet.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE, ChaosLayer.HTTP_IN));

    /**
     * Layers a consumed message can resolve to: the message consumer itself and the layers below it.
     */
    public static final Set<ChaosLayer> MESSAGE_LAYERS = Collections
            .unmodifiableSet(EnumSet.of(ChaosLayer.DATABASE, ChaosLayer.MESSAGING, ChaosLayer.SERVICE));

    /**
     * Optional hooks installed at build time because the application has the matching extension (Agroal for
     * {@link ChaosLayer#DATABASE}, Quarkus Messaging for {@link ChaosLayer#MESSAGING}), resolved on startup.
     */
    private volatile Set<ChaosLayer> optionalHooks = Collections.emptySet();

    private volatile MutableAssaultConfig mutableConfig;
    private volatile boolean active;
    private final ConcurrentLinkedDeque<AssaultRecord> history = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalAssaultCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> assaultCounts = new ConcurrentHashMap<>();
    private volatile long countersSinceEpoch = System.currentTimeMillis();

    @Inject
    Instance<AssaultObserver> observers;

    @Inject
    GoblinConfig config;

    @Inject
    Instance<GoblinLayerHooks> layerHooks;

    /**
     * Declares the optional layer hooks installed for this application. Package-private for unit tests; the production
     * lifecycle resolves them from the {@link GoblinLayerHooks} synthetic bean on startup.
     *
     * @param layers the layers whose hook is installed, never {@code null}
     */
    void setOptionalHooksForTests(Set<ChaosLayer> layers) {
        optionalHooks = new GoblinLayerHooks(layers).layers();
    }

    /**
     * @param layer the layer to test
     * @return whether an assault hook backs the given layer in this application
     */
    public boolean isLayerAvailable(ChaosLayer layer) {
        return BUILT_IN_HOOKS.contains(layer) || optionalHooks.contains(layer);
    }

    /**
     * @return the layers backed by an assault hook in this application, in ascending declaration order
     */
    public Set<ChaosLayer> getAvailableLayers() {
        EnumSet<ChaosLayer> available = EnumSet.copyOf(BUILT_IN_HOOKS);
        available.addAll(optionalHooks);
        return Collections.unmodifiableSet(available);
    }

    /**
     * Initialises the engine on startup, delegating to {@link #initialize(LaunchMode)} with the current launch mode.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        initialize(LaunchMode.current());
    }

    /**
     * Initialises the engine for the given launch mode.
     * <p>
     * Chaos only ever activates in dev or test mode: in any other launch mode -- notably a packaged production
     * application -- the engine stays inactive and neither the persisted state file nor the configuration is consulted.
     * In dev mode a previously persisted state file (assault toggles and parameters only -- the enabled/active flag is
     * never persisted and always comes from {@code quarkus.goblin.enabled}) is restored when present, otherwise the
     * mutable config is built from the {@link GoblinConfig} configuration. In test mode the state file is deliberately
     * ignored so tests always start from {@code application.properties} and can never be contaminated by local Dev UI
     * state, and chaos is only active when {@code quarkus.goblin.test.enabled} opts in: an application's test suite is
     * never assaulted just because the extension is on the classpath. Package-private for unit tests.
     *
     * @param mode the launch mode the application started under
     */
    void initialize(LaunchMode mode) {
        if (mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST) {
            this.active = false;
            return;
        }
        if (layerHooks != null && layerHooks.isResolvable()) {
            this.optionalHooks = layerHooks.get().layers();
        }
        MutableAssaultConfig persisted = mode == LaunchMode.DEVELOPMENT ? GoblinStatePersistence.load() : null;
        if (persisted != null) {
            this.mutableConfig = persisted;
            LOG.info("Loaded previous Goblin state from .goblin-state.json");
        } else if (config != null) {
            this.mutableConfig = MutableAssaultConfig.fromConfig(config);
        } else {
            // no configuration injected (plain unit test): built-in defaults
            this.mutableConfig = new MutableAssaultConfig();
        }
        boolean enabled = config == null || config.enabled();
        boolean testOptIn = mode != LaunchMode.TEST || config == null || config.test().enabled();
        this.active = enabled && testOptIn;
        if (enabled && !testOptIn) {
            LOG.info("Goblin chaos is inactive in test mode: set quarkus.goblin.test.enabled=true to assault the tests, "
                    + "or switch it on from a test with AssaultEngine.setActive(true)");
        }
        this.mutableConfig.validateAndFix();
        if (mode == LaunchMode.DEVELOPMENT) {
            this.mutableConfig.setOnChange(this::persistConfig);
        }
        if (active) {
            MutableAssaultConfig mutableConfig = this.mutableConfig.snapshot();
            LOG.warnf(
                    "Chaos engineering active: %d%% of REST requests subject to assault (profile=%s, latency=%s, exception=%s, httpStatus=%s, dependencyDegradation=%s, clientLatency=%s, clientException=%s, responseBody=%s)",
                    mutableConfig.getTargetLevel(),
                    mutableConfig.getProfile(),
                    mutableConfig.isLatencyEnabled(),
                    mutableConfig.isExceptionEnabled(),
                    mutableConfig.isHttpStatusEnabled(),
                    mutableConfig.isDependencyDegradationEnabled(),
                    mutableConfig.isClientLatencyEnabled(),
                    mutableConfig.isClientExceptionEnabled(),
                    mutableConfig.isResponseBodyEnabled());
        }
    }

    private void persistConfig() {
        GoblinStatePersistence.save(mutableConfig);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
        notifyObservers(observer -> observer.onActiveChange(active));
    }

    public boolean shouldAssault() {
        MutableAssaultConfig cfg = configSnapshot();
        if (!active || cfg == null || !cfg.hasAnyAssaultEnabled()) {
            return false;
        }
        return levelGate(cfg);
    }

    /**
     * Decides which single layer is armed for the current inbound HTTP request, see
     * {@link #resolveAssaultLayer(Set)} with {@link #HTTP_REQUEST_LAYERS}.
     *
     * @return the armed layer for this request, or {@code null} when no armed layer passed its draw
     */
    public ChaosLayer resolveAssaultLayer() {
        return resolveAssaultLayer(HTTP_REQUEST_LAYERS);
    }

    /**
     * Decides which single layer is armed for the current pseudo-request (an inbound HTTP request or a consumed
     * message), resolving ascending (from the bottom of the stack toward the entry point): every armed, actionable layer
     * independently rolls the target-level gate, and the <em>deepest</em> layer whose
     * draw passes becomes the armed layer for this request, shadowing any shallower layer whose own draw would also have
     * passed. The draw is redone for every request, never cached across requests, so the fault distribution stays live and
     * matches the per-request {@code shouldAssault()} behaviour of the legacy single-layer engine.
     * <p>
     * Only the given candidate layers take part: {@link ChaosLayer#HTTP_OUT} never does (outbound calls gate per call,
     * see {@link #shouldAssaultClient()}). Layers whose optional hook is not installed in this application (e.g.
     * {@link ChaosLayer#DATABASE} without a datasource) are never selected, so arming them degrades to "the deepest
     * available layer wins" instead of silently swallowing the fault.
     *
     * @param candidates the layers this entry point can resolve to
     * @return the armed layer, or {@code null} when no armed layer passed its draw
     */
    public ChaosLayer resolveAssaultLayer(Set<ChaosLayer> candidates) {
        MutableAssaultConfig cfg = configSnapshot();
        if (!active || cfg == null || !cfg.hasAnyAssaultEnabled()) {
            return null;
        }
        for (ChaosLayer layer : ChaosLayer.values()) {
            if (!candidates.contains(layer) || !isLayerAvailable(layer) || !isLayerActionable(layer, cfg)) {
                continue;
            }
            if (levelGate(cfg)) {
                return layer;
            }
        }
        return null;
    }

    /**
     * Returns whether the given layer is armed and has at least one of the assaults its hook can inject enabled.
     *
     * @param layer the layer to test
     * @param config the active configuration
     * @return {@code true} when a fault could actually fire at that layer
     */
    private static boolean isLayerActionable(ChaosLayer layer, MutableAssaultConfig config) {
        if (!config.isLayerEnabled(layer)) {
            return false;
        }
        return switch (layer) {
            // the database, messaging and service hooks only inject latency and exceptions
            case DATABASE, MESSAGING, SERVICE -> config.isLatencyEnabled() || config.isExceptionEnabled();
            case HTTP_IN -> config.hasAnyAssaultEnabled();
            case HTTP_OUT -> config.hasAnyClientAssaultEnabled();
        };
    }

    /**
     * Decides whether an outbound REST Client call should be assaulted, mirroring {@link #shouldAssault()} for the
     * client side.
     * <p>
     * Client-side assaults only fire when the {@link ChaosLayer#HTTP_OUT} layer is armed, at least one client assault toggle
     * is enabled and the target level gate passes; the server-side toggles are deliberately ignored so a call is never
     * delayed or failed unless the client-side assaults were explicitly enabled. The decision is per call (each outgoing
     * call rolls its own gate) rather than per inbound request.
     *
     * @return {@code true} when the outbound call is eligible for a client-side assault
     */
    public boolean shouldAssaultClient() {
        MutableAssaultConfig cfg = configSnapshot();
        if (!active || cfg == null || !cfg.isLayerEnabled(ChaosLayer.HTTP_OUT) || !cfg.hasAnyClientAssaultEnabled()) {
            return false;
        }
        return levelGate(cfg);
    }

    /**
     * Draws the target-level gate once more, independently of any per-request decision. Used by the service
     * interceptor to re-draw each further attempt (e.g. {@code @Retry}) within an already armed request.
     *
     * @return {@code true} when the draw passes the configured target level
     */
    public boolean drawLevelGate() {
        MutableAssaultConfig cfg = mutableConfig;
        return cfg != null && levelGate(cfg);
    }

    private static boolean levelGate(MutableAssaultConfig cfg) {
        int level = cfg.getTargetLevel();
        if (level <= 0) {
            return false;
        }
        if (level >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < level;
    }

    /**
     * Returns a read-only snapshot of the current configuration, for hooks that read several values for one request or
     * call: all reads of the snapshot come from the same state, whatever the Dev UI changes meanwhile.
     *
     * @return the frozen configuration, or {@code null} while the engine is not initialised
     */
    public MutableAssaultConfig configSnapshot() {
        MutableAssaultConfig current = mutableConfig;
        return current != null ? current.snapshot() : null;
    }

    public MutableAssaultConfig getMutableConfig() {
        return mutableConfig;
    }

    /**
     * Installs the mutable configuration used by {@link #shouldAssault()}/{@link #shouldAssaultClient()} and history
     * snapshots. Package-private for unit tests; the production lifecycle assigns the configuration at startup via the
     * recorder and the state loader.
     *
     * @param config the configuration to install
     */
    void setMutableConfigForTests(MutableAssaultConfig config) {
        this.mutableConfig = config;
    }

    /**
     * Installs the observers used by {@link #recordAssault(AssaultSource, String, String, long)} and
     * {@link #setActive(boolean)}.
     * Package-private for unit tests; the production lifecycle relies on CDI injection of the {@code observers} field.
     *
     * @param observers the observers to notify
     */
    void setObserversForTests(Instance<AssaultObserver> observers) {
        this.observers = observers;
    }

    public List<AssaultRecord> getHistory() {
        return List.copyOf(history);
    }

    public void clearHistory() {
        history.clear();
    }

    /**
     * Records an assault injected at the inbound REST boundary ({@link AssaultSource#SERVER}).
     *
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "exception"}
     */
    public void recordAssault(String method, String type) {
        recordAssault(AssaultSource.SERVER, method, type, 0);
    }

    /**
     * Records an assault injected at the inbound REST boundary ({@link AssaultSource#SERVER}).
     *
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "latency"}
     * @param latencyMs the injected latency, {@code 0} when not applicable
     */
    public void recordAssault(String method, String type, long latencyMs) {
        recordAssault(AssaultSource.SERVER, method, type, latencyMs);
    }

    /**
     * Records an assault injected at the given source.
     *
     * @param source where the assault was injected
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "exception"}
     */
    public void recordAssault(AssaultSource source, String method, String type) {
        recordAssault(source, method, type, 0);
    }

    /**
     * Records an assault injected at the given source, appends it to the bounded history, updates the counters and
     * notifies the observers.
     *
     * @param source where the assault was injected
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "latency"}
     * @param latencyMs the injected latency, {@code 0} when not applicable
     */
    public void recordAssault(AssaultSource source, String method, String type, long latencyMs) {
        String configSnapshot = mutableConfig != null ? mutableConfig.describeAssaults() : "no assault enabled";
        AssaultRecord record = new AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, configSnapshot,
                source);
        LOG.debugf("Goblin: history += %s type=%s latencyMs=%d (%s)", method, type, latencyMs, configSnapshot);
        history.addLast(record);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
        totalAssaultCount.incrementAndGet();
        assaultCounts.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
        notifyObservers(observer -> observer.onAssault(record));
    }

    /**
     * Notifies every registered {@link AssaultObserver} of an assault or engine state change. Observers run on the
     * request path, so a failing observer is logged and skipped rather than propagated: observability must never break
     * an assault. Outside the CDI container (plain unit test, no injected observers) the notification is a no-op.
     *
     * @param action the notification to broadcast to each observer
     */
    private void notifyObservers(Consumer<AssaultObserver> action) {
        Instance<AssaultObserver> current = observers;
        if (current == null) {
            return;
        }
        for (AssaultObserver observer : current) {
            try {
                action.accept(observer);
            } catch (RuntimeException e) {
                LOG.debugf("Goblin: assault observer ignored the notification: %s", e.getMessage());
            }
        }
    }

    /**
     * Returns the total number of assaults recorded since the engine started or counters were last reset.
     *
     * @return the cumulative assault count
     */
    public long getTotalAssaultCount() {
        return totalAssaultCount.get();
    }

    /**
     * Returns the per-type assault counts, keyed by assault type (e.g. {@code "latency"}, {@code "response-body-truncate"}).
     *
     * @return an unmodifiable snapshot of the per-type counts
     */
    public Map<String, Long> getAssaultCounts() {
        var result = new java.util.HashMap<String, Long>();
        assaultCounts.forEach((k, v) -> result.put(k, v.get()));
        return java.util.Map.copyOf(result);
    }

    /**
     * Returns the epoch timestamp (ms) when counters were last reset, or when the engine started.
     *
     * @return the counters start time in epoch milliseconds
     */
    public long getCountersSinceEpoch() {
        return countersSinceEpoch;
    }

    /**
     * Resets all assault counters to zero and restarts the counters clock.
     */
    public void resetCounters() {
        totalAssaultCount.set(0);
        assaultCounts.clear();
        countersSinceEpoch = System.currentTimeMillis();
    }

    /**
     * One recorded assault.
     *
     * @param method the history identifier of the assaulted target
     * @param type the assault label, e.g. {@code "latency"}
     * @param timestamp the epoch time (ms) the assault was recorded
     * @param latencyMs the injected latency, {@code 0} when not applicable
     * @param configSnapshot a description of the enabled assaults at that time
     * @param source where the assault was injected
     */
    public record AssaultRecord(String method, String type, long timestamp, long latencyMs, String configSnapshot,
            AssaultSource source) {

        /**
         * Builds a record injected at the inbound REST boundary ({@link AssaultSource#SERVER}).
         *
         * @param method the history identifier of the assaulted target
         * @param type the assault label
         * @param timestamp the epoch time (ms) the assault was recorded
         * @param latencyMs the injected latency, {@code 0} when not applicable
         * @param configSnapshot a description of the enabled assaults at that time
         */
        public AssaultRecord(String method, String type, long timestamp, long latencyMs, String configSnapshot) {
            this(method, type, timestamp, latencyMs, configSnapshot, AssaultSource.SERVER);
        }

        /**
         * @return the metric tag / span attribute value of the source, {@code "server"} when no source was recorded
         */
        public String sourceTag() {
            return (source != null ? source : AssaultSource.SERVER).tag();
        }
    }
}
