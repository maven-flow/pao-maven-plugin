package com.jardoapps.pao;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import com.jardoapps.pao.git.CommandLineGitClient;
import com.jardoapps.pao.git.GitClient;

/**
 * Applies branch-specific versions to the reactor's pom.xml files, commits the
 * result and optionally pushes it.
 *
 * <p>On a feature branch the project version gains a suffix derived from the branch
 * name ({@code 1.1.0-SNAPSHOT} becomes {@code 1.1.0-feature-foo-SNAPSHOT}); on a core
 * branch that suffix is stripped again, from the project version and from any
 * dependency versions that carry one.
 *
 * <p>Run this as its own invocation, before the build that publishes the artifacts:
 * Maven has already read the POMs by the time any mojo executes, so a version written
 * during a build does not change what that same build deploys.
 */
@Mojo(name = "apply",
        defaultPhase = LifecyclePhase.VALIDATE,
        aggregator = true,
        requiresProject = true,
        threadSafe = true)
public class ApplyMojo extends AbstractMojo {

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** The branch being built. Auto-detected from the CI environment or git when unset. */
    @Parameter(property = "pao.branchName")
    private String branchName;

    /**
     * Whether to give the project itself a branch-specific version. Set this to true
     * for libraries, and false for applications that only need their dependency
     * versions reset on core branches.
     */
    @Parameter(property = "pao.enforceBranchVersion", defaultValue = "true")
    private boolean enforceBranchVersion;

    /** Whether to push the resulting commits to {@code origin}. */
    @Parameter(property = "pao.pushChanges", defaultValue = "true")
    private boolean pushChanges;

    /** Appended to every commit message, e.g. {@code [skip ci]}. */
    @Parameter(property = "pao.commitMessageSuffix", defaultValue = "")
    private String commitMessageSuffix;

    @Parameter(property = "pao.gitUserName", defaultValue = "ci-bot")
    private String gitUserName;

    @Parameter(property = "pao.gitUserEmail", defaultValue = "ci-bot@example.com")
    private String gitUserEmail;

    /**
     * Branch name patterns that must keep the plain version. Glob patterns are
     * supported, and the list may be separated by spaces or commas.
     */
    @Parameter(property = "pao.coreBranches", defaultValue = "main master develop release*")
    private String coreBranches;

    /** Optional per-branch version pinning file, relative to the top-level project. */
    @Parameter(property = "pao.configFile", defaultValue = ".prevent-overwrites.conf")
    private String configFile;

    /** Optional file to append {@code changes-made=<boolean>} to, for CI to pick up. */
    @Parameter(property = "pao.outputFile")
    private String outputFile;

    /** Skips execution entirely. */
    @Parameter(property = "pao.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping (pao.skip=true).");
            return;
        }

        MavenProject topLevelProject = session.getTopLevelProject();
        Path baseDirectory = topLevelProject.getBasedir().toPath();

        RunnerSettings settings = new RunnerSettings()
                .setBranchName(branchName)
                .setEnforceBranchVersion(enforceBranchVersion)
                .setPushChanges(pushChanges)
                .setCommitMessageSuffix(commitMessageSuffix)
                .setGitUserName(gitUserName)
                .setGitUserEmail(gitUserEmail)
                .setCoreBranches(coreBranches)
                .setConfigFile(configFile == null ? null : Path.of(configFile))
                .setOutputFile(outputFile == null || outputFile.isBlank() ? null : Path.of(outputFile));

        GitClient git = new CommandLineGitClient(baseDirectory, getLog());
        PreventOverwritesRunner runner =
                new PreventOverwritesRunner(settings, git, baseDirectory, System::getenv, getLog());

        try {
            runner.run(collectReactor(topLevelProject));
        } catch (PaoException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to apply branch-specific versions: " + e.getMessage(), e);
        }
    }

    /** The reactor as plain coordinates, with the top-level project first. */
    private List<ProjectModel> collectReactor(MavenProject topLevelProject) {
        List<ProjectModel> reactor = new ArrayList<>();
        reactor.add(toModel(topLevelProject));
        for (MavenProject project : session.getAllProjects()) {
            if (project != topLevelProject) {
                reactor.add(toModel(project));
            }
        }
        return reactor;
    }

    private ProjectModel toModel(MavenProject project) {
        Parent parent = project.getModel().getParent();
        return new ProjectModel(
                project.getFile().toPath(),
                project.getGroupId(),
                project.getArtifactId(),
                project.getVersion(),
                parent == null ? null : parent.getGroupId(),
                parent == null ? null : parent.getArtifactId(),
                parent == null ? null : parent.getVersion());
    }
}
