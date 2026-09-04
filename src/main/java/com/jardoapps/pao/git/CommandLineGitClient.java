package com.jardoapps.pao.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.maven.plugin.logging.Log;

import com.jardoapps.pao.PaoException;

/**
 * Runs the {@code git} executable in the repository working directory.
 *
 * <p>Shelling out rather than embedding JGit is deliberate: CI runners already have
 * credentials configured for the {@code git} binary (token helpers, SSH agents,
 * {@code insteadOf} rewrites), and reproducing that setup in-process is a common
 * source of authentication failures.
 */
public class CommandLineGitClient implements GitClient {

    private static final long TIMEOUT_SECONDS = 120;

    private final Path workingDirectory;
    private final Log log;

    public CommandLineGitClient(Path workingDirectory, Log log) {
        this.workingDirectory = workingDirectory;
        this.log = log;
    }

    @Override
    public void configureUser(String name, String email) {
        log.info("Setting up git configuration...");
        run(true, "config", "--local", "user.name", name);
        run(true, "config", "--local", "user.email", email);
    }

    @Override
    public boolean hasUncommittedChanges() {
        return !run(true, "status", "--porcelain").output().isBlank();
    }

    @Override
    public void commitAll(String message) {
        if (!hasUncommittedChanges()) {
            log.debug("Nothing to commit.");
            return;
        }
        run(true, "commit", "-a", "-m", message);
    }

    @Override
    public void push(String branch) {
        // HEAD:<branch> works in the detached HEAD state that CI checkouts often use.
        run(true, "push", "origin", "HEAD:" + branch);
    }

    @Override
    public Optional<String> currentBranch() {
        Result result = run(false, "rev-parse", "--abbrev-ref", "HEAD");
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        String branch = result.output().trim();
        return branch.isEmpty() || "HEAD".equals(branch) ? Optional.empty() : Optional.of(branch);
    }

    private record Result(int exitCode, String output) {
    }

    private Result run(boolean failOnError, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(arguments));

        log.debug("Running: " + String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new PaoException("Cannot run: " + String.join(" ", command), e);
        }

        String output;
        int exitCode;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new PaoException("Timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", command));
            }
            exitCode = process.exitValue();
        } catch (IOException e) {
            throw new PaoException("Cannot read output of: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaoException("Interrupted while running: " + String.join(" ", command), e);
        }

        if (exitCode != 0 && failOnError) {
            throw new PaoException("Command failed (exit " + exitCode + "): " + String.join(" ", command)
                    + System.lineSeparator() + output.strip());
        }
        return new Result(exitCode, output);
    }
}
