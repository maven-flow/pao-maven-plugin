package com.jardoapps.pao;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Everything the runner needs to know, independent of how it was configured. */
public class RunnerSettings {

    private String branchName;
    private boolean enforceBranchVersion = true;
    private boolean pushChanges = true;
    private String commitMessageSuffix = "";
    private String gitUserName = "ci-bot";
    private String gitUserEmail = "ci-bot@example.com";
    private List<String> coreBranches = List.of("main", "master", "develop", "release*");
    private Path configFile;
    private Path outputFile;

    public String getBranchName() {
        return branchName;
    }

    public RunnerSettings setBranchName(String branchName) {
        this.branchName = branchName;
        return this;
    }

    public boolean isEnforceBranchVersion() {
        return enforceBranchVersion;
    }

    public RunnerSettings setEnforceBranchVersion(boolean enforceBranchVersion) {
        this.enforceBranchVersion = enforceBranchVersion;
        return this;
    }

    public boolean isPushChanges() {
        return pushChanges;
    }

    public RunnerSettings setPushChanges(boolean pushChanges) {
        this.pushChanges = pushChanges;
        return this;
    }

    public String getCommitMessageSuffix() {
        return commitMessageSuffix == null ? "" : commitMessageSuffix;
    }

    public RunnerSettings setCommitMessageSuffix(String commitMessageSuffix) {
        this.commitMessageSuffix = commitMessageSuffix;
        return this;
    }

    public String getGitUserName() {
        return gitUserName;
    }

    public RunnerSettings setGitUserName(String gitUserName) {
        this.gitUserName = gitUserName;
        return this;
    }

    public String getGitUserEmail() {
        return gitUserEmail;
    }

    public RunnerSettings setGitUserEmail(String gitUserEmail) {
        this.gitUserEmail = gitUserEmail;
        return this;
    }

    public List<String> getCoreBranches() {
        return coreBranches;
    }

    public RunnerSettings setCoreBranches(List<String> coreBranches) {
        this.coreBranches = coreBranches;
        return this;
    }

    /** Accepts the space- or comma-separated form used by the CI inputs. */
    public RunnerSettings setCoreBranches(String coreBranches) {
        this.coreBranches = coreBranches == null || coreBranches.isBlank()
                ? List.of()
                : Arrays.stream(coreBranches.split("[,\\s]+")).filter(s -> !s.isBlank()).toList();
        return this;
    }

    public Path getConfigFile() {
        return configFile;
    }

    public RunnerSettings setConfigFile(Path configFile) {
        this.configFile = configFile;
        return this;
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public RunnerSettings setOutputFile(Path outputFile) {
        this.outputFile = outputFile;
        return this;
    }
}
