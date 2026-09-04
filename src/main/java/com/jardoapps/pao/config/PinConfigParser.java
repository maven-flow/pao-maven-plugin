package com.jardoapps.pao.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;

import com.jardoapps.pao.BranchVersions;
import com.jardoapps.pao.GlobMatcher;
import com.jardoapps.pao.PaoException;

/**
 * Reads the optional per-branch pinning file.
 *
 * <p>Each non-empty line is three whitespace-separated columns; everything from a
 * {@code #} to the end of the line is a comment.
 *
 * <pre>
 * &lt;branch-pattern&gt;  project-version                     &lt;pinned-version&gt;
 * &lt;branch-pattern&gt;  dependency:&lt;groupId&gt;:&lt;artifactId&gt;   &lt;pinned-version&gt;
 * &lt;branch-pattern&gt;  exclusive-version-suffix            &lt;suffix&gt;
 * </pre>
 */
public final class PinConfigParser {

    private static final String PROJECT_VERSION = "project-version";
    private static final String EXCLUSIVE_SUFFIX = "exclusive-version-suffix";
    private static final String DEPENDENCY_PREFIX = "dependency:";

    private final Log log;

    public PinConfigParser(Log log) {
        this.log = log;
    }

    /** Parses the file if it exists, keeping only the rows matching {@code branchName}. */
    public PinConfig parse(Path file, String branchName) {
        if (file == null) {
            log.info("No config file configured. Using default behaviour.");
            return PinConfig.empty();
        }
        if (!Files.isRegularFile(file)) {
            // Worth saying out loud: someone who expected their pins to apply needs to
            // see that the file the plugin looked for is not where it looked.
            log.info("Config file '" + file + "' does not exist. Using default behaviour.");
            return PinConfig.empty();
        }

        log.info("Loading config from '" + file + "' for branch '" + branchName + "'...");

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }

        String projectVersion = null;
        Map<String, String> dependencyVersions = new LinkedHashMap<>();
        Set<String> exclusiveSuffixes = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            if (line.isBlank()) {
                continue;
            }

            String[] columns = line.trim().split("\\s+");
            if (columns.length < 3) {
                throw new PaoException(file + ":" + (i + 1)
                        + ": malformed line (expected 3 columns: <branch-pattern> <target> <value>): " + line.trim());
            }

            String pattern = columns[0];
            String target = columns[1];
            String value = columns[2];

            // Every row is validated, not just the ones for this branch: a typo in
            // another branch's row would otherwise stay silent until that branch builds.
            String dependencyKey = validateRow(file, i + 1, target, value);

            if (!GlobMatcher.matches(pattern, branchName)) {
                continue;
            }

            if (PROJECT_VERSION.equals(target)) {
                if (projectVersion == null) {
                    projectVersion = value;
                    log.info("Pin: project-version -> " + value);
                } else {
                    log.warn("Multiple project-version pins match branch '" + branchName + "' in " + file
                            + "; keeping '" + projectVersion + "' and ignoring '" + value + "'.");
                }
            } else if (EXCLUSIVE_SUFFIX.equals(target)) {
                exclusiveSuffixes.add(value);
                log.info("Exclusive version suffix: " + value);
            } else {
                String previous = dependencyVersions.put(dependencyKey, value);
                if (previous != null && !previous.equals(value)) {
                    log.warn("Multiple pins for dependency " + dependencyKey + " match branch '" + branchName + "' in "
                            + file + "; using '" + value + "'.");
                }
                log.info("Pin: dependency " + dependencyKey + " -> " + value);
            }
        }

        return new PinConfig(projectVersion, dependencyVersions, exclusiveSuffixes);
    }

    /**
     * Checks that a row names a known target and carries a usable value.
     *
     * @return the {@code groupId:artifactId} for a dependency row, otherwise null
     */
    private String validateRow(Path file, int lineNumber, String target, String value) {
        if (EXCLUSIVE_SUFFIX.equals(target)) {
            return null;
        }

        String dependencyKey = null;
        if (target.startsWith(DEPENDENCY_PREFIX)) {
            dependencyKey = target.substring(DEPENDENCY_PREFIX.length());
            if (dependencyKey.chars().filter(c -> c == ':').count() != 1 || dependencyKey.startsWith(":")
                    || dependencyKey.endsWith(":")) {
                throw new PaoException(file + ":" + lineNumber + ": malformed dependency target '" + target
                        + "' (expected 'dependency:<groupId>:<artifactId>').");
            }
        } else if (!PROJECT_VERSION.equals(target)) {
            throw new PaoException(file + ":" + lineNumber + ": unknown target '" + target
                    + "' (expected 'project-version', 'dependency:<groupId>:<artifactId>' or '" + EXCLUSIVE_SUFFIX
                    + "').");
        }

        if (!BranchVersions.isValidPin(value)) {
            throw new PaoException(file + ":" + lineNumber + ": invalid pinned version '" + value + "' for target '"
                    + target + "'. Pinned versions must match '<base>-<suffix>-SNAPSHOT' (e.g. 1.2.3-f1-SNAPSHOT)"
                    + " so they can be reverted to '<base>-SNAPSHOT' on core branches.");
        }
        return dependencyKey;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }
}
