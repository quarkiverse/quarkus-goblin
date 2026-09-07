package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
}