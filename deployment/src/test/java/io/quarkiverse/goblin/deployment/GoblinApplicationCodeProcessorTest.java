package io.quarkiverse.goblin.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.goblin.AssaultEngine;
import io.quarkiverse.goblin.AssaultObserver;

class GoblinApplicationCodeProcessorTest {

    /** Main code using the Goblin API: what the build refuses. */
    static class ObserverInMainCode implements AssaultObserver {
    }

    /** Main code without any Goblin reference. */
    static class PlainMainCode {
        String hello() {
            return "hello";
        }
    }

    /** Test code using the Goblin API: never packaged, so allowed. */
    static class EngineInTestCode {
        AssaultEngine engine;
    }

    @TempDir
    Path mainOutput;

    @Test
    void onlyMainClassesUsingGoblinAreReported() throws IOException {
        Index application = Index.of(ObserverInMainCode.class, PlainMainCode.class, EngineInTestCode.class);
        Indexer goblin = new Indexer();
        index(goblin, AssaultObserver.class);
        index(goblin, AssaultEngine.class);
        compiledToMain(ObserverInMainCode.class);
        compiledToMain(PlainMainCode.class);

        Set<String> offenders = GoblinApplicationCodeProcessor.goblinUsers(application,
                org.jboss.jandex.CompositeIndex.create(application, goblin.complete()), List.of(mainOutput));

        assertEquals(Set.of(ObserverInMainCode.class.getName()), offenders);
    }

    private void compiledToMain(Class<?> type) throws IOException {
        Path file = mainOutput.resolve(type.getName().replace('.', '/') + ".class");
        Files.createDirectories(file.getParent());
        Files.createFile(file);
    }

    private static void index(Indexer indexer, Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            indexer.index(in);
        }
    }
}
