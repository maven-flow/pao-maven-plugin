package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jardoapps.pao.PreventOverwritesRunner.RunResult;

/** Ports the behaviour covered by the shell implementation's test suite. */
class PreventOverwritesRunnerTest extends RunnerTestSupport {

    @TempDir
    Path project;

    @Test
    @DisplayName("feature branch adds the branch suffix and leaves dependencies alone")
    void enforcesBranchVersion() {
        Path pom = writePom(project, "sample-pom.xml");

        RunResult result = run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(pom, "expected-enforce-branch-version.xml");
        assertTrue(result.changesMade());
        assertEquals(List.of("Switched to branch-specific version."), git.getCommits());
    }

    @Test
    @DisplayName("a project that already carries a branch version is left untouched")
    void skipsWhenAlreadyBranchVersioned() {
        Path pom = writePom(project, "sample-pom-with-branch-version.xml");

        RunResult result = run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(pom, "sample-pom-with-branch-version.xml");
        assertFalse(result.changesMade());
        assertTrue(git.getCommits().isEmpty());
    }

    @Test
    @DisplayName("enforceBranchVersion=false leaves the project version alone")
    void skipsWhenEnforcementDisabled() {
        Path pom = writePom(project, "sample-pom.xml");

        RunResult result = run(project, settings -> settings
                .setBranchName("feature/my-feature")
                .setEnforceBranchVersion(false));

        assertMatchesFixture(pom, "sample-pom.xml");
        assertFalse(result.changesMade());
    }

    @Test
    @DisplayName("core branch strips the branch suffix from the project version")
    void removesBranchVersionOnCoreBranch() {
        Path pom = writePom(project, "sample-pom-with-branch-version.xml");

        RunResult result = run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(pom, "expected-remove-branch-version.xml");
        assertTrue(result.changesMade());
        assertTrue(result.coreBranch());
        assertEquals(List.of("Switched to non branch-specific version."), git.getCommits());
    }

    @Test
    @DisplayName("core branch strips branch suffixes from dependency versions, including rc versions")
    void removesDependencyBranchVersionsOnCoreBranch() {
        Path pom = writePom(project, "sample-pom-with-branch-deps.xml");

        RunResult result = run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(pom, "expected-remove-dependency-branch-versions.xml");
        assertTrue(result.changesMade());
        assertEquals(List.of("Switched to non branch dependency versions."), git.getCommits());
    }

    @Test
    @DisplayName("core branch with nothing to strip makes no changes")
    void makesNoChangesOnCleanCoreBranch() {
        Path pom = writePom(project, "sample-pom.xml");

        RunResult result = run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(pom, "sample-pom.xml");
        assertFalse(result.changesMade());
        assertTrue(git.getCommits().isEmpty());
    }

    @Test
    @DisplayName("release* glob matches core branches")
    void treatsGlobMatchedBranchAsCore() {
        writePom(project, "sample-pom-with-branch-version.xml");

        RunResult result = run(project, settings -> settings.setBranchName("release/2.0"));

        assertTrue(result.coreBranch());
    }

    @Test
    @DisplayName("a release version is left alone instead of being turned into a snapshot")
    void leavesReleaseVersionsAlone() {
        Path pom = writeFile(project, "pom.xml", fixture("sample-pom.xml")
                .replace("<version>1.2.3-SNAPSHOT</version>", "<version>1.2.3</version>"));

        RunResult result = run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertTrue(read(pom).contains("<version>1.2.3</version>"));
        assertFalse(result.changesMade());
        assertTrue(git.getCommits().isEmpty());
    }

    // --- Per-branch configuration ----------------------------------------

    @Test
    @DisplayName("config pins the project version for a matching branch")
    void pinsProjectVersion() {
        Path pom = writePom(project, "sample-pom.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # branch-pattern  target           value
                feature/f1        project-version  1.2.3-f1-SNAPSHOT
                """);

        run(project, settings -> settings.setBranchName("feature/f1"));

        assertMatchesFixture(pom, "expected-config-pin-project-version.xml");
    }

    @Test
    @DisplayName("config pins dependency versions even when enforcement is off")
    void pinsDependencyVersions() {
        Path pom = writePom(project, "sample-pom-two-deps.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # branch-pattern  target                     value
                feature/f1        dependency:com.example:d1  1.0.0-f1-SNAPSHOT
                feature/f1        dependency:com.example:d2  5.0.0-f1-SNAPSHOT
                """);

        run(project, settings -> settings
                .setBranchName("feature/f1")
                .setEnforceBranchVersion(false));

        assertMatchesFixture(pom, "expected-config-pin-dependency-versions.xml");
        assertEquals(List.of("Pinned branch-specific dependency versions."), git.getCommits());
    }

