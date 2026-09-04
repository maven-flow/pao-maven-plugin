package com.jardoapps.pao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.apache.maven.plugin.logging.Log;

import com.jardoapps.pao.config.PinConfig;
import com.jardoapps.pao.config.PinConfigParser;
import com.jardoapps.pao.git.GitClient;
import com.jardoapps.pao.pom.PomDocument;
import com.jardoapps.pao.pom.PomEditSession;
import com.jardoapps.pao.pom.XmlElement;

/**
 * The whole behaviour of the plugin, expressed against plain types so it can be
 * driven either by the mojo or directly by tests.
 */
public class PreventOverwritesRunner {

    /** What a run did, for reporting and for CI outputs. */
    public record RunResult(
            boolean changesMade,
            String branchName,
            boolean coreBranch,
            String projectVersion,
            List<String> commitMessages) {
    }

    private static final String COMMIT_ENFORCE = "Switched to branch-specific version.";
    private static final String COMMIT_PIN_DEPENDENCIES = "Pinned branch-specific dependency versions.";
    private static final String COMMIT_REMOVE_VERSION = "Switched to non branch-specific version.";
    private static final String COMMIT_REMOVE_DEPENDENCIES = "Switched to non branch dependency versions.";

    private final RunnerSettings settings;
    private final GitClient git;
    private final Path baseDirectory;
    private final UnaryOperator<String> environment;
    private final Log log;

    public PreventOverwritesRunner(RunnerSettings settings, GitClient git, Path baseDirectory,
            UnaryOperator<String> environment, Log log) {
        this.settings = settings;
        this.git = git;
        this.baseDirectory = baseDirectory;
        this.environment = environment;
        this.log = log;
    }

    /** Runs against a reactor whose first entry is the top-level project. */
    public RunResult run(List<ProjectModel> reactor) {
        if (reactor.isEmpty()) {
            throw new PaoException("No Maven projects to process.");
        }

        String branchName = BranchDetector.detect(settings.getBranchName(), environment, git, log);
        boolean coreBranch = isCoreBranch(branchName);
        log.info("Current branch: '" + branchName + "'");
        log.info("Needs branch version: " + !coreBranch);

        ProjectModel root = reactor.get(0);
        log.info("Project version: " + root.version());
        if (reactor.size() > 1) {
            log.info("Reactor contains " + reactor.size() + " projects.");
        }

        PinConfig pins = new PinConfigParser(log).parse(resolveConfigFile(), branchName);

        List<String> commits = new ArrayList<>();
        if (coreBranch) {
            removeBranchVersion(reactor, root, commits);
            removeDependencyBranchVersions(reactor, commits);
        } else {
            enforceBranchVersion(reactor, root, branchName, pins, commits);
            applyDependencyPins(reactor, pins, commits);
        }

        boolean changesMade = !commits.isEmpty();
        log.info(changesMade ? "Changes have been made." : "No changes have been made.");
        writeOutput("changes-made", String.valueOf(changesMade));

        if (changesMade) {
            if (settings.isPushChanges()) {
                log.info("Pushing changes to branch '" + branchName + "'...");
                git.push(branchName);
            } else {
                log.info("Push changes disabled. Skipping push.");
            }
        }

        return new RunResult(changesMade, branchName, coreBranch, root.version(), List.copyOf(commits));
    }

    // --- Feature branches -------------------------------------------------

    private void enforceBranchVersion(List<ProjectModel> reactor, ProjectModel root, String branchName, PinConfig pins,
            List<String> commits) {
        if (!settings.isEnforceBranchVersion()) {
            log.info("Project version enforcement is turned off.");
            return;
        }

        String currentVersion = root.version();
        String branchSuffix = BranchVersions.branchSuffix(branchName);
        String newVersion;

        Optional<String> pinned = pins.getProjectVersion();
        if (pinned.isPresent()) {
            // An explicit pin always wins, even over an inherited branch suffix.
            newVersion = pinned.get();
            if (newVersion.equals(currentVersion)) {
                log.info("Project already at pinned version.");
                return;
            }
            log.info("Using pinned project version: " + newVersion);
        } else if (BranchVersions.isBranchVersion(currentVersion)) {
            String currentSuffix = BranchVersions.suffixOf(currentVersion).orElseThrow();
            if (!currentSuffix.equals(branchSuffix) && pins.isExclusiveSuffix(currentSuffix)) {
                // The suffix belongs to another branch, so an inherited version would
                // publish under - and overwrite - that branch's artifacts.
                newVersion = BranchVersions.withBranch(currentVersion, branchSuffix);
                log.info("Suffix '" + currentSuffix + "' is exclusive to another branch. Re-deriving to: "
                        + newVersion);
            } else {
                log.info("Project already has a branch version.");
                return;
            }
        } else {
            newVersion = BranchVersions.withBranch(currentVersion, branchSuffix);
            log.info("Project does not have a branch version. Changing to: " + newVersion);
        }

        if (!BranchVersions.isBranchVersion(newVersion)) {
            log.warn("Version '" + newVersion + "' does not match '<base>-<suffix>-SNAPSHOT', so it will not be"
                    + " stripped automatically when this branch is merged into a core branch.");
        }

        changeProjectVersion(reactor, currentVersion, newVersion, COMMIT_ENFORCE, commits);
    }

