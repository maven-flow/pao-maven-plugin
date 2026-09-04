package com.jardoapps.pao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Multi-module projects carry the shared version in each module's
 * {@code <parent><version>}, and modules may name each other as dependencies. If
 * those references are not moved along with the aggregator's version, a module
 * points at a parent that no longer exists, or resolves a sibling from the
 * repository - the shared snapshot some other branch published.
 */
class MultiModuleTest extends RunnerTestSupport {

    @TempDir
    Path project;

    private void writeReactor() {
        writeFixture(project, "multimodule/pom.xml", "pom.xml");
        writeFixture(project.resolve("core"), "multimodule/core/pom.xml", "pom.xml");
        writeFixture(project.resolve("app"), "multimodule/app/pom.xml", "pom.xml");
    }

    private void writeBranchVersionedReactor() {
        writeFixture(project, "multimodule-expected/pom.xml", "pom.xml");
        writeFixture(project.resolve("core"), "multimodule-expected/core/pom.xml", "pom.xml");
        writeFixture(project.resolve("app"), "multimodule-expected/app/pom.xml", "pom.xml");
    }

    @Test
    @DisplayName("enforcing a branch version updates module parent references too")
    void updatesParentReferencesOnEnforce() {
        writeReactor();

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertMatchesFixture(project.resolve("pom.xml"), "multimodule-expected/pom.xml");
        assertMatchesFixture(project.resolve("core/pom.xml"), "multimodule-expected/core/pom.xml");
        assertMatchesFixture(project.resolve("app/pom.xml"), "multimodule-expected/app/pom.xml");
    }

    @Test
    @DisplayName("enforcing a branch version moves a dependency on a sibling module too")
    void updatesSiblingDependencyOnEnforce() {
        writeReactor();

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        // Left at 1.2.3-SNAPSHOT, app would resolve core from the repository - the
        // branch-agnostic snapshot another branch published - instead of the reactor.
        assertTrue(read(project.resolve("app/pom.xml"))
                .contains("<artifactId>core</artifactId>\n            <version>1.2.3-feature-my-feature-SNAPSHOT</version>"));
    }

    @Test
    @DisplayName("stripping a branch version on a core branch updates module parent references too")
    void updatesParentReferencesOnRemoval() {
        writeBranchVersionedReactor();

        run(project, settings -> settings.setBranchName("main"));

        assertMatchesFixture(project.resolve("pom.xml"), "multimodule/pom.xml");
        assertMatchesFixture(project.resolve("core/pom.xml"), "multimodule/core/pom.xml");
        assertMatchesFixture(project.resolve("app/pom.xml"), "multimodule/app/pom.xml");
    }

    @Test
    @DisplayName("a module's unrelated dependency version is left alone")
    void leavesUnrelatedDependencyAlone() {
        writeReactor();

        run(project, settings -> settings.setBranchName("feature/my-feature"));

        assertTrue(read(project.resolve("core/pom.xml")).contains("<version>9.9.9-SNAPSHOT</version>"));
        assertTrue(read(project.resolve("app/pom.xml")).contains("<version>9.9.9-SNAPSHOT</version>"));
    }

    @Test
    @DisplayName("the reactor is discovered through <modules>")
    void readsWholeReactor() {
        writeReactor();

        assertEquals(3, com.jardoapps.pao.pom.PomReader.readReactor(project.resolve("pom.xml")).size());
    }
}
