package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.quarkus.runtime.LaunchMode;

/**
 * Guards the launch-mode contract of {@link AssaultEngine}: chaos only activates in dev or test mode, a packaged
 * production application always starts inactive without reading the state file (regression for a NullPointerException
 * and for silent activation in {@code NORMAL} mode), and the persisted Dev UI state is only restored in dev mode so
 * integration tests are never contaminated by a local {@code .goblin-state.json}.
 */
class AssaultEngineLifecycleTest {

    private Path stateFile;

    @AfterEach
    void reset() throws IOException {
        System.clearProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY);
        GoblinStatePersistence.overrideStateFile(null);
        if (stateFile != null) {
            Files.deleteIfExists(stateFile);
            stateFile = null;
        }
    }

    @Test
    void normalModeStaysInactiveAndConsumesNeitherStateNorStaticConfig() throws IOException {
        AssaultEngine engine = new AssaultEngine();
        writeStateFile();

        engine.initialize(LaunchMode.NORMAL);

        assertFalse(engine.isActive(), "a packaged production app must never run chaos");
        assertNull(engine.getMutableConfig(),
                "in NORMAL mode neither the state file nor the configuration must be consulted (regression: NPE)");
    }

    @Test
    void testModeStartsFromStaticConfigAndIgnoresStateFile() throws IOException {
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500, true);
        writeStateFile();

        engine.initialize(LaunchMode.TEST);

        assertTrue(engine.isActive(), "quarkus.goblin.test.enabled=true opts the tests in");
        assertNotNull(engine.getMutableConfig());
        assertEquals(500, engine.getMutableConfig().getLatencyMaxMs(),
                "the test configuration must come from the static config, not from the local Dev UI state file (max=9000)");
        assertEquals(100, engine.getMutableConfig().getLatencyMinMs());
        assertEquals(AssaultProfile.NONE, engine.getMutableConfig().getProfile(),
                "the SLOW_FAILURE profile saved in the state file must not leak into tests");
    }

    @Test
    void elapsedAutoOffDeactivatesTheEngineWithoutAnyDevUi() throws IOException {
        isolateStateFile();
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500);
        engine.initialize(LaunchMode.DEVELOPMENT);
        assertTrue(engine.isActive());

        engine.scheduleAutoOff(60_000);
        assertTrue(engine.isActive(), "a pending auto-off leaves chaos on");
        assertTrue(engine.autoOffDeadline() > System.currentTimeMillis());

        System.setProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY, Long.toString(System.currentTimeMillis() - 1));
        assertFalse(engine.shouldAssault(), "an elapsed deadline stops the assaults on the next request");
        assertFalse(engine.isActive());
        assertEquals(0, engine.autoOffDeadline(), "the elapsed deadline is consumed");
    }

    @Test
    void deactivatingChaosCancelsThePendingAutoOff() throws IOException {
        isolateStateFile();
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500);
        engine.initialize(LaunchMode.DEVELOPMENT);
        engine.scheduleAutoOff(60_000);

        engine.setActive(false);

        assertEquals(0, engine.autoOffDeadline());
        engine.setActive(true);
        assertTrue(engine.isActive(), "re-enabling chaos is not affected by the cancelled auto-off");
    }

    @Test
    void autoOffElapsedDuringALiveReloadKeepsChaosOff() throws IOException {
        isolateStateFile();
        System.setProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY, Long.toString(System.currentTimeMillis() - 1));
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500);

        engine.initialize(LaunchMode.DEVELOPMENT);

        assertFalse(engine.isActive(), "quarkus.goblin.enabled=true must not resurrect chaos past its auto-off deadline");
        assertEquals(0, engine.autoOffDeadline());
    }

    @Test
    void pendingAutoOffSurvivesALiveReload() throws IOException {
        isolateStateFile();
        AssaultEngine first = new AssaultEngine();
        first.config = config(100, 500);
        first.initialize(LaunchMode.DEVELOPMENT);
        long deadline = first.scheduleAutoOff(60_000);

        AssaultEngine reloaded = new AssaultEngine();
        reloaded.config = config(100, 500);
        reloaded.initialize(LaunchMode.DEVELOPMENT);

        assertTrue(reloaded.isActive());
        assertEquals(deadline, reloaded.autoOffDeadline());
    }

    @Test
    void devModeRestoresStateFileOverStaticConfig() throws IOException {
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500);
        writeStateFile();

        engine.initialize(LaunchMode.DEVELOPMENT);

        assertTrue(engine.isActive());
        assertNotNull(engine.getMutableConfig());
        assertEquals(9000, engine.getMutableConfig().getLatencyMaxMs(),
                "in dev mode the persisted state file must win over application.properties");
        assertEquals(AssaultProfile.SLOW_FAILURE, engine.getMutableConfig().getProfile());
    }

    @Test
    void testModeStaysInactiveWithoutOptInButLoadsItsConfiguration() {
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500, false);

        engine.initialize(LaunchMode.TEST);

        assertFalse(engine.isActive(), "an application's tests must not be assaulted unless quarkus.goblin.test.enabled");
        assertNotNull(engine.getMutableConfig(), "the configuration is loaded so a test can switch chaos on");
        assertEquals(500, engine.getMutableConfig().getLatencyMaxMs());

        engine.setActive(true);
        assertTrue(engine.isActive(), "a test can opt in programmatically");
    }

    @Test
    void theTestOptInDoesNotAffectDevMode() {
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500, false);

        engine.initialize(LaunchMode.DEVELOPMENT);

        assertTrue(engine.isActive(), "dev mode keeps chaos on by default");
    }

    /**
     * Writes a state file with values that differ from {@link #config(int, int)} (latency max 500) so each launch mode
     * can be told apart: latency enabled at a 9000 ms maximum under the {@code SLOW_FAILURE} profile.
     */
    private void isolateStateFile() throws IOException {
        stateFile = Files.createTempFile("goblin-state-", ".json");
        Files.delete(stateFile);
        GoblinStatePersistence.overrideStateFile(stateFile.toString());
    }

    private void writeStateFile() throws IOException {
        stateFile = Files.createTempFile("goblin-state-", ".json");
        GoblinStatePersistence.overrideStateFile(stateFile.toString());
        MutableAssaultConfig persisted = new MutableAssaultConfig();
        persisted.restoreProfile(AssaultProfile.SLOW_FAILURE);
        persisted.setLatencyEnabled(true);
        persisted.setLatencyMaxMs(9000);
        GoblinStatePersistence.save(persisted);
        stateFile.toFile().deleteOnExit();
    }

    private static GoblinConfig config(int latencyMin, int latencyMax) {
        return config(latencyMin, latencyMax, true);
    }

    private static GoblinConfig config(int latencyMin, int latencyMax, boolean testModeEnabled) {
        return new GoblinConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public TestConfig test() {
                return () -> testModeEnabled;
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
                        return AssaultProfile.NONE;
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

                };
            }
        };
    }
}
