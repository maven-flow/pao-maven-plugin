package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Versions written as {@code ${property}} - the CI-friendly {@code ${revision}} style
 * among them - must be followed to the property that defines them.
 */
class PropertyVersionTest extends RunnerTestSupport {

    @TempDir
    Path project;

    @Test
    @DisplayName("a ${revision} project version is applied to the property")
    void enforcesThroughRevisionProperty() {
        Path pom = writePom(project, "sample-pom-revision.xml");

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(pom, "expected-revision-enforced.xml");
    }

    @Test
    @DisplayName("a core branch strips the branch suffix out of the property")
    void removesThroughRevisionProperty() {
        Path pom = writeFixture(project, "expected-revision-enforced.xml", "pom.xml");

        run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(pom, "sample-pom-revision.xml");
    }

    @Test
    @DisplayName("a pinned dependency whose version is a property updates the property")
    void pinsThroughDependencyProperty() {
        Path pom = writePom(project, "sample-pom-revision.xml");
        writeFile(project, ".prevent-overwrites.conf", """
                feature/f1  dependency:com.example:shared-lib  4.5.6-f1-SNAPSHOT
                """);

        run(project, settings -> settings
                .setBranchName("feature/f1")
                .setEnforceBranchVersion(false));

        String result = read(pom);
        assertTrue(result.contains("<shared-lib.version>4.5.6-f1-SNAPSHOT</shared-lib.version>"), result);
        assertTrue(result.contains("<version>${shared-lib.version}</version>"), result);
    }

    @Test
    @DisplayName("a property version that is not defined anywhere is reported and left alone")
    void leavesUnknownPropertyAlone() {
        Path pom = writeFile(project, "pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.2.3-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>d1</artifactId>
                            <version>${undefined.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        String before = read(pom);
        writeFile(project, ".prevent-overwrites.conf", """
                feature/f1  dependency:com.example:d1  1.0.0-f1-SNAPSHOT
                """);

        run(project, settings -> settings
                .setBranchName("feature/f1")
                .setEnforceBranchVersion(false));

        assertEquals(before, read(pom));
        assertTrue(git.getCommits().isEmpty());
    }
}
