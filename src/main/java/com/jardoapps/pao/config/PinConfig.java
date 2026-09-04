package com.jardoapps.pao.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The entries of the per-branch configuration file that apply to the current branch. */
public final class PinConfig {

    private final String projectVersion;
    private final Map<String, String> dependencyVersions;
    private final Set<String> exclusiveSuffixes;

    public PinConfig(String projectVersion, Map<String, String> dependencyVersions, Set<String> exclusiveSuffixes) {
        this.projectVersion = projectVersion;
        this.dependencyVersions = new LinkedHashMap<>(dependencyVersions);
        this.exclusiveSuffixes = Set.copyOf(exclusiveSuffixes);
    }

    public static PinConfig empty() {
        return new PinConfig(null, Map.of(), Set.of());
    }

    /** The pinned project version for this branch, if one was configured. */
    public Optional<String> getProjectVersion() {
        return Optional.ofNullable(projectVersion);
    }

    /** Pinned dependency versions for this branch, keyed by {@code groupId:artifactId}. */
    public Map<String, String> getDependencyVersions() {
        return Map.copyOf(dependencyVersions);
    }

    /** Suffixes declared as belonging to a single branch. */
    public boolean isExclusiveSuffix(String suffix) {
        return exclusiveSuffixes.contains(suffix);
    }

    public boolean isEmpty() {
        return projectVersion == null && dependencyVersions.isEmpty() && exclusiveSuffixes.isEmpty();
    }
}
