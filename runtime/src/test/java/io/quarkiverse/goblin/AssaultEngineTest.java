package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AssaultEngineTest {

    private static final int THREADS = 8;
    private static final int ASSAULTS_PER_THREAD = 100;

    @Test
    void concurrentRecordAssaultDoesNotLoseEntries() throws Exception {
        AssaultEngine engine = new AssaultEngine();
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
                        engine.recordAssault("Thread" + threadId, "latency", i);
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
        AssaultEngine engine = new AssaultEngine();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < ASSAULTS_PER_THREAD; i++) {
                    engine.recordAssault("writer", "exception");
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
                            snapshot.get(0);
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
        AssaultEngine engine = new AssaultEngine();
        for (int i = 0; i < 1250; i++) {
            engine.recordAssault("capped", "http-status", i);
        }

        List<AssaultEngine.AssaultRecord> history = engine.getHistory();
        assertEquals(1000, history.size());
        assertEquals(250L, history.get(0).latencyMs());
        assertEquals(1249L, history.get(history.size() - 1).latencyMs());
    }

    @Test
    void getHistoryReturnsImmutableSnapshot() {
        AssaultEngine engine = new AssaultEngine();
        engine.recordAssault("immutable", "latency", 1);

        List<AssaultEngine.AssaultRecord> snapshot = engine.getHistory();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        engine.recordAssault("immutable", "latency", 2);
        assertTrue(engine.getHistory().size() > snapshot.size());
    }

    @Test
    void historyIsEmptyWhenNoAssaultRecorded() {
        assertEquals(0, new AssaultEngine().getHistory().size());
    }

    @Test
    void countersTrackTotalAndPerType() {
        AssaultEngine engine = new AssaultEngine();
        engine.recordAssault("a", "latency");
        engine.recordAssault("b", "exception");
        engine.recordAssault("c", "latency");

        assertEquals(3, engine.getTotalAssaultCount());
        assertEquals(2L, engine.getAssaultCounts().get("latency"));
        assertEquals(1L, engine.getAssaultCounts().get("exception"));
        assertTrue(engine.getCountersSinceEpoch() <= System.currentTimeMillis());
    }

    @Test
    void resetCountersClearsTotalsAndPerType() {
        AssaultEngine engine = new AssaultEngine();
        engine.recordAssault("a", "latency");

        engine.resetCounters();

        assertEquals(0, engine.getTotalAssaultCount());
        assertTrue(engine.getAssaultCounts().isEmpty());
    }

    @Test
    void shouldAssaultClientRequiresActiveEngineAndClientToggle() {
        AssaultEngine engine = new AssaultEngine();
        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseWhenOnlyServerTogglesEnabled() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(true);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient(), "server-side toggles must not trigger client-side assaults");
    }

    @Test
    void shouldAssaultClientTrueWhenClientToggleAndLevel100() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setClientLatencyEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertTrue(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseAtLevelZero() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setClientExceptionEnabled(true);
        config.setTargetLevel(0);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientFalseWhenServerToggleOnlyAndLevel100() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setHttpStatusEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient());
    }

    @Test
    void shouldAssaultClientRequiresHttpOutLayerArmed() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setClientExceptionEnabled(true);
        config.setTargetLevel(100);
        config.setLayerEnabled(ChaosLayer.HTTP_OUT, false);
        engine.setMutableConfigForTests(config);

        assertFalse(engine.shouldAssaultClient(),
                "client assaults must not fire when the HTTP_OUT layer is disarmed, even at level 100");
    }

    @Test
    void resolveAssaultLayerNullWhileEngineInactive() {
        AssaultEngine engine = new AssaultEngine();
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer(), "an inactive engine must never resolve a layer");
    }

    @Test
    void resolveAssaultLayerNullWhenNoServerAssaultArmed() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
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
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "with only HTTP_IN actionable and a guaranteed gate, HTTP_IN must win");
    }

    @Test
    void resolveAssaultLayerNullWhenNoActionableLayerArmed() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayerEnabled(ChaosLayer.HTTP_IN, false);
        config.setLayerEnabled(ChaosLayer.HTTP_OUT, false);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer());
    }

    @Test
    void resolveAssaultLayerDeepestWinnerAtLevel100() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);

        assertEquals(ChaosLayer.SERVICE, engine.resolveAssaultLayer(),
                "the deepest armed and actionable layer must win at level 100, shadowing HTTP_IN");
    }

    @Test
    void resolveAssaultLayerSkipsLayersWhoseHookIsNotInstalled() throws Exception {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
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
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
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
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayerEnabled(ChaosLayer.SERVICE, true);
        config.setTargetLevel(0);
        engine.setMutableConfigForTests(config);

        assertNull(engine.resolveAssaultLayer(), "a failed gate applies to every layer in the request");
    }

    @Test
    void failingObserverDoesNotBreakTheAssaultFlow() {
        AssaultEngine engine = new AssaultEngine();
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

        assertDoesNotThrow(() -> engine.recordAssault("hello", "latency", 150));

        assertEquals(1, engine.getHistory().size(), "the assault must be recorded despite the failing observer");
        assertEquals(1, engine.getTotalAssaultCount(), "the counter must be incremented despite the failing observer");
        assertEquals(1, received.size(), "healthy observers must still be notified alongside the failing one");
        assertEquals("hello", received.get(0).method());
        assertEquals(150, received.get(0).latencyMs());

        assertDoesNotThrow(() -> engine.setActive(false));
        assertFalse(engine.isActive(), "the state change must apply despite the failing observer");
    }

    @Test
    void failingObserversDoNotBlockFullyFailingNotifications() {
        AssaultEngine engine = new AssaultEngine();
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

        assertDoesNotThrow(() -> engine.recordAssault("hello", "exception"));
        assertDoesNotThrow(() -> engine.setActive(true));

        assertEquals(1, engine.getHistory().size());
        assertTrue(engine.isActive());
    }

    private static final class FakeInstance implements Instance<AssaultObserver> {

        private final List<AssaultObserver> observers;

        FakeInstance(AssaultObserver... observers) {
            this.observers = List.of(observers);
        }

        @Override
        public AssaultObserver get() {
            return observers.get(0);
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
            return new FakeHandle(observers.get(0));
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

    @AfterEach
    void resetOptionalHooks() {
        AssaultEngine.setOptionalHooks(Set.of());
    }

    private static AssaultEngine engineWithLayers(ChaosLayer... layers) {
        AssaultEngine engine = new AssaultEngine();
        engine.setActive(true);
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLayers(List.of(layers));
        config.setExceptionEnabled(true);
        config.setTargetLevel(100);
        engine.setMutableConfigForTests(config);
        return engine;
    }

    @Test
    void optionalHooksAreOnlyAvailableOnceDeclared() {
        AssaultEngine engine = new AssaultEngine();
        assertFalse(engine.isLayerAvailable(ChaosLayer.DATABASE));
        assertFalse(engine.isLayerAvailable(ChaosLayer.MESSAGING));
        assertTrue(engine.isLayerAvailable(ChaosLayer.SERVICE));

        AssaultEngine.setOptionalHooks(Set.of(ChaosLayer.DATABASE));

        assertTrue(engine.isLayerAvailable(ChaosLayer.DATABASE));
        assertFalse(engine.isLayerAvailable(ChaosLayer.MESSAGING));
        assertEquals(Set.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE, ChaosLayer.HTTP_OUT, ChaosLayer.HTTP_IN),
                engine.getAvailableLayers());
    }

    @Test
    void databaseWinsAnHttpRequestOnceItsHookIsInstalled() {
        AssaultEngine.setOptionalHooks(Set.of(ChaosLayer.DATABASE));
        AssaultEngine engine = engineWithLayers(ChaosLayer.DATABASE, ChaosLayer.SERVICE, ChaosLayer.HTTP_IN);

        assertEquals(ChaosLayer.DATABASE, engine.resolveAssaultLayer());
    }

    @Test
    void anHttpRequestNeverResolvesToMessaging() {
        AssaultEngine.setOptionalHooks(Set.of(ChaosLayer.MESSAGING));
        AssaultEngine engine = engineWithLayers(ChaosLayer.MESSAGING, ChaosLayer.HTTP_IN);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "MESSAGING is a consumer entry point: an HTTP request falls through to the next layer");
    }

    @Test
    void aConsumedMessageResolvesAmongDatabaseMessagingAndService() {
        AssaultEngine.setOptionalHooks(Set.of(ChaosLayer.DATABASE, ChaosLayer.MESSAGING));

        assertEquals(ChaosLayer.MESSAGING,
                engineWithLayers(ChaosLayer.MESSAGING, ChaosLayer.SERVICE).resolveAssaultLayer(AssaultEngine.MESSAGE_LAYERS));
        assertEquals(ChaosLayer.DATABASE,
                engineWithLayers(ChaosLayer.DATABASE, ChaosLayer.MESSAGING).resolveAssaultLayer(AssaultEngine.MESSAGE_LAYERS));
        assertNull(engineWithLayers(ChaosLayer.HTTP_IN).resolveAssaultLayer(AssaultEngine.MESSAGE_LAYERS),
                "HTTP_IN never applies to a consumed message");
    }

    @Test
    void optionalLayersOnlyInjectLatencyAndExceptions() {
        AssaultEngine.setOptionalHooks(Set.of(ChaosLayer.DATABASE));
        AssaultEngine engine = engineWithLayers(ChaosLayer.DATABASE, ChaosLayer.HTTP_IN);
        engine.getMutableConfig().setExceptionEnabled(false);
        engine.getMutableConfig().setLatencyEnabled(false);
        engine.getMutableConfig().setHttpStatusEnabled(true);

        assertEquals(ChaosLayer.HTTP_IN, engine.resolveAssaultLayer(),
                "an HTTP status assault cannot fire at the database layer");
    }
}
