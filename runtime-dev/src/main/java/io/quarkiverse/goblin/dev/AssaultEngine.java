package io.quarkiverse.goblin.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import org.jboss.logging.Logger;

import io.quarkus.runtime.StartupEvent;

@ApplicationScoped
public class AssaultEngine {

    private static final Logger LOG = Logger.getLogger(AssaultEngine.class);
    private static volatile GoblinConfig staticConfig;

    private volatile MutableAssaultConfig mutableConfig;
    private volatile boolean active;
    private final List<AssaultRecord> history = new ArrayList<>();

    public static void setStaticConfig(GoblinConfig config) {
        staticConfig = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (staticConfig != null) {
            this.mutableConfig = MutableAssaultConfig.fromConfig(staticConfig);
            this.active = staticConfig.enabled();
        }
        if (active) {
            LOG.warnf(
                    "Chaos engineering active: %d%% of REST requests subject to assault (latency=%s, exception=%s, httpStatus=%s, dependencyDegradation=%s)",
                    mutableConfig.getTargetLevel(),
                    mutableConfig.isLatencyEnabled(),
                    mutableConfig.isExceptionEnabled(),
                    mutableConfig.isHttpStatusEnabled(),
                    mutableConfig.isDependencyDegradationEnabled());
        }
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

    public List<AssaultRecord> getHistory() {
        return List.copyOf(history);
    }

    public void clearHistory() {
        history.clear();
    }

    public void recordAssault(String method, String type) {
        AssaultRecord record = new AssaultRecord(method, type, System.currentTimeMillis());
        history.add(record);
        if (history.size() > 1000) {
            history.removeFirst();
        }
    }

    public void applyLatency() {
        long min = mutableConfig.getLatencyMinMs();
        long max = mutableConfig.getLatencyMaxMs();
        long delay = ThreadLocalRandom.current().nextLong(min, max + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public RuntimeException createException() {
        String type = mutableConfig.getExceptionType();
        String message = mutableConfig.getExceptionMessage();
        try {
            Class<?> clazz = Class.forName(type);
            var ctor = clazz.getConstructor(String.class);
            return (RuntimeException) ctor.newInstance(message);
        } catch (Exception e) {
            return new RuntimeException(message);
        }
    }

    public int getHttpStatus() {
        return mutableConfig.getHttpStatusCode();
    }

    public String getHttpStatusMessage() {
        return mutableConfig.getHttpStatusMessage();
    }

    public record AssaultRecord(String method, String type, long timestamp) {
    }
}
