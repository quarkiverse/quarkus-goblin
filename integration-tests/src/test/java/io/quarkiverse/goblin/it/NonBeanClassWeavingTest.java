package io.quarkiverse.goblin.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * The service layer weaves its binding onto application bean methods only: the anonymous, local and inner classes
 * returned by {@link SampleProducers} can never be beans, and a binding on them would make ArC reject the deployment.
 * The application starting at all is the main assertion.
 */
@QuarkusTest
class NonBeanClassWeavingTest {

    @Inject
    @Named("anonymousGreeting")
    Supplier<String> anonymousGreeting;

    @Inject
    @Named("localGreeting")
    Supplier<String> localGreeting;

    @Inject
    @Named("innerGreeting")
    Supplier<String> innerGreeting;

    @Test
    void producedNonBeanClassesAreLeftAlone() {
        assertEquals("hello from an anonymous class", anonymousGreeting.get());
        assertEquals("hello from a local class", localGreeting.get());
        assertEquals("hello from an inner class", innerGreeting.get());
    }
}
