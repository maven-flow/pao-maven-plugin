package com.jardoapps.pao.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    public void commit(String message, List<Path> files) {
        if (files.isEmpty()) {
            log.debug("Nothing to commit.");
            return;
        }
        List<String> paths = files.stream().map(Path::toString).toList();

        // Scoping both the staging and the commit to known paths keeps anything else
        // in the working tree out of it, whoever or whatever put it there.
        run(true, concat(List.of("add", "--"), paths));
        run(true, concat(List.of("commit", "-m", message, "--"), paths));
    }

    private static String[] concat(List<String> head, List<String> tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(tail);
        return all.toArray(new String[0]);
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

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        // Without this, an https remote with no usable credential helper prompts for a
        // username on stdin and the build blocks until the CI job itself is killed.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PaoException("Cannot run: " + String.join(" ", command), e);
        }

        String output;
        int exitCode;
        try {
            // Nothing is ever written to git, and a closed stdin makes anything that
            // would have prompted fail immediately instead of waiting for input.
            process.getOutputStream().close();

            // Draining stdout on another thread is what makes the timeout meaningful:
            // read on this thread and it blocks until git exits, so waitFor would only
            // ever see an already-terminated process. Reading after waitFor instead
            // would deadlock on output larger than the pipe buffer.
            CompletableFuture<byte[]> reader = CompletableFuture.supplyAsync(() -> readAll(process));

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.cancel(true);
                throw new PaoException("Timed out after " + TIMEOUT_SECONDS + "s: " + String.join(" ", command));
            }
            exitCode = process.exitValue();
            output = new String(reader.join(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PaoException("Cannot read output of: " + String.join(" ", command), e);
        } catch (CompletionException e) {
            throw new PaoException("Cannot read output of: " + String.join(" ", command), e.getCause());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new PaoException("Interrupted while running: " + String.join(" ", command), e);
        }

        if (exitCode != 0 && failOnError) {
            throw new PaoException("Command failed (exit " + exitCode + "): " + String.join(" ", command)
                    + System.lineSeparator() + output.strip());
        }
        return new Result(exitCode, output);
    }

    private static byte[] readAll(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException e) {
            // Expected when the process is destroyed on timeout; the caller has already
            // decided the run failed, so the partial output is of no use either way.
            throw new UncheckedIOException(e);
        }
    }
}
