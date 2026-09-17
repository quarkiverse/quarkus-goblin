package io.quarkiverse.goblin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

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

    public static void setStaticConfig(GoblinConfig config) {
        staticConfig = config;
    }

    /**
     * Initialises the engine on startup: restores persisted state when present (assault toggles and parameters only --
     * the enabled/active flag is never persisted and always comes from {@code quarkus.goblin.enabled}), otherwise builds
     * the mutable config from the static configuration, then logs the active assaults.
     *
     * @param event the Quarkus startup event
     */
    void onStart(@Observes StartupEvent event) {
        MutableAssaultConfig persisted = GoblinStatePersistence.load();
        if (persisted != null) {
            this.mutableConfig = persisted;
            LOG.info("Loaded previous Goblin state from .goblin-state.json");
        } else if (staticConfig != null) {
            this.mutableConfig = MutableAssaultConfig.fromConfig(staticConfig);
        }
        this.active = staticConfig == null || staticConfig.enabled();
        this.mutableConfig.validateAndFix();
        if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
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
    }

    public boolean shouldAssault() {
        if (!active || mutableConfig == null || !mutableConfig.hasAnyAssaultEnabled()) {
            return false;
        }
        return levelGate();
    }

    /**
     * Decides whether an outbound REST Client call should be assaulted, mirroring {@link #shouldAssault()} for the
     * client side.
     * <p>
     * Client-side assaults only fire when at least one client assault toggle is enabled and the target level gate
     * passes; the server-side toggles are deliberately ignored so a call is never delayed or failed unless the
     * client-side assaults were explicitly enabled.
     *
     * @return {@code true} when the outbound call is eligible for a client-side assault
     */
    public boolean shouldAssaultClient() {
        if (!active || mutableConfig == null || !mutableConfig.hasAnyClientAssaultEnabled()) {
            return false;
        }
        return levelGate();
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
        history.addLast(record);
        while (history.size() > MAX_HISTORY) {
            history.pollFirst();
        }
        totalAssaultCount.incrementAndGet();
        assaultCounts.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
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