    private void applyDependencyPins(List<ProjectModel> reactor, PinConfig pins, List<String> commits) {
        Map<String, String> pinnedVersions = pins.getDependencyVersions();
        if (pinnedVersions.isEmpty()) {
            return;
        }

        PomEditSession session = new PomEditSession(reactor, log);
        for (PomDocument document : session.documents()) {
            for (PomDocument.DependencyEntry dependency : document.dependencies()) {
                String pinnedVersion = pinnedVersions.get(dependency.key());
                if (pinnedVersion == null) {
                    continue;
                }
                if (session.setVersion(document, dependency.versionElement(), null, pinnedVersion)) {
                    log.info("Pinning dependency " + dependency.key() + " to " + pinnedVersion + " in "
                            + document.getPath());
                }
            }
        }
        commit(session, COMMIT_PIN_DEPENDENCIES, commits);
    }

    // --- Core branches ----------------------------------------------------

    private void removeBranchVersion(List<ProjectModel> reactor, ProjectModel root, List<String> commits) {
        String currentVersion = root.version();
        Optional<String> stripped = BranchVersions.withoutBranch(currentVersion);
        if (stripped.isEmpty()) {
            return;
        }
        log.info("Project has a branch version. Removing it, since we are on a core branch.");
        log.info("New version: " + stripped.get());
        changeProjectVersion(reactor, currentVersion, stripped.get(), COMMIT_REMOVE_VERSION, commits);
    }

    private void removeDependencyBranchVersions(List<ProjectModel> reactor, List<String> commits) {
        PomEditSession session = new PomEditSession(reactor, log);
        for (PomDocument document : session.documents()) {
            for (XmlElement version : document.allVersionElements()) {
                stripBranchVersion(document, version, "version", commits);
            }
            for (Map.Entry<String, XmlElement> property : document.properties().entrySet()) {
                stripBranchVersion(document, property.getValue(), "property " + property.getKey(), commits);
            }
        }
        commit(session, COMMIT_REMOVE_DEPENDENCIES, commits);
    }

    private void stripBranchVersion(PomDocument document, XmlElement element, String description,
            List<String> commits) {
        String value = document.valueOf(element);
        BranchVersions.withoutBranch(value).ifPresent(stripped -> {
            log.info("Replacing " + description + " " + value + " with " + stripped + " in " + document.getPath());
            document.setValue(element, stripped);
        });
    }

    // --- Shared -----------------------------------------------------------

    /**
     * Rewrites the project version across the reactor. Every reference a module makes
     * to another reactor project has to move in step with it, or that module resolves
     * against the repository instead of the reactor: {@code <parent><version>} for the
     * inherited version, and any {@code <dependency>} on a sibling that spells the
     * version out rather than deriving it.
     */
    private void changeProjectVersion(List<ProjectModel> reactor, String oldVersion, String newVersion, String message,
            List<String> commits) {
        Set<String> reactorKeys = new HashSet<>();
        reactor.forEach(project -> reactorKeys.add(project.key()));

        PomEditSession session = new PomEditSession(reactor, log);
        for (ProjectModel project : reactor) {
            PomDocument document = session.document(project.pomFile());

            if (oldVersion.equals(project.version())) {
                document.projectVersion()
                        .ifPresent(element -> session.setVersion(document, element, oldVersion, newVersion));
            }

            if (project.hasParent() && oldVersion.equals(project.parentVersion())
                    && reactorKeys.contains(project.parentKey())) {
                document.parentVersion()
                        .ifPresent(element -> session.setVersion(document, element, oldVersion, newVersion));
            }

            updateSiblingDependencies(session, document, reactorKeys, oldVersion, newVersion);
        }
        commit(session, message, commits);
    }

    /** Moves dependencies on other reactor modules to the new version. */
    private void updateSiblingDependencies(PomEditSession session, PomDocument document, Set<String> reactorKeys,
            String oldVersion, String newVersion) {
        for (PomDocument.DependencyEntry dependency : document.dependencies()) {
            if (!reactorKeys.contains(dependency.key())) {
                continue;
            }
            if (isMavenExpression(document.valueOf(dependency.versionElement()))) {
                // ${project.version} and friends already follow the project version.
                continue;
            }
            if (session.setVersion(document, dependency.versionElement(), oldVersion, newVersion)) {
                log.info("Moving dependency " + dependency.key() + " to " + newVersion + " in " + document.getPath());
            }
        }
    }

    /** True for {@code ${project.*}} / {@code ${pom.*}}, which Maven resolves itself. */
    private static boolean isMavenExpression(String value) {
        return BranchVersions.propertyReference(value)
                .filter(name -> name.startsWith("project.") || name.startsWith("pom."))
                .isPresent();
    }

    private void commit(PomEditSession session, String message, List<String> commits) {
        List<Path> written = session.save();
        if (written.isEmpty()) {
            log.debug("No pom file required a change for: " + message);
            return;
        }
        written.forEach(path -> log.info("Updated " + path));

        String fullMessage = message + settings.getCommitMessageSuffix();
        git.commit(fullMessage, written, settings.getGitUserName(), settings.getGitUserEmail());
        commits.add(fullMessage);
    }

    private boolean isCoreBranch(String branchName) {
        return settings.getCoreBranches().stream().anyMatch(pattern -> GlobMatcher.matches(pattern, branchName));
    }

    private Path resolveConfigFile() {
        Path configured = settings.getConfigFile();
        if (configured == null) {
            configured = Path.of(".prevent-overwrites.conf");
        }
        return configured.isAbsolute() ? configured : baseDirectory.resolve(configured);
    }

    /** Mirrors the outputs the shell version exposed to GitHub Actions and GitLab. */
    private void writeOutput(String name, String value) {
        log.info("Output: " + name + "=" + value);
        appendOutput(environment.apply("GITHUB_OUTPUT"), name, value);
        if (settings.getOutputFile() != null) {
            appendOutput(settings.getOutputFile().toString(), name, value);
        }
    }

    private void appendOutput(String file, String name, String value) {
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(file), name + "=" + value + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write output file " + file, e);
        }
    }
}
