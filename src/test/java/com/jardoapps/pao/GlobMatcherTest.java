package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GlobMatcherTest {

    @Test
    void matchesLiterals() {
        assertTrue(GlobMatcher.matches("main", "main"));
        assertFalse(GlobMatcher.matches("main", "master"));
    }

    @Test
    void matchesStarAcrossSlashes() {
        assertTrue(GlobMatcher.matches("release*", "release/2.0"));
        assertTrue(GlobMatcher.matches("*", "feature/abc"));
        assertTrue(GlobMatcher.matches("feature/*", "feature/abc"));
    }

    @Test
    @DisplayName("** behaves like bash, where * already spans slashes")
    void treatsDoubleStarLikeBash() {
        assertTrue(GlobMatcher.matches("**/*", "feature/abc"));
        assertFalse(GlobMatcher.matches("**/*", "main"));
    }

    @Test
    void matchesSingleCharacterWildcard() {
        assertTrue(GlobMatcher.matches("v?", "v1"));
        assertFalse(GlobMatcher.matches("v?", "v10"));
    }

    @Test
    void treatsRegexCharactersAsLiterals() {
        assertTrue(GlobMatcher.matches("release-1.0", "release-1.0"));
        assertFalse(GlobMatcher.matches("release-1.0", "release-1x0"));
    }

    // --- Character classes ------------------------------------------------

    @Test
    void matchesCharacterClasses() {
        assertTrue(GlobMatcher.matches("release-[0-9]", "release-2"));
        assertFalse(GlobMatcher.matches("release-[0-9]", "release-x"));
        assertTrue(GlobMatcher.matches("release-[abc]", "release-b"));
    }

    @Test
    void matchesNegatedCharacterClasses() {
        assertTrue(GlobMatcher.matches("release-[!0-9]", "release-x"));
        assertFalse(GlobMatcher.matches("release-[!0-9]", "release-2"));
    }

    @Test
    @DisplayName("[]] is bash's literal ']', not an empty class")
    void treatsLeadingBracketAsLiteral() {
        assertTrue(GlobMatcher.matches("v[]]", "v]"));
        assertFalse(GlobMatcher.matches("v[]]", "v["));
        assertTrue(GlobMatcher.matches("v[!]]", "v["));
        assertFalse(GlobMatcher.matches("v[!]]", "v]"));
    }

    @Test
    @DisplayName("'&' in a class is a literal, not Java's intersection operator")
    void treatsAmpersandAsLiteral() {
        // As a regex intersection '[a&&b]' matches nothing at all.
        assertTrue(GlobMatcher.matches("v[a&&b]", "v&"));
        assertTrue(GlobMatcher.matches("v[a&&b]", "va"));
        assertTrue(GlobMatcher.matches("v[a&&b]", "vb"));
        assertFalse(GlobMatcher.matches("v[a&&b]", "vc"));
    }

    @Test
    @DisplayName("a backslash in a class is a literal backslash")
    void treatsBackslashInClassAsLiteral() {
        assertTrue(GlobMatcher.matches("v[\\n]", "v\\"));
        assertTrue(GlobMatcher.matches("v[\\n]", "vn"));
        assertFalse(GlobMatcher.matches("v[\\n]", "v\n"));
    }

    @Test
    @DisplayName("a nested '[' in a class is a literal, not a Java nested class")
    void treatsNestedBracketAsLiteral() {
        assertTrue(GlobMatcher.matches("v[[a]", "v["));
        assertTrue(GlobMatcher.matches("v[[a]", "va"));
        assertFalse(GlobMatcher.matches("v[[a]", "vb"));
    }

    @Test
    @DisplayName("an unmatched '[' is a literal, as it is in bash")
    void treatsUnmatchedBracketAsLiteral() {
        assertTrue(GlobMatcher.matches("release[", "release["));
        assertTrue(GlobMatcher.matches("[]", "[]"));
    }

    @Test
    @DisplayName("a pattern that cannot be compiled names itself in the failure")
    void reportsThePatternThatFailed() {
        // A reversed range is invalid in a regex and meaningless in bash alike.
        PaoException failure = assertThrows(PaoException.class, () -> GlobMatcher.matches("release-[z-a]", "x"));

        assertTrue(failure.getMessage().contains("release-[z-a]"), failure.getMessage());
    }
}
