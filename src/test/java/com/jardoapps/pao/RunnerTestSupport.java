package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.logging.SystemStreamLog;

import com.jardoapps.pao.git.FakeGitClient;
import com.jardoapps.pao.pom.PomReader;

/** Sets up a throwaway project directory and runs the plugin logic against it. */
public class RunnerTestSupport {

    protected final Log log = new SystemStreamLog();

    protected FakeGitClient git = new FakeGitClient();

    protected Map<String, String> environment = Map.of();

    /** Copies a fixture from {@code src/test/resources/poms} to {@code <dir>/<name>}. */
    protected Path writeFixture(Path directory, String fixture, String name) {
        try (InputStream in = getClass().getResourceAsStream("/poms/" + fixture)) {
            if (in == null) {
                throw new IllegalArgumentException("No such fixture: " + fixture);
            }
            Files.createDirectories(directory);
            Path target = directory.resolve(name);
            Files.write(target, in.readAllBytes());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected Path writePom(Path directory, String fixture) {
        return writeFixture(directory, fixture, "pom.xml");
    }

    protected Path writeFile(Path directory, String name, String content) {
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(name);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected PreventOverwritesRunner.RunResult run(Path baseDirectory, Consumer<RunnerSettings> configure) {
        RunnerSettings settings = new RunnerSettings().setPushChanges(false);
        configure.accept(settings);
        PreventOverwritesRunner runner =
                new PreventOverwritesRunner(settings, git, baseDirectory, environment::get, log);
        return runner.run(PomReader.readReactor(baseDirectory.resolve("pom.xml")));
    }

    protected String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/poms/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No such fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Asserts the file matches a fixture byte for byte, so formatting changes are caught. */
    protected void assertMatchesFixture(Path file, String fixtureName) {
        assertEquals(fixture(fixtureName), read(file), file + " does not match " + fixtureName);
    }
}
