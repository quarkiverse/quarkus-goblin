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

    /**
     * JVM-wide system property holding the dev-mode auto-off state: a deadline in epoch milliseconds while an auto-off is
     * pending, {@link #AUTO_OFF_FIRED} once it switched chaos off. A system property rather than a field so the state
     * survives dev-mode live reloads, which recreate the engine but keep the JVM. Only the dev-mode engine uses it: a
     * test-mode engine running in the same JVM (continuous testing) keeps its own state in {@link #localAutoOff}.
     */
    static final String AUTO_OFF_DEADLINE_PROPERTY = "goblin.auto-off.deadline";

    /** Auto-off state: nothing pending. */
    static final long AUTO_OFF_NONE = 0;

    /** Auto-off state: the deadline elapsed and chaos was switched off; kept so a live reload does not revive chaos. */
    static final long AUTO_OFF_FIRED = -1;

    /** Longest accepted auto-off delay: 24 hours. */
    public static final long MAX_AUTO_OFF_MILLIS = 24L * 60 * 60 * 1000;

    /**
     * JVM-wide system property holding the dev-mode manual deactivation: {@code true} once chaos was switched off by an
     * explicit action (the Dev UI master toggle, the kill switch, a JSON-RPC or Dev MCP {@code setActive(false)} call).
     * A system property for the same reason as {@link #AUTO_OFF_DEADLINE_PROPERTY} -- a decision taken in the Dev UI must
     * survive the live reload that recreates this engine -- and deliberately kept out of {@code .goblin-state.json}, so
     * a new process still takes the active flag from {@code quarkus.goblin.enabled} (see
     * {@link GoblinStatePersistence}).
     * <p>
     * Only a deactivation is ever recorded: the property can keep chaos off across a reload, never arm it, and an
     * explicit activation clears it so the activation survives the next reload too. A test-mode engine never writes it:
     * continuous testing boots a test application in the same JVM, which must neither inherit nor overwrite the dev
     * session's decision.
     */
    static final String MANUAL_OFF_PROPERTY = "goblin.manual-off";

    private volatile boolean devMode;
    private final AtomicLong localAutoOff = new AtomicLong(AUTO_OFF_NONE);

    private volatile MutableAssaultConfig mutableConfig;
    /** The configuration of the last change delivered to the observers, the "previous" side of the next one. */
    private MutableAssaultConfig lastNotifiedConfig;
    private final Object configChangeLock = new Object();
    private volatile boolean active;
    private volatile DeactivationReason inactiveReason;
    private final ConcurrentLinkedDeque<AssaultRecord> history = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalAssaultCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> assaultCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<AssaultSource, AtomicLong> sourceCounts = new ConcurrentHashMap<>();
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
     * mutable config is built from the {@link GoblinConfig} configuration. A deactivation taken during the current dev
     * session -- the Dev UI master toggle, the kill switch or the auto-off -- is also restored, so the live reload that
     * follows a file save does not re-arm chaos (see {@link #MANUAL_OFF_PROPERTY}). In test mode the state file is
     * deliberately ignored so tests always start from {@code application.properties} and can never be contaminated by
     * local Dev UI state, and chaos is only active when {@code quarkus.goblin.test.enabled} opts in: an application's
     * test suite is never assaulted just because the extension is on the classpath. Package-private for unit tests.
     *
     * @param mode the launch mode the application started under
     */
    void initialize(LaunchMode mode) {
        this.devMode = mode == LaunchMode.DEVELOPMENT;
        if (mode != LaunchMode.DEVELOPMENT && mode != LaunchMode.TEST) {
            this.active = false;
            this.inactiveReason = DeactivationReason.LAUNCH_MODE;
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
        if (!enabled) {
            this.inactiveReason = DeactivationReason.DISABLED;
        } else if (!testOptIn) {
            this.inactiveReason = DeactivationReason.TEST_MODE;
        }
        if (enabled && !testOptIn) {
            LOG.info("Goblin chaos is inactive in test mode: set quarkus.goblin.test.enabled=true to assault the tests, "
                    + "or switch it on from a test with AssaultEngine.setActive(true)");
        }
        this.mutableConfig.validateAndFix();
        // the startup configuration is the "previous" side of the first change; persistence stays dev-only, the observer
        // notification fires in test mode too, where a test records the attack it injected
        synchronized (configChangeLock) {
            this.lastNotifiedConfig = this.mutableConfig.snapshot();
        }
        this.mutableConfig.setOnChange(this::configChanged);
        if (mode == LaunchMode.DEVELOPMENT) {
            long autoOff = readAutoOff();
            if (active && (autoOff == AUTO_OFF_FIRED || autoOffElapsed(autoOff))) {
                writeAutoOff(AUTO_OFF_FIRED);
                this.active = false;
                this.inactiveReason = DeactivationReason.AUTO_OFF;
                LOG.info("Goblin chaos stays off after the restart: the Dev UI auto-off switched it off");
            } else if (active && readManualOff()) {
                this.active = false;
                this.inactiveReason = DeactivationReason.MANUAL;
                LOG.info("Goblin chaos stays off after the restart: it was deactivated from the Dev UI");
            }
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

    /**
     * Listener of the mutable configuration: persists it in dev mode, then reports the change to the observers with the
     * configuration before and after it. The setters publish under the configuration lock but notify outside it, so two
     * changes racing can be folded into a single notification: the observers then see the oldest previous and the
     * latest current, and an intermediate state is not reported on its own. A notification that finds the state
     * already reported is skipped, so no transition is ever reported twice.
     */
    private void configChanged() {
        if (devMode) {
            persistConfig();
        }
        AssaultConfigChange change;
        synchronized (configChangeLock) {
            MutableAssaultConfig current = mutableConfig.snapshot();
            MutableAssaultConfig previous = lastNotifiedConfig;
            if (current.hasSameStateAs(previous)) {
                return;
            }
            lastNotifiedConfig = current;
            change = new AssaultConfigChange(previous != null ? previous : current, current, System.currentTimeMillis());
        }
        notifyConfigObservers(change);
    }

    /**
     * Delivers a configuration change to every observer. Unlike the request-path notifications, a failure is logged at
     * {@code WARN}: a configuration change is a rare, deliberate action, and an observer that cannot record it is losing
     * the one event it exists for.
     *
     * @param change the change to deliver
     */
    private void notifyConfigObservers(AssaultConfigChange change) {
        Instance<AssaultObserver> current = observers;
        if (current == null) {
            return;
        }
        for (AssaultObserver observer : current) {
            try {
                observer.onConfigChange(change);
            } catch (RuntimeException e) {
                LOG.warnf(e, "Goblin: assault observer %s failed on a configuration change", observer.getClass().getName());
            }
        }
    }

    /**
     * Returns whether chaos is active. An elapsed auto-off deadline deactivates the engine on the spot, so the auto-off
     * applies whether or not the Dev UI is open.
     *
     * @return {@code true} when chaos is active
     */
    public boolean isActive() {
        if (active && autoOffElapsed(readAutoOff())) {
            expireAutoOff();
        }
        return active;
    }

    /**
     * Returns why chaos is currently off, so the Dev UI and an AI agent can tell a deactivation apart from a
     * misconfiguration instead of guessing.
     *
     * @return the deactivation reason, or {@code null} while chaos is active
     */
    public DeactivationReason inactiveReason() {
        return active ? null : inactiveReason;
    }

    /**
     * Activates or deactivates chaos. Deactivating cancels any pending auto-off; activating forgets an auto-off that
     * already fired but keeps a pending one.
     * <p>
     * In dev mode the decision survives a live reload, in both directions: see {@link #MANUAL_OFF_PROPERTY}.
     *
     * @param active the new active flag
     */
    public void setActive(boolean active) {
        synchronized (this) {
            this.active = active;
            this.inactiveReason = active ? null : DeactivationReason.MANUAL;
            // a deactivation must survive the live reload that follows the next file save, and so must an activation
            writeManualOff(!active);
            if (!active || readAutoOff() == AUTO_OFF_FIRED) {
                writeAutoOff(AUTO_OFF_NONE);
            }
        }
        notifyObservers(observer -> observer.onActiveChange(active));
    }

    /**
     * Flips the active flag. When the auto-off deadline has just elapsed, the caller saw chaos active and asked to turn
     * it off: the elapsed auto-off is applied and chaos stays off, instead of being switched back on.
     *
     * @return the new active flag
     */
    public boolean toggleActive() {
        boolean target;
        synchronized (this) {
            if (active && autoOffElapsed(readAutoOff())) {
                target = false;
            } else {
                target = !active;
            }
        }
        setActive(target);
        return target;
    }

    /**
     * Switches chaos off once the auto-off deadline elapsed. The decision is taken under the lock and the observers are
     * notified after it is released, so an observer never runs while the engine lock is held. The auto-off has its own
     * persisted state (see {@link #AUTO_OFF_DEADLINE_PROPERTY}), so it deliberately does not record a manual
     * deactivation.
     */
    private void expireAutoOff() {
        boolean fired;
        synchronized (this) {
            fired = active && autoOffElapsed(readAutoOff());
            if (fired) {
                active = false;
                inactiveReason = DeactivationReason.AUTO_OFF;
                writeAutoOff(AUTO_OFF_FIRED);
            }
        }
        if (fired) {
            LOG.warn("Goblin chaos auto-disabled: the auto-off deadline elapsed");
            notifyObservers(observer -> observer.onActiveChange(false));
        }
    }

    /**
     * Schedules chaos to switch itself off after the given delay, replacing any pending auto-off. The engine enforces it
     * on its own (see {@link #isActive()}), independently of the Dev UI; in dev mode the deadline survives live reloads.
     *
     * @param delayMillis the delay before chaos is deactivated, between 1 ms and {@link #MAX_AUTO_OFF_MILLIS}
     * @return the deadline, in epoch milliseconds
     */
    public long scheduleAutoOff(long delayMillis) {
        if (delayMillis <= 0 || delayMillis > MAX_AUTO_OFF_MILLIS) {
            throw new IllegalArgumentException(
                    "Auto-off delay must be between 1 and " + MAX_AUTO_OFF_MILLIS + " ms, got " + delayMillis);
        }
        long deadline = System.currentTimeMillis() + delayMillis;
        writeAutoOff(deadline);
        return deadline;
    }

    /**
     * Cancels any pending auto-off; chaos stays in its current state.
     */
    public synchronized void cancelAutoOff() {
        if (readAutoOff() > 0) {
            writeAutoOff(AUTO_OFF_NONE);
        }
    }

    /**
     * Returns the pending auto-off deadline.
     *
     * @return the deadline in epoch milliseconds, or {@code 0} when no auto-off is pending
     */
    public long autoOffDeadline() {
        return Math.max(AUTO_OFF_NONE, readAutoOff());
    }

    private static boolean autoOffElapsed(long state) {
        return state > 0 && System.currentTimeMillis() >= state;
    }

    /**
     * Reads the auto-off state: from the JVM-wide system property in dev mode, from this engine otherwise.
     *
     * @return a deadline, {@link #AUTO_OFF_NONE} or {@link #AUTO_OFF_FIRED}
     */
    long readAutoOff() {
        if (!devMode) {
            return localAutoOff.get();
        }
        String raw = System.getProperty(AUTO_OFF_DEADLINE_PROPERTY);
        if (raw == null) {
            return AUTO_OFF_NONE;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            System.clearProperty(AUTO_OFF_DEADLINE_PROPERTY);
            return AUTO_OFF_NONE;
        }
    }

    /**
     * Writes the auto-off state, see {@link #readAutoOff()}. Package-private for unit tests.
     *
     * @param state a deadline, {@link #AUTO_OFF_NONE} or {@link #AUTO_OFF_FIRED}
     */
    void writeAutoOff(long state) {
        if (!devMode) {
            localAutoOff.set(state);
        } else if (state == AUTO_OFF_NONE) {
            System.clearProperty(AUTO_OFF_DEADLINE_PROPERTY);
        } else {
            System.setProperty(AUTO_OFF_DEADLINE_PROPERTY, Long.toString(state));
        }
    }

    /**
     * Reads the dev-mode manual deactivation, see {@link #MANUAL_OFF_PROPERTY}. Always {@code false} outside dev mode, so
     * a test application started by continuous testing is never held off by the dev session's decision. Any value other
     * than {@code "true"} reads as "not deactivated" and is therefore self-healing.
     *
     * @return whether chaos was deactivated by an explicit action in this dev session
     */
    boolean readManualOff() {
        return devMode && Boolean.parseBoolean(System.getProperty(MANUAL_OFF_PROPERTY));
    }

    /**
     * Writes the dev-mode manual deactivation, see {@link #MANUAL_OFF_PROPERTY}. A no-op outside dev mode: a test engine
     * keeps the decision in memory for the duration of the test. Package-private for unit tests.
     *
     * @param manualOff whether chaos was deactivated by an explicit action
     */
    void writeManualOff(boolean manualOff) {
        if (!devMode) {
            return;
        }
        if (manualOff) {
            System.setProperty(MANUAL_OFF_PROPERTY, Boolean.TRUE.toString());
        } else {
            System.clearProperty(MANUAL_OFF_PROPERTY);
        }
    }

    public boolean shouldAssault() {
        MutableAssaultConfig cfg = configSnapshot();
        if (!isActive() || cfg == null || !cfg.hasAnyAssaultEnabled()) {
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
        if (!isActive() || cfg == null || !cfg.hasAnyAssaultEnabled()) {
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
        if (!isActive() || cfg == null || !cfg.isLayerEnabled(ChaosLayer.HTTP_OUT) || !cfg.hasAnyClientAssaultEnabled()) {
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
        // the replaced configuration must not become the "previous" side of the next change, and the replacement reports
        // its own changes like the configuration installed by initialize()
        synchronized (configChangeLock) {
            this.lastNotifiedConfig = config != null ? config.snapshot() : null;
        }
        if (config != null) {
            config.setOnChange(this::configChanged);
        }
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
        sourceCounts.computeIfAbsent(source != null ? source : AssaultSource.SERVER, k -> new AtomicLong())
                .incrementAndGet();
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
     * Returns the per-source assault counts, keyed by source tag (e.g. {@code "server"}, {@code "rest-client"}).
     *
     * @return an unmodifiable snapshot of the per-source counts
     */
    public Map<String, Long> getAssaultCountsBySource() {
        var result = new java.util.HashMap<String, Long>();
        sourceCounts.forEach((k, v) -> result.put(k.tag(), v.get()));
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
        sourceCounts.clear();
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
