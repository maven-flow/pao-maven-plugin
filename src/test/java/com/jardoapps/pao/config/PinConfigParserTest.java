package com.jardoapps.pao.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jardoapps.pao.PaoException;

class PinConfigParserTest {

    @TempDir
    Path directory;

    private final PinConfigParser parser = new PinConfigParser(new SystemStreamLog());

    private PinConfig parse(String content, String branch) {
        try {
            Path file = directory.resolve(".prevent-overwrites.conf");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return parser.parse(file, branch);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void returnsEmptyConfigWhenFileIsMissing() {
        assertTrue(parser.parse(directory.resolve("nope.conf"), "feature/f1").isEmpty());
    }

    @Test
    void readsAllThreeTargets() {
        PinConfig config = parse("""
                feature/f1  project-version                    1.2.3-f1-SNAPSHOT
                feature/f1  dependency:com.example:d1          2.0.0-f1-SNAPSHOT
                feature/f1  exclusive-version-suffix           f1
                """, "feature/f1");

        assertEquals(Optional.of("1.2.3-f1-SNAPSHOT"), config.getProjectVersion());
        assertEquals(Map.of("com.example:d1", "2.0.0-f1-SNAPSHOT"), config.getDependencyVersions());
        assertTrue(config.isExclusiveSuffix("f1"));
        assertFalse(config.isExclusiveSuffix("f2"));
    }

    @Test
    @DisplayName("rows for other branches are ignored")
    void keepsOnlyMatchingRows() {
        PinConfig config = parse("""
                feature/f1  project-version  1.2.3-f1-SNAPSHOT
                feature/f2  project-version  1.2.3-f2-SNAPSHOT
                """, "feature/f2");

        assertEquals(Optional.of("1.2.3-f2-SNAPSHOT"), config.getProjectVersion());
    }

    @Test
    @DisplayName("the first matching project-version pin wins")
    void keepsFirstProjectVersionPin() {
        PinConfig config = parse("""
                feature/*   project-version  1.2.3-star-SNAPSHOT
                feature/f1  project-version  1.2.3-f1-SNAPSHOT
                """, "feature/f1");

        assertEquals(Optional.of("1.2.3-star-SNAPSHOT"), config.getProjectVersion());
    }

    @Test
    void rejectsPinsThatCannotBeReverted() {
        PaoException failure =
                assertThrows(PaoException.class, () -> parse("feature/f1  project-version  vf1\n", "feature/f1"));

        assertTrue(failure.getMessage().contains("vf1"), failure.getMessage());
    }

    @Test
    void rejectsUnknownTargets() {
        PaoException failure = assertThrows(PaoException.class,
                () -> parse("feature/f1  something-else  1.2.3-f1-SNAPSHOT\n", "feature/f1"));

        assertTrue(failure.getMessage().contains("something-else"), failure.getMessage());
    }

    @Test
    void rejectsMalformedDependencyTargets() {
        assertThrows(PaoException.class,
                () -> parse("feature/f1  dependency:justone  1.2.3-f1-SNAPSHOT\n", "feature/f1"));
    }

    @Test
    void rejectsLinesWithTooFewColumns() {
        assertThrows(PaoException.class, () -> parse("feature/f1  project-version\n", "feature/f1"));
    }

    @Test
    @DisplayName("a stray space inside a value is reported rather than silently pinning a prefix")
    void rejectsLinesWithTooManyColumns() {
        PaoException failure = assertThrows(PaoException.class,
                () -> parse("feature/f1  project-version  1.2.3 -f1-SNAPSHOT\n", "feature/f1"));

        assertTrue(failure.getMessage().contains("expected 3 columns"), failure.getMessage());
    }

    @Test
    @DisplayName("a trailing comment is not counted as a fourth column")
    void allowsTrailingCommentsAfterTheValue() {
        PinConfig config = parse("feature/f1  project-version  1.2.3-f1-SNAPSHOT  # why this pin exists\n",
                "feature/f1");

        assertEquals(Optional.of("1.2.3-f1-SNAPSHOT"), config.getProjectVersion());
    }

    @Test
    @DisplayName("a malformed row for another branch still fails, so typos surface early")
    void validatesRowsForOtherBranchesToo() {
        assertThrows(PaoException.class, () -> parse("feature/f9  bogus-target  x\n", "feature/f1"));
    }
}
