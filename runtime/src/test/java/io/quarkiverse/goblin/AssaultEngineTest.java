package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

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
}
