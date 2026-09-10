package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MutableAssaultConfigTest {

    @Test
    void onChangeListenerIsCalledOnSetter() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        AtomicInteger callCount = new AtomicInteger(0);
        config.setOnChange(callCount::incrementAndGet);

        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);
        config.setHttpStatusEnabled(true);
        config.setDependencyDegradationEnabled(true);
        config.setLatencyMinMs(200);
        config.setLatencyMaxMs(800);
        config.setExceptionType("java.io.IOException");
        config.setExceptionMessage("boom");
        config.setHttpStatusCode(418);
        config.setHttpStatusMessage("I'm a teapot");
        config.setTargetLevel(50);

        assertEquals(11, callCount.get());
    }

    @Test
    void onChangeListenerNotCalledWhenNotSet() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> {
            config.setLatencyEnabled(false);
            config.setTargetLevel(42);
        });
    }

    @Test
    void onChangeListenerCanBeReplaced() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        AtomicInteger first = new AtomicInteger(0);
        AtomicInteger second = new AtomicInteger(0);

        config.setOnChange(first::incrementAndGet);
        config.setLatencyEnabled(false);
        assertEquals(1, first.get());
        assertEquals(0, second.get());

        config.setOnChange(second::incrementAndGet);
        config.setLatencyEnabled(true);
        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void fromConfigLoadsCorrectValues() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertTrue(config.isLatencyEnabled());
        assertFalse(config.isExceptionEnabled());
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(5000, config.getLatencyMaxMs());
        assertEquals(100, config.getTargetLevel());
    }

    @Test
    void targetLevelIsClamped() {
        MutableAssaultConfig config = new MutableAssaultConfig();

        config.setTargetLevel(150);
        assertEquals(100, config.getTargetLevel());

        config.setTargetLevel(-10);
        assertEquals(0, config.getTargetLevel());
    }

    @Test
    void describeAssaultsReflectsState() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        assertEquals("no assault enabled", config.describeAssaults());

        config.setLatencyEnabled(true);
        config.setLatencyMinMs(100);
        config.setLatencyMaxMs(500);
        assertTrue(config.describeAssaults().contains("latency"));
        assertTrue(config.describeAssaults().contains("100 - 500 ms"));
    }

    @Test
    void hasAnyAssaultEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        assertFalse(config.hasAnyAssaultEnabled());

        config.setExceptionEnabled(true);
        assertTrue(config.hasAnyAssaultEnabled());
    }

    @Test
    void validateAndFixSwapsInvertedLatencyRange() {
        MutableAssaultConfig config = MutableAssaultConfig.fromConfig(configWith(5000, 100, 503,
                "java.lang.RuntimeException", 100));
        config.validateAndFix();
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(5000, config.getLatencyMaxMs());
    }

    @Test
    void validateAndFixDefaultsOutOfRangeHttpStatusTo503() {
        MutableAssaultConfig aboveRange = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 999, "java.lang.RuntimeException", 100));
        aboveRange.validateAndFix();
        assertEquals(503, aboveRange.getHttpStatusCode());

        MutableAssaultConfig belowRange = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 42, "java.lang.RuntimeException", 100));
        belowRange.validateAndFix();
        assertEquals(503, belowRange.getHttpStatusCode());
    }

    @Test
    void validateAndFixKeepsValidValues() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWith(50, 300, 418, "java.lang.RuntimeException", 100));
        config.validateAndFix();
        assertEquals(50, config.getLatencyMinMs());
        assertEquals(300, config.getLatencyMaxMs());
        assertEquals(418, config.getHttpStatusCode());
        assertEquals(100, config.getTargetLevel());
        assertEquals("java.lang.RuntimeException", config.getExceptionType());
    }

    @Test
    void validateAndFixClampsTargetLevel() {
        MutableAssaultConfig tooHigh = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 503, "java.lang.RuntimeException", 250));
        tooHigh.validateAndFix();
        assertEquals(100, tooHigh.getTargetLevel());

        MutableAssaultConfig tooLow = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 503, "java.lang.RuntimeException", -15));
        tooLow.validateAndFix();
        assertEquals(0, tooLow.getTargetLevel());
    }

    @Test
    void validateAndFixHandlesNonexistentExceptionClass() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 503, "com.example.DoesNotExist", 100));
        assertDoesNotThrow(config::validateAndFix);
        assertEquals("com.example.DoesNotExist", config.getExceptionType());
    }

    @Test
    void validateAndFixHandlesExceptionClassWithoutStringConstructor() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 503, "java.util.ArrayList", 100));
        assertDoesNotThrow(config::validateAndFix);
    }

    @Test
    void setLatencyRangeSwapsInvertedPair() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyRange(10000, 500);
        assertEquals(500, config.getLatencyMinMs());
        assertEquals(10000, config.getLatencyMaxMs());
    }

    @Test
    void setHttpStatusCodeOutOfRangeDefaultsTo503() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setHttpStatusCode(999);
        assertEquals(503, config.getHttpStatusCode());

        config.setHttpStatusCode(42);
        assertEquals(503, config.getHttpStatusCode());
    }

    @Test
    void setHttpStatusCodeWithinRangeKept() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setHttpStatusCode(418);
        assertEquals(418, config.getHttpStatusCode());
    }

    @Test
    void setHttpStatusCodeAtBoundariesKept() {
        MutableAssaultConfig config = new MutableAssaultConfig();

        config.setHttpStatusCode(100);
        assertEquals(100, config.getHttpStatusCode());

        config.setHttpStatusCode(599);
        assertEquals(599, config.getHttpStatusCode());
    }

    @Test
    void setExceptionTypeAcceptsNonexistentClassWithoutThrowing() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertDoesNotThrow(() -> config.setExceptionType("com.example.DoesNotExist"));
        assertEquals("com.example.DoesNotExist", config.getExceptionType());
    }

    private static GoblinConfig configWith(long latencyMin, long latencyMax, int httpStatus, String exceptionType,
            int targetLevel) {
        return new GoblinConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public AssaultConfig assault() {
                return new AssaultConfig() {
                    @Override
                    public AssaultType type() {
                        return AssaultType.LATENCY;
                    }

                    @Override
                    public LatencyConfig latency() {
                        return new LatencyConfig() {
                            @Override
                            public long minMilliseconds() {
                                return latencyMin;
                            }

                            @Override
                            public long maxMilliseconds() {
                                return latencyMax;
                            }
                        };
                    }

                    @Override
                    public ExceptionConfig exception() {
                        return new ExceptionConfig() {
                            @Override
                            public String type() {
                                return exceptionType;
                            }

                            @Override
                            public String message() {
                                return "boom";
                            }
                        };
                    }

                    @Override
                    public HttpStatusConfig httpStatus() {
                        return new HttpStatusConfig() {
                            @Override
                            public int code() {
                                return httpStatus;
                            }

                            @Override
                            public String message() {
                                return "unavailable";
                            }
                        };
                    }
                };
            }

            @Override
            public TargetConfig target() {
                return new TargetConfig() {
                    @Override
                    public int level() {
                        return targetLevel;
                    }

                    @Override
                    public Optional<String[]> includePackages() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String[]> excludePackages() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<String[]> excludeAnnotations() {
                        return Optional.empty();
                    }
                };
            }
        };
    }
}
