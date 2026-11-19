package io.quarkiverse.goblin.it.aesh;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.quarkus.test.aesh.AeshLauncher;
import io.quarkus.test.aesh.AeshLauncherImpl;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;

/**
 * Regression test for #95: with Goblin on the classpath, the service-assault binding turned every Aesh command into an
 * ArC subclass without {@code @CommandDefinition}, which Aesh refused, so the console never started and every command
 * timed out. The commands must keep being dispatched.
 */
@QuarkusMainTest
class AeshCliTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void dispatchesACommandCallingAServiceBean(QuarkusMainLauncher launcher) {
        AeshLauncher aesh = new AeshLauncherImpl(launcher);
        aesh.launch();
        try {
            String output = aesh.executeCommand("hello goblin", TIMEOUT);
            assertTrue(output.contains("hello goblin"), output);
        } finally {
            aesh.exit();
        }
    }

    @Test
    void dispatchesACommandTouchingTheDatabase(QuarkusMainLauncher launcher) {
        AeshLauncher aesh = new AeshLauncherImpl(launcher);
        aesh.launch();
        try {
            String output = aesh.executeCommand("query", TIMEOUT);
            assertTrue(output.contains("answer 42"), output);
        } finally {
            aesh.exit();
        }
    }
}
