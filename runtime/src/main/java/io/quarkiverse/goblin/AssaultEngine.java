package io.quarkiverse.goblin;

import java.util.List;
import java.util.Map;
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
    private static volatile GoblinConfig staticConfig;

    private volatile MutableAssaultConfig mutableConfig;
    private volatile boolean active;
    private final ConcurrentLinkedDeque<AssaultRecord> history = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalAssaultCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> assaultCounts = new ConcurrentHashMap<>();
    private volatile long countersSinceEpoch = System.currentTimeMillis();

    @Inject
    Instance<AssaultObserver> observers;

    public static void setStaticConfig(GoblinConfig config) {
        staticConfig = config;
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
     * application -- the engine stays inactive and neither the persisted state file nor the static configuration is
     * consulted. In dev mode a previously persisted state file (assault toggles and parameters only -- the
     * enabled/active flag is never persisted and always comes from {@code quarkus.goblin.enabled}) is restored when
     * present, otherwise the mutable config is built from the static configuration. In test mode the state file is
     * deliberately ignored so integration tests always start from {@code application.properties} and can never be
     * contaminated by local Dev UI state. Package-private for unit tests.
     *
     * @param mode the launch mode the application started under
     */
    void initialize(LaunchMode mode) {
        if (mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST) {
            this.active = false;
            return;
        }
        MutableAssaultConfig persisted = mode == LaunchMode.DEVELOPMENT ? GoblinStatePersistence.load() : null;
        if (persisted != null) {
            this.mutableConfig = persisted;
            LOG.info("Loaded previous Goblin state from .goblin-state.json");
        } else if (staticConfig != null) {
            this.mutableConfig = MutableAssaultConfig.fromConfig(staticConfig);
        } else {
            // no recorded configuration (engine started outside the extension's build steps): built-in defaults
            this.mutableConfig = new MutableAssaultConfig();
        }
        this.active = staticConfig == null || staticConfig.enabled();
        this.mutableConfig.validateAndFix();
        if (mode == LaunchMode.DEVELOPMENT) {
            this.mutableConfig.setOnChange(this::persistConfig);
        }
        if (active) {
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
        if (!active || mutableConfig == null || !mutableConfig.hasAnyAssaultEnabled()) {
            return false;
        }
        return levelGate();
    }

    /**
     * Decides which single layer is armed for the current request, resolving ascending (from the bottom of the stack toward
     * REST): every armed, actionable layer independently rolls the target-level gate, and the <em>deepest</em> layer whose
     * draw passes becomes the armed layer for this request, shadowing any shallower layer whose own draw would also have
     * passed. The draw is redone for every request, never cached across requests, so the fault distribution stays live and
     * matches the per-request {@code shouldAssault()} behaviour of the legacy single-layer engine.
     * <p>
     * {@link ChaosLayer#HTTP_OUT} is deliberately excluded: outbound calls gate per call (see
     * {@link #shouldAssaultClient()}) rather than per inbound request. Layers without a backing assault hook yet
     * ({@link ChaosLayer#DATABASE}, {@link ChaosLayer#MESSAGING}) are never selected so arming them today degrades to
     * "the deepest implemented layer wins" instead of silently swallowing the fault.
     *
     * @return the armed layer for this request, or {@code null} when no armed layer passed its draw
     */
    public ChaosLayer resolveAssaultLayer() {
        if (!active || mutableConfig == null || !mutableConfig.hasAnyAssaultEnabled()) {
            return null;
        }
        for (ChaosLayer layer : ChaosLayer.values()) {
            if (layer == ChaosLayer.HTTP_OUT || !isLayerActionable(layer, mutableConfig)) {
                continue;
            }
            if (levelGate()) {
                return layer;
            }
        }
        return null;
    }

    /**
     * Returns whether the given layer both has at least one backing assault armed and is implemented as an assault hook.
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
            // no assault hook yet (issue #54 phases 2-3): never armed, so the draw falls through to SERVICE/HTTP_IN
            case DATABASE, MESSAGING -> false;
            // the service interceptor only injects latency and exceptions
            case SERVICE -> config.isLatencyEnabled() || config.isExceptionEnabled();
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
        if (!active || mutableConfig == null || !mutableConfig.isLayerEnabled(ChaosLayer.HTTP_OUT)
                || !mutableConfig.hasAnyClientAssaultEnabled()) {
            return false;
        }
        return levelGate();
    }

    /**
     * Draws the target-level gate once more, independently of any per-request decision. Used by the service
     * interceptor to re-draw each further attempt (e.g. {@code @Retry}) within an already armed request.
     *
     * @return {@code true} when the draw passes the configured target level
     */
    public boolean drawLevelGate() {
        return mutableConfig != null && levelGate();
    }

    private boolean levelGate() {
        int level = mutableConfig.getTargetLevel();
        if (level <= 0) {
            return false;
        }
        if (level >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < level;
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
     * Installs the observers used by {@link #recordAssault(String, String, long)} and {@link #setActive(boolean)}.
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

    public void recordAssault(String method, String type) {
        recordAssault(method, type, 0);
    }

    public void recordAssault(String method, String type, long latencyMs) {
        String configSnapshot = mutableConfig != null ? mutableConfig.describeAssaults() : "no assault enabled";
        AssaultRecord record = new AssaultRecord(method, type, System.currentTimeMillis(), latencyMs, configSnapshot);
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

    public record AssaultRecord(String method, String type, long timestamp, long latencyMs, String configSnapshot) {
    }
}
