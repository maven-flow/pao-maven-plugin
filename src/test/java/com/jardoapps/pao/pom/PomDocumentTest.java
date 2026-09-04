package com.jardoapps.pao.pom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PomDocumentTest {

    private static PomDocument parse(String xml) {
        return PomDocument.parse(Path.of("pom.xml"), xml);
    }

    @Test
    @DisplayName("everything outside the edited value is preserved exactly")
    void preservesSurroundingText() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- a leading comment -->
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>   <!-- trailing comment -->
                </project>
                """;
        PomDocument document = parse(xml);

        document.setValue(document.projectVersion().orElseThrow(), "1.0.0-feature-x-SNAPSHOT");

        assertEquals(xml.replace("1.0.0-SNAPSHOT", "1.0.0-feature-x-SNAPSHOT"), document.render());
    }

    @Test
    @DisplayName("a version inside a comment is not an element and is never rewritten")
    void ignoresVersionsInsideComments() {
        String xml = """
                <project>
                    <artifactId>my-app</artifactId>
                    <!-- was <version>0.9.0-SNAPSHOT</version> before the bump -->
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals(1, document.allVersionElements().size());
        assertEquals("1.0.0-SNAPSHOT", document.valueOf(document.projectVersion().orElseThrow()));
    }

    @Test
    void ignoresMarkupInsideCdata() {
        String xml = """
                <project>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <description><![CDATA[Use <version>9.9.9</version> in your pom]]></description>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals(1, document.allVersionElements().size());
    }

    @Test
    @DisplayName("the project version is distinguished from the parent's")
    void distinguishesProjectAndParentVersion() {
        String xml = """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>my-parent</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals("1.0.0-SNAPSHOT", document.valueOf(document.projectVersion().orElseThrow()));
        assertEquals("2.0.0-SNAPSHOT", document.valueOf(document.parentVersion().orElseThrow()));
    }

    @Test
    @DisplayName("a module that inherits its version has no project version element")
    void reportsNoProjectVersionWhenInherited() {
        String xml = """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>my-parent</artifactId>
                        <version>2.0.0-SNAPSHOT</version>
                    </parent>
                    <artifactId>core</artifactId>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals(Optional.empty(), document.projectVersion());
        assertTrue(document.parentVersion().isPresent());
    }

    @Test
    @DisplayName("dependencyManagement and plugin dependencies are found as well")
    void findsDependenciesEverywhere() {
        String xml = """
                <project>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>managed</artifactId>
                                <version>1.0.0-SNAPSHOT</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>direct</artifactId>
                            <version>2.0.0-SNAPSHOT</version>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>no-version</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>some-plugin</artifactId>
                                <dependencies>
                                    <dependency>
                                        <groupId>com.example</groupId>
                                        <artifactId>plugin-dep</artifactId>
                                        <version>3.0.0-SNAPSHOT</version>
                                    </dependency>
                                </dependencies>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals(List.of("com.example:managed", "com.example:direct", "com.example:plugin-dep"),
                document.dependencies().stream().map(PomDocument.DependencyEntry::key).toList());
    }

    @Test
    void readsProperties() {
        String xml = """
                <project>
                    <artifactId>my-app</artifactId>
                    <version>${revision}</version>
                    <properties>
                        <revision>1.0.0-SNAPSHOT</revision>
                        <java.version>17</java.version>
                    </properties>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals(List.of("revision", "java.version"), List.copyOf(document.properties().keySet()));
        assertEquals("1.0.0-SNAPSHOT", document.valueOf(document.properties().get("revision")));
    }

    @Test
    void readsModules() {
        String xml = """
                <project>
                    <artifactId>my-parent</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <modules>
                        <module>core</module>
                        <module>web</module>
                    </modules>
                </project>
                """;

        assertEquals(List.of("core", "web"), parse(xml).modules());
    }

    @Test
    @DisplayName("attributes containing '>' do not confuse the scanner")
    void handlesAngleBracketsInAttributes() {
        String xml = """
                <project>
                    <artifactId note="a > b">my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """;
        PomDocument document = parse(xml);

        assertEquals("my-app", document.projectArtifactId().orElseThrow());
        assertEquals("1.0.0-SNAPSHOT", document.valueOf(document.projectVersion().orElseThrow()));
    }

    @Test
    @DisplayName("self-closing elements do not unbalance the element stack")
    void handlesSelfClosingElements() {
        String xml = """
                <project>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <properties/>
                </project>
                """;

        assertEquals("1.0.0-SNAPSHOT", parse(xml).valueOf(parse(xml).projectVersion().orElseThrow()));
    }

    @Test
    void reportsNoChangeWhenNothingWasEdited() {
        PomDocument document = parse("""
                <project>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                </project>
                """);

        assertFalse(document.isModified());
    }
}
