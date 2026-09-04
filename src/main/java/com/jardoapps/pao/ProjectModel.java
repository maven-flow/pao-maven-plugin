package com.jardoapps.pao;

import java.nio.file.Path;

/** The coordinates of one reactor project, together with the pom.xml they came from. */
public record ProjectModel(
        Path pomFile,
        String groupId,
        String artifactId,
        String version,
        String parentGroupId,
        String parentArtifactId,
        String parentVersion) {

    public String key() {
        return groupId + ":" + artifactId;
    }

    public boolean hasParent() {
        return parentArtifactId != null;
    }

    public String parentKey() {
        return hasParent() ? parentGroupId + ":" + parentArtifactId : null;
    }
}
