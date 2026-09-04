package com.jardoapps.pao.pom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.jardoapps.pao.BranchVersions;
import com.jardoapps.pao.PaoException;
import com.jardoapps.pao.ProjectModel;

/**
 * Builds a reactor model straight from pom.xml files by following {@code <modules>}.
 *
 * <p>The plugin itself uses Maven's own resolved reactor; this reader exists so the
 * same logic can be exercised against plain files in tests.
 */
public final class PomReader {

    private PomReader() {
    }

    /** Reads the project at {@code pomFile} and, recursively, all of its modules. */
    public static List<ProjectModel> readReactor(Path pomFile) {
        List<ProjectModel> reactor = new ArrayList<>();
        collect(pomFile.toAbsolutePath().normalize(), reactor);
        return reactor;
    }

    private static void collect(Path pomFile, List<ProjectModel> reactor) {
        if (!Files.isRegularFile(pomFile)) {
            throw new PaoException("POM file not found: " + pomFile);
        }
        PomDocument document = PomDocument.load(pomFile);
        reactor.add(toModel(pomFile, document));

        Path baseDir = pomFile.getParent();
        for (String module : document.modules()) {
            Path modulePath = baseDir.resolve(module).normalize();
            if (Files.isDirectory(modulePath)) {
                modulePath = modulePath.resolve("pom.xml");
            }
            collect(modulePath, reactor);
        }
    }

    private static ProjectModel toModel(Path pomFile, PomDocument document) {
        String parentGroupId = document.parentGroupId().orElse(null);
        String parentArtifactId = document.parentArtifactId().orElse(null);
        String parentVersion = document.parentVersion().map(document::valueOf).orElse(null);

        String groupId = document.projectGroupId().orElse(parentGroupId);
        String artifactId = document.projectArtifactId()
                .orElseThrow(() -> new PaoException(pomFile + ": no <artifactId> found"));
        String version = document.projectVersion().map(document::valueOf).orElse(parentVersion);

        if (version == null) {
            throw new PaoException(pomFile + ": no project version and no parent version found");
        }

        return new ProjectModel(pomFile, groupId, artifactId, interpolate(document, version), parentGroupId,
                parentArtifactId, interpolate(document, parentVersion));
    }

    /**
     * Resolves a {@code ${...}} version against the pom's own properties, so the model
     * carries the same effective version Maven would report.
     */
    private static String interpolate(PomDocument document, String value) {
        if (value == null) {
            return null;
        }
        return BranchVersions.propertyReference(value)
                .map(name -> document.properties().get(name))
                .map(document::valueOf)
                .orElse(value);
    }
}
