package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;

/**
 * A production build of an application carrying Goblin fails, with a message saying how to keep Goblin out of it.
 */
class ProductionBuildRefusedTest {

    @RegisterExtension
    static final QuarkusProdModeTest config = new QuarkusProdModeTest()
            .withApplicationRoot(jar -> jar.addClass(ProductionBuildRefusedTest.class))
            .setApplicationName("goblin-production-build")
            .setApplicationVersion("1.0")
            .assertBuildException(e -> {
                Throwable cause = e;
                while (cause != null && !cause.getMessage().contains("Goblin must never reach production")) {
                    cause = cause.getCause();
                }
                assertTrue(cause != null, "the build must fail on Goblin, got: " + e);
                assertTrue(cause.getMessage().contains("<scope>provided</scope>"),
                        "the message must say how to keep Goblin out of production: " + cause.getMessage());
            });

    @Test
    void productionBuildIsRefused() {
        fail("the production build must have failed before any test runs");
    }
}
