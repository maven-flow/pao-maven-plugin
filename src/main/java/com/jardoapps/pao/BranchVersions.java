package com.jardoapps.pao;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for deriving, recognising and stripping branch-specific versions.
 *
 * <p>A branch version is {@code <base>-<suffix>-SNAPSHOT}, for example
 * {@code 1.2.3-feature-abc-SNAPSHOT} or {@code 1.2.3-rc.4-feature-abc-SNAPSHOT}.
 * The base may carry any number of numeric segments and an optional {@code -rc}
 * qualifier; everything between the base and {@code -SNAPSHOT} is the suffix.
 */
public final class BranchVersions {

    private static final String SNAPSHOT = "-SNAPSHOT";

    /**
     * The numeric part plus an optional {@code -rc} qualifier.
     *
     * <p>Both quantifiers are possessive so the base can never be re-read as
     * something shorter: without that, {@code 1.2.3-rc.4-SNAPSHOT} would match with a
     * base of {@code 1.2.3} and a "branch suffix" of {@code rc.4}, and stripping it
     * would silently drop the release candidate qualifier.
     */
    private static final String BASE = "\\d++(?:\\.\\d++)++(?:-rc(?:\\.\\d++)?)?+";

    private static final Pattern BRANCH_VERSION = Pattern.compile("^(" + BASE + ")-(.+)-SNAPSHOT$");

    private static final Pattern PLAIN_SNAPSHOT = Pattern.compile("^(" + BASE + ")-SNAPSHOT$");

    /** A {@code ${...}} property reference used in place of a literal version. */
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("^\\$\\{([^}]+)}$");

    private BranchVersions() {
    }

    public static boolean isBranchVersion(String version) {
        return version != null && BRANCH_VERSION.matcher(version).matches();
    }

    /** The base of a branch version, e.g. {@code 1.2.3-rc.4-feature-abc-SNAPSHOT} -> {@code 1.2.3-rc.4}. */
    public static Optional<String> baseOf(String version) {
        Matcher matcher = BRANCH_VERSION.matcher(version);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** The suffix of a branch version, e.g. {@code 1.2.3-feature-abc-SNAPSHOT} -> {@code feature-abc}. */
    public static Optional<String> suffixOf(String version) {
        Matcher matcher = BRANCH_VERSION.matcher(version);
        return matcher.matches() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    /** Strips the branch suffix, e.g. {@code 1.2.3-feature-abc-SNAPSHOT} -> {@code 1.2.3-SNAPSHOT}. */
    public static Optional<String> withoutBranch(String version) {
        return baseOf(version).map(base -> base + "-SNAPSHOT");
    }

    /**
     * Builds the branch version for the given suffix. An existing branch suffix is
     * replaced, otherwise the suffix is inserted before {@code -SNAPSHOT}.
     *
     * <p>Empty for a version that is not a snapshot. The plugin's premise is that
     * several branches would otherwise publish over one shared snapshot, so a release
     * version is a sign the goal is running somewhere it was not meant to - and
     * deriving one anyway would be lossy: {@code 1.2.3} would become
     * {@code 1.2.3-<suffix>-SNAPSHOT} and come back as {@code 1.2.3-SNAPSHOT}, turning
     * a released project into a snapshot one on the way through.
     */
    public static Optional<String> withBranch(String version, String branchSuffix) {
        Optional<String> base = baseOf(version);
        if (base.isPresent()) {
            return Optional.of(base.get() + "-" + branchSuffix + "-SNAPSHOT");
        }
        Matcher plain = PLAIN_SNAPSHOT.matcher(version);
        if (plain.matches()) {
            return Optional.of(plain.group(1) + "-" + branchSuffix + "-SNAPSHOT");
        }
        if (!version.endsWith(SNAPSHOT)) {
            return Optional.empty();
        }
        String stripped = version.substring(0, version.length() - SNAPSHOT.length());
        return Optional.of(stripped + "-" + branchSuffix + SNAPSHOT);
    }

    /** Turns a branch name into a version suffix: {@code feature/abc} -> {@code feature-abc}. */
    public static String branchSuffix(String branchName) {
        return branchName.replace('/', '-');
    }

    /**
     * A pinned version must itself be a branch version, otherwise it could not be
     * reverted to {@code <base>-SNAPSHOT} when the branch is merged to a core branch.
     */
    public static boolean isValidPin(String version) {
        return isBranchVersion(version);
    }

    /** The property name referenced by {@code ${name}}, if the value is exactly such a reference. */
    public static Optional<String> propertyReference(String value) {
        Matcher matcher = PROPERTY_REFERENCE.matcher(value);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
