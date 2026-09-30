package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * <p>
 * Also guards the dev-session decisions that must survive a live reload -- the auto-off and the manual deactivation, in
 * both directions -- and that a test application running in the same JVM keeps to its own.
 */
class AssaultEngineLifecycleTest {

    private Path stateFile;

    @AfterEach
    void reset() throws IOException {
        System.clearProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY);
        System.clearProperty(AssaultEngine.MANUAL_OFF_PROPERTY);
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
    void toggleRightAfterTheAutoOffFiredTurnsChaosOffNotBackOn() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        engine.writeAutoOff(System.currentTimeMillis() - 1);

        assertFalse(engine.toggleActive(), "the user saw chaos on and asked to turn it off");
        assertFalse(engine.isActive());
        assertTrue(engine.toggleActive(), "a later toggle switches chaos on again");
    }

    @Test
    void autoOffThatFiredKeepsChaosOffAfterALiveReload() throws IOException {
        isolateStateFile();
        AssaultEngine first = devEngine();
        first.writeAutoOff(System.currentTimeMillis() - 1);
        assertFalse(first.isActive(), "the elapsed deadline fires on the next read");

        AssaultEngine reloaded = devEngine();

        assertFalse(reloaded.isActive(), "quarkus.goblin.enabled=true must not revive chaos the auto-off switched off");
        assertEquals(AssaultEngine.AUTO_OFF_FIRED, reloaded.readAutoOff());
        assertEquals(DeactivationReason.AUTO_OFF, reloaded.inactiveReason(),
                "the reloaded engine must still know the auto-off is what switched it off");
    }

    @Test
    void manualReactivationAfterAnAutoOffIsKeptByTheNextReload() throws IOException {
        isolateStateFile();
        AssaultEngine first = devEngine();
        first.writeAutoOff(System.currentTimeMillis() - 1);
        assertFalse(first.isActive());
        first.setActive(true);

        AssaultEngine reloaded = devEngine();

        assertTrue(reloaded.isActive(), "switching chaos on again forgets the fired auto-off");
    }

    @Test
    void manualDeactivationSurvivesALiveReload() throws IOException {
        isolateStateFile();
        AssaultEngine first = devEngine();
        first.setActive(false);

        AssaultEngine reloaded = devEngine();

        assertFalse(reloaded.isActive(),
                "quarkus.goblin.enabled=true must not re-arm chaos the Dev UI just switched off (regression: the live "
                        + "reload that follows a file save brought the goblin back)");
        assertEquals(DeactivationReason.MANUAL, reloaded.inactiveReason());
    }

    @Test
    void manualDeactivationSurvivesRepeatedLiveReloads() throws IOException {
        isolateStateFile();
        devEngine().setActive(false);

        for (int reload = 1; reload <= 3; reload++) {
            AssaultEngine reloaded = devEngine();
            assertFalse(reloaded.isActive(), "chaos must stay off at reload " + reload);
            assertEquals(DeactivationReason.MANUAL, reloaded.inactiveReason());
        }
    }

    @Test
    void manualReactivationSurvivesTheNextLiveReload() throws IOException {
        isolateStateFile();
        AssaultEngine first = devEngine();
        first.setActive(false);
        first.setActive(true);

        AssaultEngine reloaded = devEngine();

        assertTrue(reloaded.isActive(), "an explicit activation must survive a live reload as well, or chaos could only "
                + "ever be switched on for one reload");
        assertNull(reloaded.inactiveReason());
    }

    @Test
    void manualDeactivationNeverReachesTheStateFile() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        engine.getMutableConfig().setLatencyEnabled(true);
        engine.setActive(false);

        assertTrue(Files.exists(stateFile), "sanity: a configuration change is persisted");
        assertFalse(Files.readString(stateFile).contains("manual-off"),
                "the enabled/active flag is never persisted: a state file could re-arm an extension disabled with "
                        + "quarkus.goblin.enabled=false");
        assertEquals(Boolean.TRUE.toString(), System.getProperty(AssaultEngine.MANUAL_OFF_PROPERTY),
                "the deactivation lives in the dev session only, so a new process takes it from the configuration");
    }

    @Test
    void unreadableManualOffPropertyReadsAsNotDeactivated() throws IOException {
        isolateStateFile();
        System.setProperty(AssaultEngine.MANUAL_OFF_PROPERTY, "not-a-boolean");

        AssaultEngine engine = devEngine();

        assertTrue(engine.isActive(), "an unreadable value must read as \"not deactivated\", never as a stuck switch");
    }

    @Test
    void testModeEngineNeverTouchesTheDevModeManualOff() throws IOException {
        isolateStateFile();
        AssaultEngine dev = devEngine();
        dev.setActive(false);

        // continuous testing boots a test application in the same JVM
        AssaultEngine test = new AssaultEngine();
        test.config = config(100, 500, true);
        test.initialize(LaunchMode.TEST);
        assertTrue(test.isActive(), "a test application is never held off by the dev session decision");
        test.setActive(false);
        test.setActive(true);

        assertFalse(dev.isActive(), "the dev application keeps its manual deactivation");
        assertTrue(dev.readManualOff());
    }

    @Test
    void deactivationReasonTellsTheDevSessionCausesApart() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        engine.setActive(false);
        assertEquals(DeactivationReason.MANUAL, engine.inactiveReason());

        engine.setActive(true);
        assertNull(engine.inactiveReason(), "an active engine has no reason to report");

        engine.writeAutoOff(System.currentTimeMillis() - 1);
        assertFalse(engine.isActive());
        assertEquals(DeactivationReason.AUTO_OFF, engine.inactiveReason(),
                "a fired auto-off has its own state and must not be reported as a manual deactivation");
    }

    @Test
    void deactivationReasonTellsTheConfigurationCausesApart() throws IOException {
        isolateStateFile();
        AssaultEngine active = devEngine();
        assertNull(active.inactiveReason());

        AssaultEngine disabled = new AssaultEngine();
        disabled.config = disabledConfig();
        disabled.initialize(LaunchMode.DEVELOPMENT);
        assertEquals(DeactivationReason.DISABLED, disabled.inactiveReason());

        AssaultEngine testMode = new AssaultEngine();
        testMode.config = config(100, 500, false);
        testMode.initialize(LaunchMode.TEST);
        assertEquals(DeactivationReason.TEST_MODE, testMode.inactiveReason());

        AssaultEngine production = new AssaultEngine();
        production.initialize(LaunchMode.NORMAL);
        assertEquals(DeactivationReason.LAUNCH_MODE, production.inactiveReason());
    }

    @Test
    void activatingChaosKeepsAPendingAutoOff() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        engine.setActive(false);
        long deadline = engine.scheduleAutoOff(60_000);

        engine.setActive(true);

        assertEquals(deadline, engine.autoOffDeadline(), "an auto-off armed before activation still applies");
    }

    @Test
    void testModeEngineNeverTouchesTheDevModeAutoOff() throws IOException {
        isolateStateFile();
        AssaultEngine dev = devEngine();
        long deadline = dev.scheduleAutoOff(60_000);

        // continuous testing boots a test application in the same JVM
        AssaultEngine test = new AssaultEngine();
        test.config = config(100, 500, true);
        test.initialize(LaunchMode.TEST);
        test.scheduleAutoOff(1);
        test.writeAutoOff(System.currentTimeMillis() - 1);
        assertFalse(test.isActive(), "the test engine applies its own auto-off");

        assertEquals(deadline, dev.autoOffDeadline(), "the dev application keeps its pending auto-off");
        assertTrue(dev.isActive());
    }

    @Test
    void elapsedAutoOffStopsEveryEntryPoint() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        engine.getMutableConfig().setLatencyEnabled(true);
        engine.getMutableConfig().setClientLatencyEnabled(true);
        assertNotNull(engine.resolveAssaultLayer(), "sanity: HTTP_IN resolves while chaos is on");
        assertTrue(engine.shouldAssaultClient(), "sanity: HTTP_OUT fires while chaos is on");

        engine.writeAutoOff(System.currentTimeMillis() - 1);

        assertNull(engine.resolveAssaultLayer());
        assertFalse(engine.shouldAssaultClient());
        assertFalse(engine.shouldAssault());
    }

    @Test
    void schedulingAgainReplacesThePendingDeadline() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        long first = engine.scheduleAutoOff(60_000);

        long second = engine.scheduleAutoOff(120_000);

        assertTrue(second > first);
        assertEquals(second, engine.autoOffDeadline());
    }

    @Test
    void unreadableAutoOffPropertyIsDiscarded() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();
        System.setProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY, "not-a-number");

        assertEquals(0, engine.autoOffDeadline());
        assertNull(System.getProperty(AssaultEngine.AUTO_OFF_DEADLINE_PROPERTY));
        assertTrue(engine.isActive());
    }

    @Test
    void autoOffDelayIsBounded() throws IOException {
        isolateStateFile();
        AssaultEngine engine = devEngine();

        assertThrows(IllegalArgumentException.class, () -> engine.scheduleAutoOff(0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.scheduleAutoOff(AssaultEngine.MAX_AUTO_OFF_MILLIS + 1));
    }

    private AssaultEngine devEngine() {
        AssaultEngine engine = new AssaultEngine();
        engine.config = config(100, 500);
        engine.initialize(LaunchMode.DEVELOPMENT);
        return engine;
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

    /**
     * A configuration with {@code quarkus.goblin.enabled=false}, delegating the rest to {@link #config(int, int)}: chaos
     * must then be off for the whole session, and the reported reason must say so.
     *
     * @return a configuration that keeps chaos disabled for the session
     */
    private static GoblinConfig disabledConfig() {
        GoblinConfig delegate = config(100, 500);
        return new GoblinConfig() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public TestConfig test() {
                return delegate.test();
            }

            @Override
            public AssaultConfig assault() {
                return delegate.assault();
            }

            @Override
            public TargetConfig target() {
                return delegate.target();
            }
        };
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
