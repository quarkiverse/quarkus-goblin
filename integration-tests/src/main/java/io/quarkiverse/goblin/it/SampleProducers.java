package io.quarkiverse.goblin.it;

import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

/**
 * Producers returning instances of classes that can never be CDI beans -- an anonymous class, a local class and a
 * non-static inner class -- like the anonymous {@code MeterFilter} an application typically produces. The service
 * layer must not weave its binding onto them: ArC would reject it and the application would not start.
 */
@ApplicationScoped
public class SampleProducers {

    @Produces
    @Named("anonymousGreeting")
    Supplier<String> anonymousGreeting() {
        return new Supplier<>() {
            @Override
            public String get() {
                return "hello from an anonymous class";
            }
        };
    }

    @Produces
    @Named("localGreeting")
    Supplier<String> localGreeting() {
        class LocalGreeting implements Supplier<String> {
            @Override
            public String get() {
                return "hello from a local class";
            }
        }
        return new LocalGreeting();
    }

    @Produces
    @Named("innerGreeting")
    Supplier<String> innerGreeting() {
        return new InnerGreeting();
    }

    /**
     * Non-static inner class: bound to its enclosing instance, never a bean.
     */
    class InnerGreeting implements Supplier<String> {
        @Override
        public String get() {
            return "hello from an inner class";
        }
    }
}
