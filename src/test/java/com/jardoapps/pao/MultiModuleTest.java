package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-module projects carry the shared version in each module's
 * {@code <parent><version>}. If those references are not moved along with the
 * aggregator's version, every module points at a parent that no longer exists.
 */
class MultiModuleTest extends RunnerTestSupport {

    @TempDir
    Path project;

    private void writeReactor() {
        writeFixture(project, "multimodule/pom.xml", "pom.xml");
        writeFixture(project.resolve("core"), "multimodule/core/pom.xml", "pom.xml");
    }

    @Test
    @DisplayName("enforcing a branch version updates module parent references too")
    void updatesParentReferencesOnEnforce() {
        writeReactor();

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(project.resolve("pom.xml"), "multimodule-expected/pom.xml");
        assertMatchesFixture(project.resolve("core/pom.xml"), "multimodule-expected/core/pom.xml");
    }

    @Test
    @DisplayName("stripping a branch version on a core branch updates module parent references too")
    void updatesParentReferencesOnRemoval() {
        writeFixture(project, "multimodule-expected/pom.xml", "pom.xml");
        writeFixture(project.resolve("core"), "multimodule-expected/core/pom.xml", "pom.xml");

        run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(project.resolve("pom.xml"), "multimodule/pom.xml");
        assertMatchesFixture(project.resolve("core/pom.xml"), "multimodule/core/pom.xml");
    }

    @Test
    @DisplayName("a module's unrelated dependency version is left alone")
    void leavesUnrelatedDependencyAlone() {
        writeReactor();

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertTrue(read(project.resolve("core/pom.xml")).contains("<version>9.9.9-SNAPSHOT</version>"));
    }

    @Test
    @DisplayName("the reactor is discovered through <modules>")
    void readsWholeReactor() {
        writeReactor();

        assertEquals(2, com.jardoapps.pao.pom.PomReader.readReactor(project.resolve("pom.xml")).size());
    }
}
