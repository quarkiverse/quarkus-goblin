package io.quarkiverse.goblin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.goblin.GoblinScenarios.Scenario;
import io.quarkiverse.goblin.GoblinScenarios.ScenarioException;

class GoblinScenariosTest {

    private Path directory;

    @BeforeEach
    void useTemporaryDirectory() throws IOException {
        directory = Files.createTempDirectory("goblin-scenarios").resolve("scenarios");
        System.setProperty(GoblinScenarios.DIRECTORY_PROPERTY, directory.toString());
    }

    @AfterEach
    void cleanUp() throws IOException {
        System.clearProperty(GoblinScenarios.DIRECTORY_PROPERTY);
        Path root = directory.getParent();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static MutableAssaultConfig attack() {
        MutableAssaultConfig config = new MutableAssaultConfig();
        config.setLatencyEnabled(false);
        config.setExceptionEnabled(true);
        config.setExceptionType("java.lang.IllegalStateException");
        config.setExceptionMessage("the cave is on fire");
        config.setHttpStatusCode(502);
        config.setResponseBodyMode(ResponseBodyMode.INFLATE);
        config.setResponseBodyPercentage(150);
        config.setLatencyMinMs(300);
        config.setLatencyMaxMs(500);
        config.setTargetLevel(40);
        config.setLayers(Set.of(ChaosLayer.DATABASE, ChaosLayer.SERVICE));
        config.setResponseHeader("X-Goblin", ResponseHeaderAction.SET, "on fire");
        return config;
    }

    @Test
    void aSavedScenarioLoadsBackExactly() {
        MutableAssaultConfig saved = attack();

        GoblinScenarios.save("cave on fire", saved, false);
        MutableAssaultConfig loaded = GoblinScenarios.load("cave on fire").config();

        assertEquals(GoblinStatePersistence.toJson(saved), GoblinStatePersistence.toJson(loaded),
                "every toggle and parameter survives the round trip");
        assertTrue(Files.exists(directory.resolve("cave on fire.json")));
    }

    @Test
    void scenariosAreListedByNameWithWhatTheyArm() {
        GoblinScenarios.save("zeta", new MutableAssaultConfig(), false);
        GoblinScenarios.save("Alpha", attack(), false);

        List<Scenario> scenarios = GoblinScenarios.list();

        assertEquals(List.of("Alpha", "zeta"), scenarios.stream().map(Scenario::name).toList());
        Scenario alpha = scenarios.getFirst();
        assertTrue(alpha.assaults().startsWith("exception enabled (java.lang.IllegalStateException"), alpha.assaults());
        assertEquals(List.of("DATABASE", "SERVICE"), alpha.layers());
        assertEquals(40, alpha.level());
        assertTrue(alpha.savedAt() > 0);
    }

    @Test
    void anEmptyStoreListsNothing() {
        assertEquals(List.of(), GoblinScenarios.list());
    }

    @Test
    void anExistingScenarioIsOnlyReplacedOnRequest() {
        GoblinScenarios.save("storm", attack(), false);

        ScenarioException refused = assertThrows(ScenarioException.class,
                () -> GoblinScenarios.save("storm", new MutableAssaultConfig(), false));
        assertTrue(refused.getMessage().contains("overwrite=true"), refused.getMessage());
        assertEquals(40, GoblinScenarios.load("storm").config().getTargetLevel(), "the refused save changed nothing");

        GoblinScenarios.save("storm", new MutableAssaultConfig(), true);
        assertEquals(100, GoblinScenarios.load("storm").config().getTargetLevel());
    }

    @Test
    void invalidNamesAreRejected() {
        for (String name : List.of("", "   ", "../escape", "a/b", "a\\b", ".hidden", "-dash", "x".repeat(65),
                "name.json", "tab\tname")) {
            assertThrows(ScenarioException.class, () -> GoblinScenarios.save(name, new MutableAssaultConfig(), false),
                    "'" + name + "' must be rejected");
        }
        assertThrows(ScenarioException.class, () -> GoblinScenarios.save(null, new MutableAssaultConfig(), false));
        assertFalse(Files.exists(directory), "no rejected name ever reaches the file system");
    }

    @Test
    void surroundingWhitespaceIsIgnored() {
        GoblinScenarios.save("  slow kitchen  ", attack(), false);

        assertEquals(40, GoblinScenarios.load("slow kitchen").config().getTargetLevel());
    }

    @Test
    void deletingAScenarioRemovesItsFile() {
        GoblinScenarios.save("short-lived", attack(), false);

        assertTrue(GoblinScenarios.delete("short-lived"));
        assertFalse(Files.exists(directory.resolve("short-lived.json")));
        assertFalse(GoblinScenarios.delete("short-lived"), "deleting a missing scenario reports false");
    }

    @Test
    void loadingAMissingScenarioIsAClearError() {
        ScenarioException missing = assertThrows(ScenarioException.class, () -> GoblinScenarios.load("nowhere"));
        assertTrue(missing.getMessage().contains("does not exist"), missing.getMessage());
    }

    @Test
    void anInvalidValueFallsBackLikeAnyConfigurationChange() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("tampered.json"), """
                {"httpStatusCode": 999, "targetLevel": 250, "latencyMinMs": 900, "latencyMaxMs": 100}
                """);

