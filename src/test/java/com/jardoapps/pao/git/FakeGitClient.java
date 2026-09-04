package com.jardoapps.pao.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Records what the runner asked git to do, without touching a repository. */
public class FakeGitClient implements GitClient {

    private final List<String> commits = new ArrayList<>();
    private final List<String> pushes = new ArrayList<>();
    private String configuredUser;
    private String currentBranch;

    @Override
    public void configureUser(String name, String email) {
        configuredUser = name + " <" + email + ">";
    }

    @Override
    public boolean hasUncommittedChanges() {
        return true;
    }

    @Override
    public void commitAll(String message) {
        commits.add(message);
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

    public String getConfiguredUser() {
        return configuredUser;
    }
}
