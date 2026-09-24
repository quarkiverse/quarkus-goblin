package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);
        config.setLatencyMinMs(200);
        config.setLatencyMaxMs(800);
        config.setExceptionType("java.io.IOException");
        config.setExceptionMessage("boom");
        config.setHttpStatusCode(418);
        config.setHttpStatusMessage("I'm a teapot");
        config.setTargetLevel(50);
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(150);

        assertEquals(16, callCount.get());
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
    void validateAndFixFlagsExceptionClassThatDoesNotExtendRuntimeException() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWith(100, 5000, 503, "java.util.concurrent.TimeoutException", 100));
        assertDoesNotThrow(config::validateAndFix);
        assertTrue(config.validateAndFix().stream().anyMatch(message -> message.contains("does not extend RuntimeException")));
    }

    @Test
    void exceptionPresetsAreAllThrowableWithoutFallback() {
        assertFalse(MutableAssaultConfig.EXCEPTION_PRESETS.isEmpty());
        for (String preset : MutableAssaultConfig.EXCEPTION_PRESETS) {
            MutableAssaultConfig config = new MutableAssaultConfig();
            assertDoesNotThrow(() -> config.setExceptionType(preset), "preset must not throw: " + preset);
            assertTrue(config.validateAndFix().stream().noneMatch(
                    message -> message.contains("fall back to RuntimeException")),
                    "preset must not trigger the RuntimeException fallback: " + preset);
        }
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

    @Test
    void fromConfigAppliesSlowFailureProfile() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWithProfile(100, 5000, 503, "java.lang.RuntimeException", 100, AssaultProfile.SLOW_FAILURE));

        assertEquals(AssaultProfile.SLOW_FAILURE, config.getProfile());
        assertTrue(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
        assertFalse(config.isHttpStatusEnabled());
        assertFalse(config.isDependencyDegradationEnabled());
        assertTrue(config.describeAssaults().contains("profile SLOW_FAILURE"));
    }

    @Test
    void fromConfigAppliesIntermittentProfile() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWithProfile(100, 5000, 503, "java.lang.RuntimeException", 30, AssaultProfile.INTERMITTENT));

        assertEquals(AssaultProfile.INTERMITTENT, config.getProfile());
        assertTrue(config.isHttpStatusEnabled());
        assertEquals(500, config.getHttpStatusCode());
        assertFalse(config.isLatencyEnabled());
        assertEquals(30, config.getTargetLevel());
    }

    @Test
    void fromConfigAppliesTimeoutProfile() {
        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWithProfile(100, 5000, 503, "java.lang.RuntimeException", 100, AssaultProfile.TIMEOUT));

        assertEquals(AssaultProfile.TIMEOUT, config.getProfile());
        assertTrue(config.isLatencyEnabled());
        assertEquals(30000, config.getLatencyMinMs());
        assertEquals(30000, config.getLatencyMaxMs());
        assertFalse(config.isExceptionEnabled());
    }

    @Test
    void setProfileAppliesDefaultsAndRemainsOverridable() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setProfile(AssaultProfile.SLOW_FAILURE);
        assertTrue(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());

        config.setLatencyEnabled(false);
        assertFalse(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
        assertEquals(AssaultProfile.SLOW_FAILURE, config.getProfile());
    }

    @Test
    void setProfileToNoneLeavesTogglesUntouched() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);

        config.setProfile(AssaultProfile.NONE);
        assertFalse(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
    }

    @Test
    void clientTogglesDefaultOff() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertFalse(config.isClientLatencyEnabled());
        assertFalse(config.isClientExceptionEnabled());
        assertFalse(config.hasAnyClientAssaultEnabled());
    }

    @Test
    void clientTogglesAffectHasAnyClientAssaultEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setClientLatencyEnabled(true);
        assertTrue(config.hasAnyClientAssaultEnabled());

        config.setClientLatencyEnabled(false);
        config.setClientExceptionEnabled(true);
        assertTrue(config.hasAnyClientAssaultEnabled());

        config.setClientExceptionEnabled(false);
        assertFalse(config.hasAnyClientAssaultEnabled());
    }

    @Test
    void clientTogglesDoNotAffectServerHasAnyAssaultEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);
        config.setClientLatencyEnabled(true);

        assertFalse(config.hasAnyAssaultEnabled(), "client toggles must not count towards server assault check");
    }

    @Test
    void layersDefaultToHttpInAndHttpOut() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());
        assertTrue(config.isLayerEnabled(ChaosLayer.HTTP_IN));
        assertTrue(config.isLayerEnabled(ChaosLayer.HTTP_OUT));
        assertFalse(config.isLayerEnabled(ChaosLayer.SERVICE));
    }

    @Test
    void setLayersReplacesTheArmedSet() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayers(List.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN));
        assertEquals(Set.of(ChaosLayer.SERVICE, ChaosLayer.HTTP_IN), config.getLayers());
    }

    @Test
    void setLayersRestoresDefaultWhenNullOrEmpty() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayers(List.of(ChaosLayer.SERVICE));

        config.setLayers(List.of());
        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());

        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setLayers(null);
        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());
    }

    @Test
    void getLayersReturnsAnIsolatedSnapshot() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.getLayers().add(ChaosLayer.SERVICE);
        assertFalse(config.isLayerEnabled(ChaosLayer.SERVICE),
                "mutating the returned set must not affect the configuration");
    }

    @Test
    void setLayerEnabledTogglesASingleLayer() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        assertTrue(config.isLayerEnabled(ChaosLayer.SERVICE));
        assertTrue(config.isLayerEnabled(ChaosLayer.HTTP_IN), "other layers must stay armed");

        config.setLayerEnabled(ChaosLayer.HTTP_IN, false);
        assertFalse(config.isLayerEnabled(ChaosLayer.HTTP_IN));

        config.setLayerEnabled(ChaosLayer.SERVICE, false);
        assertFalse(config.isLayerEnabled(ChaosLayer.SERVICE));
        assertFalse(config.isLayerEnabled(null));
    }

    @Test
    void resetToDefaultsRestoresHttpInAndHttpOutLayers() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setLayerEnabled(ChaosLayer.HTTP_IN, false);
        config.setLayerEnabled(ChaosLayer.HTTP_OUT, false);
        config.resetToDefaults();
        assertEquals(Set.of(ChaosLayer.HTTP_IN, ChaosLayer.HTTP_OUT), config.getLayers());
    }

    @Test
    void describeAssaultsIncludesClientWhenEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);
        String desc = config.describeAssaults();
        assertTrue(desc.contains("client latency"), "expected 'client latency' in: " + desc);
        assertTrue(desc.contains("client exception"), "expected 'client exception' in: " + desc);
    }

    @Test
    void clientTogglesIgnoredByApplyProfileDefaults() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setClientLatencyEnabled(true);
        config.setClientExceptionEnabled(true);

        config.setProfile(AssaultProfile.SLOW_FAILURE);
        assertTrue(config.isClientLatencyEnabled(), "client toggles must survive profile application");
        assertTrue(config.isClientExceptionEnabled());
    }

    @Test
    void restoreProfileOnlyLabelsWithoutApplyingDefaults() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);

        config.restoreProfile(AssaultProfile.SLOW_FAILURE);
        assertEquals(AssaultProfile.SLOW_FAILURE, config.getProfile());
        assertFalse(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
    }

    @Test
    void setProfileAppliesTimeoutDefaults() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setProfile(AssaultProfile.TIMEOUT);

        assertEquals(AssaultProfile.TIMEOUT, config.getProfile());
        assertTrue(config.isLatencyEnabled());
        assertEquals(30000, config.getLatencyMinMs());
        assertEquals(30000, config.getLatencyMaxMs());
    }

    @Test
    void applyingSlowFailureAfterManualOverrideRestoresDefaults() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setProfile(AssaultProfile.TIMEOUT);
        config.setLatencyMaxMs(200);
        config.setLatencyMinMs(100);

        config.setProfile(AssaultProfile.SLOW_FAILURE);
        assertEquals(100, config.getLatencyMinMs());
        assertEquals(5000, config.getLatencyMaxMs());
        assertTrue(config.isLatencyEnabled());
        assertTrue(config.isExceptionEnabled());
    }

    @Test
    void describeAssaultsSaysNoAssaultEnabledEvenWithProfileWhenAllTogglesOff() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setProfile(AssaultProfile.SLOW_FAILURE);
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        assertEquals("no assault enabled", config.describeAssaults());
    }

    @Test
    void bodyAssaultDefaultsOff() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        assertFalse(config.isResponseBodyEnabled());
        assertEquals(ResponseBodyMode.TRUNCATE, config.getResponseBodyMode());
        assertEquals(50, config.getResponseBodyPercentage());
    }

    @Test
    void bodyAssaultCountsTowardsServerHasAnyAssaultEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);
        assertFalse(config.hasAnyAssaultEnabled());

        config.setResponseBodyEnabled(true);
        assertTrue(config.hasAnyAssaultEnabled());
    }

    @Test
    void describeAssaultsIncludesBodyWhenEnabled() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);

        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(150);
        config.setResponseBodyEnabled(true);
        String desc = config.describeAssaults();
        assertTrue(desc.contains("response body inflate enabled"), "expected inflate in: " + desc);
        assertTrue(desc.contains("150%"), "expected percentage in: " + desc);
    }

    @Test
    void truncatePercentageAbove100IsClamped() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyMode(ResponseBodyMode.TRUNCATE);
        config.setResponseBodyPercentage(150);
        assertEquals(100, config.getResponseBodyPercentage());
    }

    @Test
    void inflatePercentageAtOrBelow100IsClampedTo101() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(50);
        assertEquals(101, config.getResponseBodyPercentage());
    }

    @Test
    void inflatePercentageAbove1000IsClamped() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(5000);
        assertEquals(1000, config.getResponseBodyPercentage());
    }

    @Test
    void invalidBodyModeFallsBackToTruncate() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyMode(null);
        assertEquals(ResponseBodyMode.TRUNCATE, config.getResponseBodyMode());
    }

    @Test
    void fromConfigResponseBodyTypeEnabled() {
        GoblinConfig goblinConfig = configWithResponseBody();
        MutableAssaultConfig config = MutableAssaultConfig.fromConfig(goblinConfig);
        assertTrue(config.isResponseBodyEnabled());
        assertEquals(ResponseBodyMode.INFLATE, config.getResponseBodyMode());
        assertEquals(150, config.getResponseBodyPercentage());
    }

    @Test
    void profilesLeaveBodyToggleUntouched() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseBodyEnabled(true);
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(200);

        config.setProfile(AssaultProfile.SLOW_FAILURE);
        assertTrue(config.isResponseBodyEnabled(), "body toggle must survive profile application");
        assertEquals(ResponseBodyMode.INFLATE, config.getResponseBodyMode());
        assertEquals(200, config.getResponseBodyPercentage());
    }

    @Test
    void setResponseHeaderReplacesAnExistingRuleIgnoringCase() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "first");
        config.setResponseHeader("x-goblin", ResponseHeaderAction.SET, "second");

        assertEquals(1, config.getResponseHeaders().size(), "header names are case-insensitive");
        assertEquals("second", config.getResponseHeaders().get("x-goblin").value());
    }

    @Test
    void removeResponseHeaderIgnoresCase() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos");

        config.removeResponseHeader("x-goblin");

        assertTrue(config.getResponseHeaders().isEmpty());
    }

    @Test
    void setResponseHeaderRejectsValuesWithControlCharacters() {
        MutableAssaultConfig config = new MutableAssaultConfig();

        assertThrows(IllegalArgumentException.class,
                () -> config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos\r\nInjected: true"));
        assertThrows(IllegalArgumentException.class,
                () -> config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos\nInjected: true"));
        assertThrows(IllegalArgumentException.class,
                () -> config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "chaos\u0000"));
        assertTrue(config.getResponseHeaders().isEmpty(), "a rejected rule must not be stored");
    }

    @Test
    void isValidResponseHeaderValueAllowsTabButRejectsControlCharacters() {
        assertTrue(MutableAssaultConfig.isValidResponseHeaderValue(null));
        assertTrue(MutableAssaultConfig.isValidResponseHeaderValue("a\tb"));
        assertTrue(MutableAssaultConfig.isValidResponseHeaderValue("plain value; charset=utf-8"));
        assertFalse(MutableAssaultConfig.isValidResponseHeaderValue("a\nb"));
        assertFalse(MutableAssaultConfig.isValidResponseHeaderValue("a\rb"));
        assertFalse(MutableAssaultConfig.isValidResponseHeaderValue("a\u007Fb"));
        assertFalse(MutableAssaultConfig.isValidResponseHeaderValue("a\u0000b"));
    }

    @Test
    void fromConfigSkipsResponseHeaderRulesThatAreNotValid() {
        Map<String, GoblinConfig.HeaderConfig> headers = new java.util.LinkedHashMap<>();
        headers.put("X-Valid", headerRule(ResponseHeaderAction.SET, "chaos"));
        headers.put("X-NoAction", headerRule(null, "chaos"));
        headers.put("X-Unsafe", headerRule(ResponseHeaderAction.SET, "chaos\nInjected: true"));

        MutableAssaultConfig config = MutableAssaultConfig
                .fromConfig(configWithBodyAndHeaders(AssaultType.RESPONSE_HEADER, headers));

        assertTrue(config.isResponseHeaderEnabled());
        assertEquals(1, config.getResponseHeaders().size(),
                "invalid rules must be skipped, never implicitly restored as SET");
        assertTrue(config.getResponseHeaders().containsKey("X-Valid"));
    }

    private static GoblinConfig.HeaderConfig headerRule(ResponseHeaderAction action, String value) {
        return new GoblinConfig.HeaderConfig() {
            @Override
            public ResponseHeaderAction action() {
                return action;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }

    private static GoblinConfig configWith(long latencyMin, long latencyMax, int httpStatus, String exceptionType,
            int targetLevel) {
        return configWithProfile(latencyMin, latencyMax, httpStatus, exceptionType, targetLevel, AssaultProfile.NONE);
    }

    private static GoblinConfig configWithResponseBody() {
        return configWithBodyAndHeaders(AssaultType.RESPONSE_BODY, Map.of());
    }

    private static GoblinConfig configWithBodyAndHeaders(AssaultType type, Map<String, GoblinConfig.HeaderConfig> headers) {
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
                        return type;
                    }

                    @Override
                    public Map<String, HeaderConfig> headers() {
                        return headers;
                    }

                    @Override
                    public AssaultProfile profile() {
                        return AssaultProfile.NONE;
                    }

                    @Override
                    public BodyConfig body() {
                        return new BodyConfig() {
                            @Override
                            public ResponseBodyMode mode() {
                                return ResponseBodyMode.INFLATE;
                            }

                            @Override
                            public int percentage() {
                                return 150;
                            }
                        };
                    }

                    @Override
                    public LatencyConfig latency() {
                        return new LatencyConfig() {
                            @Override
                            public long minMilliseconds() {
                                return 100;
                            }

                            @Override
                            public long maxMilliseconds() {
                                return 5000;
                            }
                        };
                    }

                    @Override
                    public ExceptionConfig exception() {
                        return new ExceptionConfig() {
                            @Override
                            public String type() {
                                return "java.lang.RuntimeException";
                            }

                            @Override
                            public String message() {
                                return "Goblin chaos: simulated exception";
                            }
                        };
                    }

                    @Override
                    public HttpStatusConfig httpStatus() {
                        return new HttpStatusConfig() {
                            @Override
                            public int code() {
                                return 503;
                            }

                            @Override
                            public String message() {
                                return "Service Unavailable (Goblin chaos)";
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
                        return 100;
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

    private static GoblinConfig configWithProfile(long latencyMin, long latencyMax, int httpStatus, String exceptionType,
            int targetLevel, AssaultProfile profile) {
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
                    public Map<String, HeaderConfig> headers() {
                        return Map.of();
                    }

                    @Override
                    public AssaultProfile profile() {
                        return profile;
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

                    @Override
                    public BodyConfig body() {
                        return new BodyConfig() {
                            @Override
                            public ResponseBodyMode mode() {
                                return ResponseBodyMode.TRUNCATE;
                            }

                            @Override
                            public int percentage() {
                                return 50;
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
