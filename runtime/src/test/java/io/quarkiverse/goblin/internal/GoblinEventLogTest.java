package io.quarkiverse.goblin.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.AssaultSource;
import io.quarkiverse.goblin.AssaultType;
import io.quarkiverse.goblin.ResponseBodyMode;
import io.quarkiverse.goblin.ResponseHeaderAction;
import io.quarkiverse.goblin.internal.state.GoblinStatePersistence;
import io.quarkus.runtime.LaunchMode;

class GoblinEventLogTest {

    /** A log event as the handler saw it, with the Goblin MDC fields present while it was logged. */
    private record Captured(Level level, String message, Map<String, Object> mdc) {
    }

    private final List<Captured> captured = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            Map<String, Object> mdc = new LinkedHashMap<>();
            for (String key : List.of("event", "method", "value", "reason", "previous")) {
                Object value = MDC.get(GoblinEventLog.MDC_PREFIX + key);
                if (value != null) {
                    mdc.put(key, value);
                }
            }
            captured.add(new Captured(record.getLevel(), record.getMessage(), mdc));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger category = Logger.getLogger(GoblinEventLog.CATEGORY);
    private Level previousLevel;

    @BeforeEach
    void attachHandler() {
        previousLevel = category.getLevel();
        category.setLevel(Level.ALL);
        category.addHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        category.removeHandler(handler);
        category.setLevel(previousLevel);
    }

    private List<Captured> events(String name) {
        return captured.stream().filter(c -> c.message().startsWith("goblin event=" + name + " ")).toList();
    }

    @Test
    void formatQuotesAndEscapesValuesSoAnEventStaysOnOneLine() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("plain", "latency");
        fields.put("spaced", "a b");
        fields.put("equals", "a=b");
        fields.put("quoted", "say \"hi\"");
        fields.put("backslash", "C:\\tmp x");
        fields.put("multiline", "first\nsecond\r\tthird");
        fields.put("control", "bell\u0007");
        fields.put("empty", "");

        String line = GoblinEventLog.format(fields);

