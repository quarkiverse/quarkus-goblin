package io.quarkiverse.goblin.it;

import java.time.temporal.ChronoUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

/**
 * The single business service of the integration-tests app. Every method logs its execution at DEBUG level so
 * {@code quarkus:dev} (with the {@code io.quarkiverse.goblin} log category at DEBUG, already set in
 * {@code application.properties}) shows the whole resilience chain in action: entry, optional Goblin service-layer
 * assault, then the MicroProfile Fault Tolerance behaviour and the outcome.
 * <p>
 * Methods without annotations have no resilience wrapper, so a Goblin-injected exception surfaces straight to the caller.
 * The others exercise {@code @Retry}, {@code @Fallback}, both combined, {@code @Timeout} and {@code @CircuitBreaker}, and a
 * nested bean-to-bean call, and database access through {@link SampleRepository}.
 */
@ApplicationScoped
public class SampleService {

    private static final Logger LOG = Logger.getLogger(SampleService.class);

    @Inject
    SampleDelegate delegate;

    @Inject
    SampleRepository repository;

    public String hello() {
        LOG.debugf("SampleService.hello() executing - no fault tolerance: an injected exception surfaces directly");
        return "hello from Goblin SampleService";
    }

    public String slow() throws InterruptedException {
        LOG.debugf("SampleService.slow() executing - no fault tolerance, built-in 5 ms delay");
        Thread.sleep(5);
        return "slow service reply";
    }

    @Retry(maxRetries = 2, delay = 0, jitter = 0)
    public String flaky() {
        LOG.debugf("SampleService.flaky() executing - @Retry(maxRetries=2): three executions for a Goblin exception");
        return "flaky service resolved";
    }

    @Fallback(fallbackMethod = "serviceFallback")
    public String fallbackable() {
        LOG.debugf("SampleService.fallbackable() executing - @Fallback: a Goblin exception is answered by the fallback");
        return "primary reply";
    }

    @Retry(maxRetries = 1, delay = 0, jitter = 0)
    @Fallback(fallbackMethod = "serviceFallback")
    public String retryThenFallback() {
        LOG.debugf("SampleService.retryThenFallback() executing - @Retry(1) + @Fallback: one retry, then the fallback");
        return "primary reply";
    }

    @Timeout(value = 400, unit = ChronoUnit.MILLIS)
    public String timed() {
        LOG.debugf("SampleService.timed() executing - @Timeout(400ms): an injected delay above the threshold is aborted");
        return "timed reply";
    }

    @CircuitBreaker(requestVolumeThreshold = 2, failureRatio = 1.0, delay = 60, delayUnit = ChronoUnit.SECONDS)
    public String guarded() {
        LOG.debugf("SampleService.guarded() executing - @CircuitBreaker(2 calls, 100%%): opens after two Goblin faults");
        return "guarded reply";
    }

    public String nested() {
        LOG.debugf("SampleService.nested() executing - calls SampleDelegate.inner() through its CDI proxy");
        return "nested: " + delegate.inner();
    }

    public String databasePing() {
        LOG.debugf("SampleService.databasePing() executing - no fault tolerance: a database fault surfaces directly");
        return "db: " + repository.ping();
    }

    @Retry(maxRetries = 2, delay = 0, jitter = 0)
    @Fallback(fallbackMethod = "databaseFallback")
    public String databasePingWithRetry() {
        LOG.debugf("SampleService.databasePingWithRetry() executing - @Retry(maxRetries=2) + @Fallback: each attempt "
                + "acquires a new connection, the fallback answers once they are exhausted");
        return "db: " + repository.ping();
    }

    String databaseFallback(Throwable cause) {
        // never touches the database: an assaulted fallback could not answer the original failure
        LOG.debugf("SampleService.databaseFallback() answering after %s", cause.getClass().getSimpleName());
        return "database fallback reply";
    }

    String serviceFallback(Throwable cause) {
        LOG.debugf("SampleService.serviceFallback() answering after %s", cause.getClass().getSimpleName());
        return "fallback reply";
    }
}