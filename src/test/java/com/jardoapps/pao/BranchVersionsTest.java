package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BranchVersionsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.3-feature-abc-SNAPSHOT",
            "1.2.3-rc.4-feature-abc-SNAPSHOT",
            "1.2.10-feature-abc-SNAPSHOT",
            "1.2.3.4-feature-abc-SNAPSHOT",
            "1.2-feature-abc-SNAPSHOT" })
    void recognisesBranchVersions(String version) {
        assertTrue(BranchVersions.isBranchVersion(version));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.2.3-SNAPSHOT",
            "1.2.3",
            "1.2.3-rc.4-SNAPSHOT",
            "${revision}",
            "not-a-version" })
    void rejectsNonBranchVersions(String version) {
        assertFalse(BranchVersions.isBranchVersion(version));
    }

    @ParameterizedTest
    @CsvSource({
            "1.2.3-feature-abc-SNAPSHOT,       1.2.3-SNAPSHOT",
            "1.2.3-rc.4-feature-abc-SNAPSHOT,  1.2.3-rc.4-SNAPSHOT",
            "1.2.3.4-feature-abc-SNAPSHOT,     1.2.3.4-SNAPSHOT" })
    void stripsBranchSuffix(String version, String expected) {
        assertEquals(Optional.of(expected), BranchVersions.withoutBranch(version));
    }

    @Test
    @DisplayName("a two-digit patch number survives the round trip")
    void keepsMultiDigitPatchNumbers() {
        String branchVersion = BranchVersions.withBranch("1.2.10-SNAPSHOT", "feature-abc").orElseThrow();

        assertEquals("1.2.10-feature-abc-SNAPSHOT", branchVersion);
        assertEquals(Optional.of("1.2.10-SNAPSHOT"), BranchVersions.withoutBranch(branchVersion));
    }

    @ParameterizedTest
    @CsvSource({
            "1.2.3-SNAPSHOT,                   feature-abc,  1.2.3-feature-abc-SNAPSHOT",
            "1.2.3-rc.4-SNAPSHOT,              feature-abc,  1.2.3-rc.4-feature-abc-SNAPSHOT",
            "1.2.3-feature-old-SNAPSHOT,       feature-abc,  1.2.3-feature-abc-SNAPSHOT" })
    void addsOrReplacesBranchSuffix(String version, String suffix, String expected) {
        assertEquals(Optional.of(expected), BranchVersions.withBranch(version, suffix));
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.2.3", "1.2.3-rc.4", "1.2.3.RELEASE" })
    @DisplayName("no branch version is derived from a release version")
    void refusesToDeriveFromReleaseVersions(String version) {
        // Deriving one would be lossy: the trip back on a core branch yields
        // <base>-SNAPSHOT, silently turning a released project into a snapshot one.
        assertEquals(Optional.empty(), BranchVersions.withBranch(version, "feature-abc"));
    }

    @Test
    void extractsSuffix() {
        assertEquals(Optional.of("feature-abc"), BranchVersions.suffixOf("1.2.3-feature-abc-SNAPSHOT"));
        assertEquals(Optional.of("feature-abc"), BranchVersions.suffixOf("1.2.3-rc.4-feature-abc-SNAPSHOT"));
        assertEquals(Optional.empty(), BranchVersions.suffixOf("1.2.3-SNAPSHOT"));
    }

    @Test
    void convertsBranchNameToSuffix() {
        assertEquals("feature-FEA-123-comments", BranchVersions.branchSuffix("feature/FEA-123-comments"));
        assertEquals("main", BranchVersions.branchSuffix("main"));
    }

    @Test
    void acceptsOnlyRevertiblePins() {
        assertTrue(BranchVersions.isValidPin("1.2.3-f1-SNAPSHOT"));
        assertFalse(BranchVersions.isValidPin("vf1"));
        assertFalse(BranchVersions.isValidPin("1.2.3-SNAPSHOT"));
    }

    @Test
    void detectsPropertyReferences() {
        assertEquals(Optional.of("revision"), BranchVersions.propertyReference("${revision}"));
        assertEquals(Optional.empty(), BranchVersions.propertyReference("1.2.3-SNAPSHOT"));
        assertEquals(Optional.empty(), BranchVersions.propertyReference("prefix-${revision}"));
    }
}
