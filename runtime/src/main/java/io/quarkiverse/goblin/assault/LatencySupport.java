package io.quarkiverse.goblin.assault;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.quarkiverse.goblin.MutableAssaultConfig;
import io.quarkus.runtime.BlockingOperationControl;

/**
 * Single implementation of the latency draw and of the blocking sleep shared by every latency hook (inbound filter,
 * service interceptor, REST Client filter and Vert.x WebClient), so they all agree on the bounds, the interruption
 * policy and the event-loop guard.
 */
public final class LatencySupport {

    private static final Logger LOG = Logger.getLogger(LatencySupport.class);

    private static final AtomicBoolean EVENT_LOOP_WARNED = new AtomicBoolean();

    private LatencySupport() {
    }

    /**
     * Draws a delay uniformly within the configured latency range, bounds included.
     * <p>
     * Reads the range once through {@link MutableAssaultConfig#getLatencyRange()} so a concurrent update can never be
     * observed half-applied, clamps negative values to {@code 0} and never overflows on {@code Long.MAX_VALUE}.
     *
     * @param config the active configuration
     * @return the delay to apply, in milliseconds, never negative
     */
    public static long drawDelay(MutableAssaultConfig config) {
        long[] range = config.getLatencyRange();
        return drawDelay(range[0], range[1]);
    }

    /**
     * Draws a delay uniformly within {@code [min, max]}, tolerating reversed, negative or unbounded values.
     *
     * @param min the lower bound in milliseconds
     * @param max the upper bound in milliseconds
     * @return the delay to apply, in milliseconds, never negative
     */
    static long drawDelay(long min, long max) {
        long low = Math.max(0, Math.min(min, max));
        long high = Math.max(0, Math.max(min, max));
        if (low == high) {
            return low;
        }
        return ThreadLocalRandom.current().nextLong(low, high == Long.MAX_VALUE ? high : high + 1);
    }

    /**
     * Returns whether the current thread may block. Returns {@code false} on a Vert.x I/O (event-loop) thread, where a
     * {@link Thread#sleep(long)} would stall every request multiplexed on that loop.
     *
     * @return {@code true} when a blocking sleep is safe on the current thread
     */
    public static boolean isBlockingAllowed() {
        try {
            return BlockingOperationControl.isBlockingAllowed();
        } catch (NullPointerException e) {
            // no Quarkus runtime installed the I/O thread detectors (plain unit tests): any thread may block
            return true;
        }
    }

    /**
     * Blocks the current thread for the given delay, unless it is an event-loop thread.
     * <p>
     * On an event-loop thread the latency is skipped (a WARN is logged once, then DEBUG) instead of blocking the loop;
     * the caller must not record the assault in that case. An interruption restores the interrupt flag and is
     * propagated as an {@link InterruptedException}, so the caller can abort the assaulted call.
     *
     * @param delay the delay to apply, in milliseconds
     * @param target the assaulted target, used for logging
     * @return {@code true} when the delay was applied, {@code false} when it was skipped on an event-loop thread
     * @throws InterruptedException when the sleep is interrupted
     */
    public static boolean sleep(long delay, String target) throws InterruptedException {
        if (!isBlockingAllowed()) {
            if (EVENT_LOOP_WARNED.compareAndSet(false, true)) {
                LOG.warnf("Goblin: skipping %d ms latency on %s: the call runs on a Vert.x event-loop thread, which must "
                        + "never block (further occurrences are logged at DEBUG)", delay, target);
            } else {
                LOG.debugf("Goblin: skipping %d ms latency on %s (event-loop thread)", delay, target);
            }
            return false;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
