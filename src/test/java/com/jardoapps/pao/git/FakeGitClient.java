package com.jardoapps.pao.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Records what the runner asked git to do, without touching a repository. */
public class FakeGitClient implements GitClient {

    private final List<String> commits = new ArrayList<>();
    private final Map<String, List<Path>> committedFiles = new LinkedHashMap<>();
    private final List<String> pushes = new ArrayList<>();
    private String configuredUser;
    private String currentBranch;

    @Override
    public void configureUser(String name, String email) {
        configuredUser = name + " <" + email + ">";
    }

    @Override
    public void commit(String message, List<Path> files) {
        commits.add(message);
        committedFiles.put(message, List.copyOf(files));
    }

    @Override
    public void push(String branch) {
        pushes.add(branch);
    }

    @Override
    public Optional<String> currentBranch() {
        return Optional.ofNullable(currentBranch);
    }

    public FakeGitClient withCurrentBranch(String branch) {
        this.currentBranch = branch;
        return this;
    }

    public List<String> getCommits() {
        return List.copyOf(commits);
    }

    public List<String> getPushes() {
        return List.copyOf(pushes);
    }

    /** The files staged for a given commit message, in the order the runner passed them. */
    public List<Path> getCommittedFiles(String message) {
        return committedFiles.getOrDefault(message, List.of());
    }

    public String getConfiguredUser() {
        return configuredUser;
    }
}
