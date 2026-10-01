package io.quarkiverse.goblin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

/**
 * Saved chaos scenarios: named snapshots of the assault configuration, stored one per file under
 * {@code .goblin/scenarios/<name>.json} in the process working directory, next to {@code .goblin-state.json}.
 * <p>
 * A scenario uses the very format of the state file ({@link GoblinStatePersistence}), so loading one goes through the
 * same tolerant reading and the same validation as any configuration change: an invalid value falls back to a valid one
 * with a log, exactly as when it comes from the Dev UI. Like the state file, a scenario holds the assault configuration
 * only, never the active flag: loading a scenario does not switch chaos on.
 * <p>
 * A scenario name is 1 to 64 characters, letters, digits, spaces, {@code -} and {@code _}, starting with a letter or a
 * digit: it is used as the file name, so anything that could escape the directory or clash with the file system is
 * rejected. Names are case-insensitive on every operating system -- {@code Foo} and {@code foo} are the same scenario --
 * so the store behaves the same on a case-insensitive file system (macOS, Windows) and on a case-sensitive one (Linux).
 * Tests point the store at a temporary directory with the {@value #DIRECTORY_PROPERTY} system property.
 */
public final class GoblinScenarios {

    private static final Logger LOG = Logger.getLogger(GoblinScenarios.class);

    /** Default location of the scenarios, relative to the process working directory. */
    static final String DEFAULT_DIRECTORY = ".goblin/scenarios";

    /** System property overriding {@value #DEFAULT_DIRECTORY}, for tests. */
    public static final String DIRECTORY_PROPERTY = "goblin.scenarios.directory";