    @Test
    @DisplayName("inline and indented comments in the config are ignored")
    void ignoresComments() {
        Path pom = writePom(project, "sample-pom-two-deps.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # full-line comment: branch-pattern  target  value
                feature/f1        dependency:com.example:d1  1.0.0-f1-SNAPSHOT   # pin d1 for f1
                feature/f1        dependency:com.example:d2  5.0.0-f1-SNAPSHOT # pin d2 for f1
                   # indented full-line comment should be ignored too
                """);

        run(project, settings -> settings
                .setBranchName("feature/f1")
                .setEnforceBranchVersion(false));

        assertMatchesFixture(pom, "expected-config-pin-dependency-versions.xml");
    }

    @Test
    @DisplayName("a config that matches no branch falls back to the derived version")
    void fallsBackWhenConfigDoesNotMatch() {
        Path pom = writePom(project, "sample-pom.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # Config only covers feature/f1 - this run is on feature/my-feature
                feature/f1  project-version  1.2.3-f1-SNAPSHOT
                """);

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(pom, "expected-enforce-branch-version.xml");
    }

    @Test
    @DisplayName("a pinned version that is not a branch version fails the build")
    void rejectsInvalidPin() {
        writePom(project, "sample-pom.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # 'vf1' is not a valid branch version - must be rejected
                feature/f1  project-version  vf1
                """);

        PaoException failure = assertThrows(PaoException.class,
                () -> run(project, settings -> settings.setBranchName("feature/f1")));

        assertTrue(failure.getMessage().contains("vf1"), failure.getMessage());
    }

    @Test
    @DisplayName("an inherited exclusive suffix is re-derived for the current branch")
    void reDerivesExclusiveSuffix() {
        Path pom = writePom(project, "sample-pom-with-branch-version.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # branch-pattern  target                    value
                *                 exclusive-version-suffix  feature-old
                """);

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(pom, "expected-config-exclusive-suffix-rederive.xml");
    }

    @Test
    @DisplayName("the branch that owns an exclusive suffix keeps its version")
    void leavesExclusiveSuffixOwnerAlone() {
        Path pom = writePom(project, "sample-pom-with-branch-version.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                # branch-pattern  target                    value
                *                 exclusive-version-suffix  feature-old
                """);

        RunResult result = run(project, settings -> settings.setBranchName("feature/old"));

        assertMatchesFixture(pom, "sample-pom-with-branch-version.xml");
        assertFalse(result.changesMade());
    }

    // --- Branch detection and outputs -------------------------------------

    @Test
    @DisplayName("the branch name is taken from the CI environment when not configured")
    void detectsBranchFromEnvironment() {
        writePom(project, "sample-pom.xml");
        environment = Map.of("GITHUB_REF_NAME", "feature/my-feature");

        RunResult result = run(project, settings -> {
        });

        assertEquals("feature/my-feature", result.branchName());
    }

    @Test
    @DisplayName("on a pull-request build the source branch wins over the synthetic merge ref")
    void prefersPullRequestHeadRef() {
        writePom(project, "sample-pom.xml");
        // What GitHub Actions sets on a pull_request event: GITHUB_REF is
        // refs/pull/123/merge, so GITHUB_REF_NAME is the unusable '123/merge'.
        environment = Map.of(
                "GITHUB_HEAD_REF", "feature/my-feature",
                "GITHUB_REF_NAME", "123/merge");

        RunResult result = run(project, settings -> {
        });

        assertEquals("feature/my-feature", result.branchName());
    }

    @Test
    @DisplayName("on a push build the empty pull-request variable is ignored")
    void ignoresEmptyPullRequestHeadRef() {
        writePom(project, "sample-pom.xml");
        // GITHUB_HEAD_REF is present but empty on push events.
        environment = Map.of(
                "GITHUB_HEAD_REF", "",
                "GITHUB_REF_NAME", "feature/my-feature");

        RunResult result = run(project, settings -> {
        });

        assertEquals("feature/my-feature", result.branchName());
    }

    @Test
    @DisplayName("the changes-made output is appended to the configured output file")
    void writesOutputFile() {
        writePom(project, "sample-pom.xml");
        Path output = project.resolve("build.env");

        run(project, settings -> settings
                .setBranchName("feature/my-feature")
                .setOutputFile(output));

        assertEquals("changes-made=true", read(output).strip());
    }

    @Test
    @DisplayName("only the poms the run wrote are committed")
    void commitsOnlyTheFilesItWrote() {
        Path pom = writePom(project, "sample-pom.xml");
        // Whatever else is in the tree - an earlier pipeline step's output, or a
        // developer's own edits - must stay out of the version commit.
        writeFile(project, "left-behind.txt", "not ours to commit");

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertEquals(List.of(pom), git.getCommittedFiles("Switched to branch-specific version."));
    }

    @Test
    @DisplayName("changes are pushed to the detected branch when pushing is enabled")
    void pushesToDetectedBranch() {
        writePom(project, "sample-pom.xml");

        run(project, settings -> settings
                .setBranchName("feature/my-feature")
                .setPushChanges(true));

        assertEquals(List.of("feature/my-feature"), git.getPushes());
    }
}
