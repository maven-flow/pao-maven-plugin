package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
