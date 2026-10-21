package io.quarkiverse.goblin.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.*;
import io.quarkiverse.goblin.internal.state.GoblinStatePersistence;
import io.quarkus.runtime.LaunchMode;

class AssaultEngineTest {

    private static final int THREADS = 8;
    private static final int ASSAULTS_PER_THREAD = 100;

    @Test
    void concurrentRecordAssaultDoesNotLoseEntries() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        AtomicInteger failures = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ASSAULTS_PER_THREAD; i++) {
                        engine.recordAssault(AssaultType.LATENCY, "Thread" + threadId, "latency", i);
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, failures.get(), "no worker should have thrown");
        assertEquals(THREADS * ASSAULTS_PER_THREAD, engine.getHistory().size(),
                "every recorded assault must be present in the history");
    }

    @Test
    void concurrentReadsAndWritesDoNotThrow() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < ASSAULTS_PER_THREAD; i++) {
                    engine.recordAssault(AssaultType.EXCEPTION, "writer", "exception");
                }
            } catch (Throwable e) {
                failures.incrementAndGet();
            }
        }));
        for (int r = 0; r < THREADS - 1; r++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ASSAULTS_PER_THREAD; i++) {
                        List<AssaultEngine.AssaultRecord> snapshot = engine.getHistory();
                        if (!snapshot.isEmpty()) {
                            snapshot.getFirst();
                            snapshot.get(snapshot.size() - 1);
                        }
                    }
                } catch (Throwable e) {
                    failures.incrementAndGet();
                }
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        assertEquals(0, failures.get(), "no worker should have thrown");
        assertFalse(engine.getHistory().isEmpty());
    }

    @Test
    void historyIsCappedAt1000Entries() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        for (int i = 0; i < 1250; i++) {
            engine.recordAssault(AssaultType.HTTP_STATUS, "capped", "http-status", i);
        }

        List<AssaultEngine.AssaultRecord> history = engine.getHistory();
        assertEquals(1000, history.size());
        assertEquals(250L, history.getFirst().latencyMs());
        assertEquals(1249L, history.get(history.size() - 1).latencyMs());
    }

    @Test
    void getHistoryReturnsImmutableSnapshot() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.recordAssault(AssaultType.LATENCY, "immutable", "latency", 1);

        List<AssaultEngine.AssaultRecord> snapshot = engine.getHistory();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        engine.recordAssault(AssaultType.LATENCY, "immutable", "latency", 2);
        assertTrue(engine.getHistory().size() > snapshot.size());
    }

    @Test
    void historyIsEmptyWhenNoAssaultRecorded() {
        assertEquals(0, new AssaultEngineImpl().getHistory().size());
    }

    @Test
    void countersTrackTotalAndPerType() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.recordAssault(AssaultType.LATENCY, "a", "latency");
        engine.recordAssault(AssaultType.EXCEPTION, "b", "exception");
        engine.recordAssault(AssaultType.LATENCY, "c", "latency");

        assertEquals(3, engine.getTotalAssaultCount());
        assertEquals(2L, engine.getAssaultCounts().get("latency"));
        assertEquals(1L, engine.getAssaultCounts().get("exception"));
        assertTrue(engine.getCountersSinceEpoch() <= System.currentTimeMillis());
    }

    @Test
    void resetCountersClearsTotalsAndPerType() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.recordAssault(AssaultType.LATENCY, "a", "latency");

        engine.resetCounters();

        assertEquals(0, engine.getTotalAssaultCount());
        assertTrue(engine.getAssaultCounts().isEmpty());
    }

    @Test
    void shouldAssaultClientRequiresActiveEngineAndClientToggle() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseWhenOnlyServerTogglesEnabled() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLatencyEnabled(true);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient(), "server-side toggles must not trigger client-side assaults");
    }

    @Test
    void shouldAssaultClientTrueWhenClientToggleAndLevel100() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setClientLatencyEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertTrue(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseAtLevelZero() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setClientExceptionEnabled(true);
        config.setTargetLevel(0);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseWhenServerToggleOnlyAndLevel100() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setHttpStatusEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientRequiresHttpOutLayerArmed() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setClientExceptionEnabled(true);
        config.setTargetLevel(100);
        config.setLayerEnabled(ChaosLayer.HTTP_OUT, false);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient(),
                "client assaults must not fire when the HTTP_OUT layer is disarmed, even at level 100");
    }

    @Test
    void resolveAssaultLayerNullWhileEngineInactive() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer(), "an inactive engine must never resolve a layer");
    }

    @Test
    void resolveAssaultLayerNullWhenNoServerAssaultArmed() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(false);
        config.setDependencyDegradationEnabled(false);
        config.setClientLatencyEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer(),
                "only client-side toggles cannot arm any inbound layer");
    }

    @Test
    void resolveAssaultLayerSelectsHttpInWithDefaultLayersAtLevel100() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "with only HTTP_IN actionable and a guaranteed gate, HTTP_IN must win");
    }

    @Test
    void resolveAssaultLayerNullWhenNoActionableLayerArmed() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayerEnabled(ChaosLayer.HTTP_IN, false);
        config.setLayerEnabled(ChaosLayer.HTTP_OUT, false);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer());
    }

    @Test
    void resolveAssaultLayerDeepestWinnerAtLevel100() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.SERVICE, engine.resolveAssaultLayer(),
                "the deepest armed and actionable layer must win at level 100, shadowing HTTP_IN");
    }

    @Test
    void resolveAssaultLayerSkipsLayersWhoseHookIsNotInstalled() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayerEnabled(ChaosLayer.DATABASE, true);
        config.setLayerEnabled(ChaosLayer.MESSAGING, true);
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.SERVICE, engine.resolveAssaultLayer(),
                "without a datasource nor messaging, DATABASE and MESSAGING must be skipped in favour of SERVICE");
    }

    @Test
    void resolveAssaultLayerSkipsServiceWhenNotActionable() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(false);
        config.setHttpStatusEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "an armed SERVICE layer without latency or exception is not actionable and must fall through to HTTP_IN");
    }

    @Test
    void resolveAssaultLayerNullWhenGateFailsAtLevelZero() throws Exception {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setTargetLevel(0);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer(), "a failed gate applies to every layer in the request");
    }

    @Test
    void failingObserverDoesNotBreakTheAssaultFlow() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        List<AssaultEngine.AssaultRecord> received = new ArrayList<>();
        AssaultObserver failing = new AssaultObserver() {
            @Override
            public void onAssault(AssaultEngine.AssaultRecord record) {
                throw new RuntimeException("observer exploded");
            }
        };
        AssaultObserver healthy = new AssaultObserver() {
            @Override
            public void onAssault(AssaultEngine.AssaultRecord record) {
                received.add(record);
            }
        };
        engine.setObserversForTests(new FakeInstance(failing, healthy));

        assertDoesNotThrow(() -> engine.recordAssault(AssaultType.LATENCY, "hello", "latency", 150));

        assertEquals(1, engine.getHistory().size(), "the assault must be recorded despite the failing observer");
        assertEquals(1, engine.getTotalAssaultCount(), "the counter must be incremented despite the failing observer");
        assertEquals(1, received.size(), "healthy observers must still be notified alongside the failing one");
        assertEquals("hello", received.getFirst().method());
        assertEquals(150, received.getFirst().latencyMs());

        assertDoesNotThrow(() -> engine.setActive(false));
        assertFalse(engine.isActive(), "the state change must apply despite the failing observer");
    }

    @Test
    void failingObserversDoNotBlockFullyFailingNotifications() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        AssaultObserver failing = new AssaultObserver() {
            @Override
            public void onAssault(AssaultEngine.AssaultRecord record) {
                throw new RuntimeException("onAssault exploded");
            }

            @Override
            public void onActiveChange(boolean active) {
                throw new RuntimeException("onActiveChange exploded");
            }
        };
        engine.setObserversForTests(new FakeInstance(failing));

        assertDoesNotThrow(() -> engine.recordAssault(AssaultType.EXCEPTION, "hello", "exception"));
        assertDoesNotThrow(() -> engine.setActive(true));

        assertEquals(1, engine.getHistory().size());
        assertTrue(engine.isActive());
    }

    /** Records the configuration changes it receives. */
    private static final class ConfigChangeRecorder implements AssaultObserver {
        final List<AssaultConfigChange> changes = new CopyOnWriteArrayList<>();

        @Override
        public void onConfigChange(AssaultConfigChange change) {
            changes.add(change);
        }
    }

    private static AssaultEngineImpl engineIn(LaunchMode mode, AssaultObserver... observers) {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setObserversForTests(new FakeInstance(observers));
        engine.initialize(mode);
        return engine;
    }

    @Test
    void aConfigurationChangeIsNotifiedOnceWithTheConfigurationBeforeAndAfter() {
        ConfigChangeRecorder recorder = new ConfigChangeRecorder();
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, recorder);
        assertTrue(recorder.changes.isEmpty(), "the configuration loaded at startup is not a change");

        engine.getMutableConfig().setTargetLevel(42);

        assertEquals(1, recorder.changes.size(), "one setter call, one notification");
        AssaultConfigChange change = recorder.changes.getFirst();
        assertEquals(100, change.previous().getTargetLevel());
        assertEquals(42, change.current().getTargetLevel());
        assertTrue(change.timestamp() > 0);

        engine.getMutableConfig().setLatencyEnabled(false);

        assertEquals(2, recorder.changes.size());
        assertEquals(42, recorder.changes.get(1).previous().getTargetLevel(),
                "the previous side of a change is the current side of the change before it");
        assertTrue(recorder.changes.get(1).previous().isLatencyEnabled());
        assertFalse(recorder.changes.get(1).current().isLatencyEnabled());
        assertEquals("no assault enabled", recorder.changes.get(1).currentDescription());
    }

    @Test
    void aChangePublishedAtOnceIsASingleNotification() {
        ConfigChangeRecorder recorder = new ConfigChangeRecorder();
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, recorder);

        MutableAssaultConfigImpl staged = engine.getMutableConfig().workingCopy();
        staged.setLatencyEnabled(false);
        staged.setExceptionEnabled(true);
        staged.setTargetLevel(30);
        staged.setLayers(Set.of(ChaosLayer.SERVICE));
        engine.getMutableConfig().replaceWith(staged);

        assertEquals(1, recorder.changes.size(), "a multi-field change staged and published at once notifies once");
        AssaultConfigChange change = recorder.changes.getFirst();
        assertTrue(change.previous().isLatencyEnabled());
        assertFalse(change.previous().isExceptionEnabled());
        assertFalse(change.current().isLatencyEnabled());
        assertTrue(change.current().isExceptionEnabled());
        assertEquals(30, change.current().getTargetLevel());
        assertEquals(Set.of(ChaosLayer.SERVICE), change.current().getLayers());
    }

    @Test
    void anObserverCannotChangeTheConfigurationItIsHanded() {
        ConfigChangeRecorder recorder = new ConfigChangeRecorder();
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, recorder);

        engine.getMutableConfig().setTargetLevel(10);

        AssaultConfigChange change = recorder.changes.getFirst();
        // the API hands read-only AssaultConfig views; casting one back to the implementation still cannot write
        assertThrows(UnsupportedOperationException.class,
                () -> ((MutableAssaultConfigImpl) change.current()).setTargetLevel(99));
        assertThrows(UnsupportedOperationException.class,
                () -> ((MutableAssaultConfigImpl) change.previous()).setLatencyEnabled(false));
        assertEquals(10, engine.getMutableConfig().getTargetLevel());
        assertEquals(1, recorder.changes.size(), "the rejected mutations published nothing");
    }

    @Test
    void aFailingConfigurationObserverNeverReachesTheCaller() {
        ConfigChangeRecorder healthy = new ConfigChangeRecorder();
        AssaultObserver failing = new AssaultObserver() {
            @Override
            public void onConfigChange(AssaultConfigChange change) {
                throw new IllegalStateException("observer exploded");
            }
        };
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, failing, healthy);

        assertDoesNotThrow(() -> engine.getMutableConfig().setTargetLevel(55));

        assertEquals(55, engine.getMutableConfig().getTargetLevel(), "the change applies despite the failing observer");
        assertEquals(1, healthy.changes.size(), "the other observers are still notified");
    }

    @Test
    void aReplacedConfigurationIsNeverThePreviousSideOfAChange() {
        ConfigChangeRecorder recorder = new ConfigChangeRecorder();
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, recorder);
        MutableAssaultConfigImpl replacement = new MutableAssaultConfigImpl();
        replacement.setTargetLevel(7);
        engine.setMutableConfigForTests(replacement);

        replacement.setTargetLevel(8);

        assertEquals(1, recorder.changes.size());
        assertEquals(7, recorder.changes.getFirst().previous().getTargetLevel(),
                "the previous side is the installed configuration, not the one it replaced");
    }

    @Test
    void theStartupValidationIsNotAChange() {
        ConfigChangeRecorder recorder = new ConfigChangeRecorder();
        AssaultEngineImpl engine = engineIn(LaunchMode.TEST, recorder);

        engine.getMutableConfig().validateAndFix();

        assertTrue(recorder.changes.isEmpty(), "validateAndFix publishes without notifying, as documented on the SPI");
    }

    @Test
    void theConfigurationIsPersistedInDevModeOnlyButNotifiedInBoth() throws Exception {
        Path dir = Files.createTempDirectory("goblin-config-change");
        Path stateFile = dir.resolve("goblin-state.json");
        GoblinStatePersistence.overrideStateFile(stateFile.toString());
        try {
            ConfigChangeRecorder testRecorder = new ConfigChangeRecorder();
            AssaultEngineImpl testEngine = engineIn(LaunchMode.TEST, testRecorder);
            testEngine.getMutableConfig().setTargetLevel(20);
            assertEquals(1, testRecorder.changes.size(), "test mode notifies the observers");
            assertFalse(Files.exists(stateFile), "test mode never writes the state file");

            ConfigChangeRecorder devRecorder = new ConfigChangeRecorder();
            AssaultEngineImpl devEngine = engineIn(LaunchMode.DEVELOPMENT, devRecorder);
            devEngine.getMutableConfig().setTargetLevel(21);
            assertEquals(1, devRecorder.changes.size(), "dev mode notifies the observers");
            assertTrue(Files.exists(stateFile), "dev mode persists the change");
        } finally {
            GoblinStatePersistence.overrideStateFile(null);
            System.clearProperty(AssaultEngineImpl.MANUAL_OFF_PROPERTY);
            System.clearProperty(AssaultEngineImpl.AUTO_OFF_DEADLINE_PROPERTY);
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(dir);
        }
    }

    private static final class FakeInstance implements Instance<AssaultObserver> {

        private final List<AssaultObserver> observers;

        FakeInstance(AssaultObserver... observers) {
            this.observers = List.of(observers);
        }

        @Override
        public AssaultObserver get() {
            return observers.getFirst();
        }

        @Override
        public Instance<AssaultObserver> select(Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends AssaultObserver> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            return (Instance<U>) (Instance<?>) FakeInstance.this;
        }

        @Override
        public <U extends AssaultObserver> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            return (Instance<U>) (Instance<?>) FakeInstance.this;
        }

        @Override
        public boolean isUnsatisfied() {
            return observers.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(AssaultObserver instance) {
        }

        @Override
        public Instance.Handle<AssaultObserver> getHandle() {
            return new FakeHandle(observers.getFirst());
        }

        @Override
        public Iterable<Instance.Handle<AssaultObserver>> handles() {
            return (Iterable<Instance.Handle<AssaultObserver>>) (Iterable<?>) observers.stream().map(FakeHandle::new).toList();
        }

        @Override
        public Iterator<AssaultObserver> iterator() {
            return observers.iterator();
        }
    }

    private static final class FakeHandle implements Instance.Handle<AssaultObserver> {

        private final AssaultObserver observer;

        FakeHandle(AssaultObserver observer) {
            this.observer = observer;
        }

        @Override
        public AssaultObserver get() {
            return observer;
        }

        @Override
        public jakarta.enterprise.inject.spi.Bean<AssaultObserver> getBean() {
            return null;
        }

        @Override
        public void destroy() {
        }

        @Override
        public void close() {
        }
    }

    private static AssaultEngineImpl engineWithLayers(Set<ChaosLayer> hooks, ChaosLayer... layers) {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        engine.setOptionalHooksForTests(hooks);
        engine.setActive(true);
        MutableAssaultConfigImpl config = new MutableAssaultConfigImpl();
        config.setLayers(List.of(layers));
        config.setExceptionEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);
        return engine;
    }

    @Test
    void optionalHooksAreOnlyAvailableOnceDeclared() {
        AssaultEngineImpl engine = new AssaultEngineImpl();
        assertFalse(engine.isLayerAvailable(ChaosLayer.DATABASE));
        assertFalse(engine.isLayerAvailable(ChaosLayer.MESSAGING));
        assertTrue(engine.isLayerAvailable(ChaosLayer.SERVICE));

        engine.setOptionalHooksForTests(Set.of(ChaosLayer.DATABASE));

        assertTrue(engine.isLayerAvailable(ChaosLayer.DATABASE));
        assertFalse(engine.isLayerAvailable(ChaosLayer.MESSAGING));
        assertEquals(Set.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE, ChaosLayer.HTTP_OUT, ChaosLayer.HTTP_IN),
                engine.getAvailableLayers());
    }

    @Test
    void databaseWinsAnHttpRequestOnceItsHookIsInstalled() {
        AssaultEngineImpl engine = engineWithLayers(Set.of(ChaosLayer.DATABASE), ChaosLayer.DATABASE, ChaosLayer.SERVICE,
                ChaosLayer.HTTP_IN);

        assertEquals(ChaosLayer.DATABASE, engine.resolveAssaultLayer());
    }

    @Test
    void anHttpRequestNeverResolvesToMessaging() {
        AssaultEngineImpl engine = engineWithLayers(Set.of(ChaosLayer.MESSAGING), ChaosLayer.MESSAGING, ChaosLayer.HTTP_IN);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "MESSAGING is a consumer entry point: an HTTP request falls through to the next layer");
    }

    @Test
    void aConsumedMessageResolvesAmongDatabaseMessagingAndService() {
        Set<ChaosLayer> hooks = Set.of(ChaosLayer.DATABASE, ChaosLayer.MESSAGING);

        assertEquals(ChaosLayer.MESSAGING,
                engineWithLayers(hooks, ChaosLayer.MESSAGING, ChaosLayer.SERVICE)
                        .resolveAssaultLayer(AssaultEngineImpl.MESSAGE_LAYERS));
        assertEquals(ChaosLayer.DATABASE,
                engineWithLayers(hooks, ChaosLayer.DATABASE, ChaosLayer.MESSAGING)
                        .resolveAssaultLayer(AssaultEngineImpl.MESSAGE_LAYERS));
        assertNull(engineWithLayers(hooks, ChaosLayer.HTTP_IN).resolveAssaultLayer(AssaultEngineImpl.MESSAGE_LAYERS),
                "HTTP_IN never applies to a consumed message");
    }

    @Test
    void optionalLayersOnlyInjectLatencyAndExceptions() {
        AssaultEngineImpl engine = engineWithLayers(Set.of(ChaosLayer.DATABASE), ChaosLayer.DATABASE, ChaosLayer.HTTP_IN);
        engine.getMutableConfig().setExceptionEnabled(false);
        engine.getMutableConfig().setLatencyEnabled(false);
        engine.getMutableConfig().setHttpStatusEnabled(true);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "an HTTP status assault cannot fire at the database layer");
    }
}