        MutableAssaultConfig loaded = GoblinScenarios.load("tampered").config();

        assertEquals(503, loaded.getHttpStatusCode(), "an out-of-range status falls back to 503");
        assertEquals(100, loaded.getTargetLevel(), "the level is clamped to 0-100");
        assertEquals(100, loaded.getLatencyMinMs(), "a reversed latency range is swapped");
        assertEquals(900, loaded.getLatencyMaxMs());
    }

    @Test
    void anUnreadableScenarioIsListedWithTheReasonAndFailsToLoad() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("broken.json"), "not json at all");

        Scenario broken = GoblinScenarios.list().getFirst();
        assertEquals("broken", broken.name());
        assertTrue(broken.assaults().startsWith("unreadable"), broken.assaults());

        assertThrows(ScenarioException.class, () -> GoblinScenarios.load("broken"));
    }

    @Test
    void namesAreCaseInsensitiveOnEveryFileSystem() throws IOException {
        GoblinScenarios.save("Storm", attack(), false);

        ScenarioException conflict = assertThrows(ScenarioException.class,
                () -> GoblinScenarios.save("storm", new MutableAssaultConfig(), false));
        assertEquals(GoblinScenarios.Failure.EXISTS, conflict.failure(), "another case is the same scenario");

        GoblinScenarios.LoadedScenario loaded = GoblinScenarios.load("STORM");
        assertEquals("Storm", loaded.name(), "the stored name is reported");
        assertEquals(40, loaded.config().getTargetLevel());

        GoblinScenarios.save("storm", new MutableAssaultConfig(), true);
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(1, files.filter(file -> file.toString().endsWith(".json")).count(),
                    "replacing under another case leaves a single scenario file");
        }
        assertEquals(100, GoblinScenarios.load("Storm").config().getTargetLevel());

        assertTrue(GoblinScenarios.delete("STORM"));
        assertEquals(List.of(), GoblinScenarios.list());
    }

    @Test
    void everyFailureCarriesItsReason() throws IOException {
        assertEquals(GoblinScenarios.Failure.INVALID_NAME,
                assertThrows(ScenarioException.class, () -> GoblinScenarios.load("../x")).failure());
        assertEquals(GoblinScenarios.Failure.NOT_FOUND,
                assertThrows(ScenarioException.class, () -> GoblinScenarios.load("nowhere")).failure());

        GoblinScenarios.save("storm", attack(), false);
        ScenarioException exists = assertThrows(ScenarioException.class,
                () -> GoblinScenarios.save("STORM", attack(), false));
        assertEquals(GoblinScenarios.Failure.EXISTS, exists.failure());
        assertEquals("storm", exists.scenario(), "the conflict names the stored scenario");

        // a regular file where the directory should be: portable on every file system
        Path notADirectory = directory.getParent().resolve("not-a-directory");
        Files.writeString(notADirectory, "x");
        System.setProperty(GoblinScenarios.DIRECTORY_PROPERTY, notADirectory.resolve("scenarios").toString());
        assertEquals(GoblinScenarios.Failure.STORAGE,
                assertThrows(ScenarioException.class, () -> GoblinScenarios.save("storm", attack(), false)).failure());
    }

    @Test
    void aFileWhoseNameIsNotAScenarioNameIsIgnored() throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("weird$.json"), "{}");
        GoblinScenarios.save("fine", attack(), false);

        assertEquals(List.of("fine"), GoblinScenarios.list().stream().map(Scenario::name).toList(),
                "an invalid file name is skipped, with a WARN telling to rename or delete it by hand");
    }
}