    private static final String EXTENSION = ".json";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}");

    private GoblinScenarios() {
    }

    /**
     * A saved scenario, as listed.
     *
     * @param name the scenario name
     * @param savedAt when the scenario was last saved, in epoch milliseconds
     * @param assaults what the scenario arms, e.g. {@code "latency enabled (100 - 500 ms)"}, or why it cannot be read
     * @param layers the chaos layers the scenario arms, empty when it cannot be read
     * @param level the target level of the scenario, {@code -1} when it cannot be read
     */
    public record Scenario(String name, long savedAt, String assaults, List<String> layers, int level) {
    }

    /**
     * A loaded scenario: its name as stored, and its configuration.
     *
     * @param name the stored name, which may differ in case from the requested one
     * @param config a detached configuration holding the scenario, to publish with
     *        {@link MutableAssaultConfig#replaceWith(MutableAssaultConfig)}
     */
    public record LoadedScenario(String name, MutableAssaultConfig config) {
    }

    /**
     * Why a scenario operation failed, so a caller -- the Dev UI, an agent -- can react to it without parsing a message.
     */
    public enum Failure {
        /** The name does not follow the naming rule. */
        INVALID_NAME,
        /** No scenario has that name. */
        NOT_FOUND,
        /** A scenario of that name, whatever its case, exists and was not to be overwritten. */
        EXISTS,
        /** The file could not be read, written or deleted. */
        STORAGE
    }

    /**
     * A scenario operation that cannot be performed. The message is meant for the Dev UI and for an agent, the
     * {@link #failure()} for code.
     */
    public static final class ScenarioException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Failure failure;
        private final String scenario;

        ScenarioException(Failure failure, String message) {
            this(failure, message, null, null);
        }

        ScenarioException(Failure failure, String message, Throwable cause) {
            this(failure, message, null, cause);
        }

        ScenarioException(Failure failure, String message, String scenario, Throwable cause) {
            super(message, cause);
            this.failure = failure;
            this.scenario = scenario;
        }

        /**
         * @return why the operation failed
         */
        public Failure failure() {
            return failure;
        }

        /**
         * @return the stored name of the scenario the failure is about, e.g. the existing one of an {@link Failure#EXISTS}
         *         conflict, or {@code null}
         */
        public String scenario() {
            return scenario;
        }
    }

    /**
     * @return the saved scenarios, sorted by name (case-insensitive); a file that cannot be read is listed with the
     *         reason in {@link Scenario#assaults()} so it can be inspected or deleted
     */
    public static List<Scenario> list() {
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Scenario> scenarios = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - EXTENSION.length());
                if (NAME.matcher(name).matches()) {
                    scenarios.add(describe(name, file));
                } else {
                    LOG.warnf("Goblin: %s is not a valid scenario name and is ignored; rename or delete it by hand", file);
                }
            }
        } catch (IOException e) {
            throw new ScenarioException(Failure.STORAGE, "Cannot list the scenarios in " + directory + ": " + reason(e), e);
        }
        scenarios.sort(Comparator.comparing(Scenario::name, String.CASE_INSENSITIVE_ORDER));
        return scenarios;
    }

    /**
     * Saves the configuration under the given name. The file is written to a temporary sibling then moved, so a crash
     * never leaves a truncated scenario behind.
     *
     * @param name the scenario name
     * @param config the configuration to save
     * @param overwrite whether an existing scenario of the same name may be replaced
     * @return the saved scenario
     * @throws ScenarioException when the name is invalid, the scenario exists and {@code overwrite} is false, or the file
     *         cannot be written
     */
    public static synchronized Scenario save(String name, MutableAssaultConfig config, boolean overwrite) {
        String valid = validName(name);
        Path target = file(valid);
        Path existing = find(valid);
        if (existing != null && !overwrite) {
            throw new ScenarioException(Failure.EXISTS, "Scenario '" + stem(existing) + "' already exists: save it "
                    + "again with overwrite=true to replace it", stem(existing), null);
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(tmp, GoblinStatePersistence.toJson(config.snapshot()).encodePrettily());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort: a leftover temporary file is overwritten by the next save
            }
            throw new ScenarioException(Failure.STORAGE, "Cannot save scenario '" + valid + "': " + reason(e), e);
        }
        dropOldSpelling(existing, target);
        LOG.infof("Goblin scenario '%s' saved to %s", valid, target);
        return describe(valid, target);
    }

    /**
     * Reads a scenario. The configuration goes through the same validation as any change: an invalid value is replaced
     * by a valid one with a log.
     *
     * @param name the scenario name, whatever its case
     * @return the scenario, with its name as stored
     * @throws ScenarioException when the name is invalid, the scenario does not exist or cannot be read
     */
    public static LoadedScenario load(String name) {
        String valid = validName(name);
        Path file = find(valid);
        if (file == null) {
            throw new ScenarioException(Failure.NOT_FOUND, "Scenario '" + valid + "' does not exist; listScenarios "
                    + "returns the saved ones");
        }
        try {
            MutableAssaultConfig config = GoblinStatePersistence.fromJson(Files.readString(file));
            config.validateAndFix();
            return new LoadedScenario(stem(file), config);
        } catch (NoSuchFileException e) {
            throw new ScenarioException(Failure.NOT_FOUND, "Scenario '" + valid + "' does not exist; listScenarios "
                    + "returns the saved ones", e);
        } catch (IOException | RuntimeException e) {
            throw new ScenarioException(Failure.STORAGE, "Scenario '" + valid + "' cannot be read: " + reason(e), e);
        }
    }

    /**
     * Deletes a scenario.
     *
     * @param name the scenario name
     * @return {@code true} when the scenario existed and was deleted, {@code false} when there was none
     * @throws ScenarioException when the name is invalid or the file cannot be deleted
     */
    public static synchronized boolean delete(String name) {
        String valid = validName(name);
        Path file = find(valid);
        try {
            boolean deleted = file != null && Files.deleteIfExists(file);
            if (deleted) {
                LOG.infof("Goblin scenario '%s' deleted", stem(file));
            }
            return deleted;
        } catch (IOException e) {
            throw new ScenarioException(Failure.STORAGE, "Cannot delete scenario '" + valid + "': " + reason(e), e);
        }
    }

    /**
     * Validates a scenario name, see the class documentation.
     *
     * @param name the name to validate, surrounding whitespace ignored
     * @return the trimmed name
     * @throws ScenarioException when the name is not valid
     */
    public static String validName(String name) {
        String trimmed = name != null ? name.trim() : "";
        if (!NAME.matcher(trimmed).matches()) {
            throw new ScenarioException(Failure.INVALID_NAME, "Invalid scenario name '" + (name != null ? name : "")
                    + "': use 1 to 64 letters, digits, spaces, '-' or '_', starting with a letter or a digit");
        }
        return trimmed;
    }

    private static Scenario describe(String name, Path file) {
        long savedAt;
        try {
            savedAt = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            savedAt = 0;
        }
        try {
            MutableAssaultConfig config = GoblinStatePersistence.fromJson(Files.readString(file));
            config.validateAndFix();
            return new Scenario(name, savedAt, config.describeAssaults(),
                    config.getLayers().stream().map(Enum::name).sorted().toList(), config.getTargetLevel());
        } catch (IOException | RuntimeException e) {
            return new Scenario(name, savedAt, "unreadable: " + reason(e), List.of(), -1);
        }
    }

    /**
     * After replacing 'foo' by 'Foo' on a case-sensitive file system, drops the old spelling so a single scenario remains.
     * On a case-insensitive file system both names are the very file just written, which is kept. The scenario is
     * already saved at this point: a failure here -- the file removed meanwhile from a terminal, another dev mode on the
     * same project -- is logged and never turns the successful save into an error.
     *
     * @param existing the file found before the save, or {@code null}
     * @param target the file just written
     */
    private static void dropOldSpelling(Path existing, Path target) {
        if (existing == null) {
            return;
        }
        try {
            if (Files.exists(existing) && !Files.isSameFile(existing, target)) {
                Files.deleteIfExists(existing);
            }
        } catch (IOException e) {
            LOG.warnf("Goblin: scenario saved to %s, but the previous file %s could not be removed: %s", target, existing,
                    reason(e));
        }
    }

    /**
     * Finds the file of a scenario whatever the case of its name, so the store gives the same answer on every file
     * system.
     *
     * @param validName a valid scenario name
     * @return the existing file, or {@code null} when no scenario has that name
     */
    private static Path find(String validName) {
        Path exact = file(validName);
        if (Files.exists(exact)) {
            // on a case-insensitive file system 'STORM.json' exists when the file is 'Storm.json': report the real name
            try {
                return exact.toRealPath();
            } catch (IOException e) {
                return exact;
            }
        }
        Path directory = directory();
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path candidate : files) {
                if (stem(candidate).equalsIgnoreCase(validName)) {
                    return candidate;
                }
            }
        } catch (IOException e) {
            throw new ScenarioException(Failure.STORAGE, "Cannot read the scenarios in " + directory + ": " + reason(e), e);
        }
        return null;
    }

    private static String stem(Path file) {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.length() - EXTENSION.length());
    }

    private static String reason(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static Path file(String validName) {
        return directory().resolve(validName + EXTENSION);
    }

    private static Path directory() {
        String override = System.getProperty(DIRECTORY_PROPERTY);
        return Path.of(override != null && !override.isBlank() ? override : DEFAULT_DIRECTORY).toAbsolutePath();
    }
}