        assertEquals("goblin plain=latency spaced=\"a b\" equals=\"a=b\" quoted=\"say \\\"hi\\\"\" "
                + "backslash=\"C:\\\\tmp x\" multiline=\"first\\nsecond\\r\\tthird\" control=\"bell\\u0007\" empty=\"\"",
                line);
        assertFalse(line.contains("\n"));
    }

    @Test
    void anAssaultIsLoggedAtInfoWithTheInjectedValueAndConfig() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.initialize(LaunchMode.TEST);
        engine.getMutableConfig().setLatencyRange(100, 200);

        engine.recordAssault(AssaultSource.REST_CLIENT, AssaultType.LATENCY, "REST-Client GET http://x", "latency", 142);

        List<Captured> assaults = events("assault");
        assertEquals(1, assaults.size(), captured.toString());
        Captured event = assaults.getFirst();
        assertEquals(Level.INFO.intValue(), event.level().intValue());
        assertTrue(event.message().matches("goblin event=assault timestamp=\\S+Z source=rest-client "
                + "method=\"REST-Client GET http://x\" type=latency assault-type=latency value=142ms "
                + "config=\"latency enabled \\(100 - 200 ms\\)\""), event.message());
        assertEquals(Map.of("event", "assault", "method", "REST-Client GET http://x", "value", "142ms"), event.mdc());
        assertNull(MDC.get(GoblinEventLog.MDC_PREFIX + "event"), "the MDC is cleaned up after the event");
        assertEquals("142ms", engine.getHistory().getFirst().injectedValue());
    }

    @Test
    void theLifecycleIsLoggedAtWarn() {
        // test mode without a configuration: chaos starts active, nothing is persisted, the auto-off stays local
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.initialize(LaunchMode.TEST);

        assertEquals(1, events("activated").size(), "chaos active at startup is an event");
        assertTrue(events("activated").getFirst().message().contains(" origin=startup profile=NONE level=100 "),
                events("activated").getFirst().message());

        engine.getMutableConfig().setTargetLevel(42);
        Captured change = events("config-change").getFirst();
        assertEquals(Level.WARNING.intValue(), change.level().intValue());
        assertTrue(change.message().contains(" level=42 "), change.message());
        assertEquals("latency enabled (100 - 5000 ms)", change.mdc().get("previous"));

        engine.scheduleAutoOff(60_000);
        assertEquals(1, events("auto-off-scheduled").size());

        engine.setActive(false);
        assertEquals("manual", events("deactivated").getFirst().mdc().get("reason"));
        engine.setActive(true);
        assertTrue(events("activated").get(1).message().contains(" origin=manual "));
    }

    @Test
    void anElapsedAutoOffIsLoggedAsADeactivation() throws InterruptedException {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.initialize(LaunchMode.TEST);

        engine.scheduleAutoOff(1);
        Thread.sleep(20);
        assertFalse(engine.isActive());

        Captured deactivated = events("deactivated").getFirst();
        assertEquals(Level.WARNING.intValue(), deactivated.level().intValue());
        assertEquals("auto-off", deactivated.mdc().get("reason"));
    }

    @Test
    void anAutoOffElapsedDuringARestartIsLoggedOnce() throws Exception {
        Path dir = Files.createTempDirectory("goblin-event-log");
        GoblinStatePersistence.overrideStateFile(dir.resolve("goblin-state.json").toString());
        try {
            // the deadline elapsed while the dev process was restarting: the new engine reports the deactivation
            System.setProperty(AssaultEngineImpl.AUTO_OFF_DEADLINE_PROPERTY, "1");
            new AssaultEngineImpl().initialize(LaunchMode.DEVELOPMENT);
            assertEquals(1, events("deactivated").size(), captured.toString());
            assertEquals("auto-off", events("deactivated").getFirst().mdc().get("reason"));
            assertTrue(events("activated").isEmpty(), "chaos stays off after the restart");

            // the previous engine already reported it: a further restart stays silent
            new AssaultEngineImpl().initialize(LaunchMode.DEVELOPMENT);
            assertEquals(1, events("deactivated").size(), captured.toString());
        } finally {
            GoblinStatePersistence.overrideStateFile(null);
            System.clearProperty(AssaultEngineImpl.AUTO_OFF_DEADLINE_PROPERTY);
            System.clearProperty(AssaultEngineImpl.MANUAL_OFF_PROPERTY);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void aGoblinMdcKeySetByTheCallerIsRestored() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.initialize(LaunchMode.TEST);
        MDC.put(GoblinEventLog.MDC_PREFIX + "method", "outer");
        try {
            engine.recordAssault(AssaultType.HTTP_STATUS, "SampleResource.hello", "http-status");

            assertEquals("SampleResource.hello", events("assault").getFirst().mdc().get("method"));
            assertEquals("outer", MDC.get(GoblinEventLog.MDC_PREFIX + "method"));
            assertNull(MDC.get(GoblinEventLog.MDC_PREFIX + "event"));
        } finally {
            MDC.remove(GoblinEventLog.MDC_PREFIX + "method");
        }
    }

    @Test
    void anInvalidExceptionClassIsReportedAsTheRuntimeExceptionActuallyThrown() {
        MutableAssaultConfigImpl cfg = new MutableAssaultConfigImpl();
        cfg.setExceptionType("com.acme.Missing");
        cfg.setExceptionMessage("boom");

        assertEquals("java.lang.RuntimeException: boom", AssaultEngineImpl.injectedValue(cfg, "exception", 0));
    }

    @Test
    void theInjectedValueOfEachBuiltInAssaultComesFromItsConfiguration() {
        MutableAssaultConfigImpl cfg = new MutableAssaultConfigImpl();
        cfg.setExceptionType(IllegalStateException.class.getName());
        cfg.setExceptionMessage("boom");
        cfg.setHttpStatusCode(429);
        cfg.setResponseBodyMode(ResponseBodyMode.INFLATE);
        cfg.setResponseBodyPercentage(200);
        cfg.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "on");

        assertEquals("0ms", AssaultEngineImpl.injectedValue(cfg, "latency", 0));
        assertEquals("java.lang.IllegalStateException: boom", AssaultEngineImpl.injectedValue(cfg, "exception", 0));
        assertEquals("HTTP 429", AssaultEngineImpl.injectedValue(cfg, "http-status", 0));
        assertEquals("HTTP 503", AssaultEngineImpl.injectedValue(cfg, "dependency-degradation", 0));
        assertEquals("inflate 200%", AssaultEngineImpl.injectedValue(cfg, "response-body-inflate", 0));
        assertEquals("X-Goblin: on", AssaultEngineImpl.injectedValue(cfg, "response-header-set:X-Goblin", 0));
        assertEquals("Server removed", AssaultEngineImpl.injectedValue(cfg, "response-header-remove:Server", 0));
        assertEquals("", AssaultEngineImpl.injectedValue(cfg, "my-custom-assault", 0), "a custom assault is unknown");
        assertEquals("30ms", AssaultEngineImpl.injectedValue(cfg, "my-custom-assault", 30));
        assertEquals("", AssaultEngineImpl.injectedValue(null, "exception", 0));
    }
}
