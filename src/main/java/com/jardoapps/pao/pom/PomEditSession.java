package com.jardoapps.pao.pom;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.plugin.logging.Log;

import com.jardoapps.pao.BranchVersions;
import com.jardoapps.pao.ProjectModel;

/**
 * A batch of pom.xml edits that are written out together.
 *
 * <p>Version elements are frequently written as {@code ${some.version}} rather than a
 * literal. This session resolves such references to the {@code <properties>} entry
 * that defines them - anywhere in the reactor - and edits that instead, so the
 * indirection survives the rewrite.
 */
public final class PomEditSession {

    private final Map<Path, PomDocument> documents = new LinkedHashMap<>();
    private final Log log;

    public PomEditSession(List<ProjectModel> reactor, Log log) {
        this.log = log;
        for (ProjectModel project : reactor) {
            documents.computeIfAbsent(project.pomFile(), PomDocument::load);
        }
    }

    public PomDocument document(Path pomFile) {
        PomDocument document = documents.get(pomFile);
        if (document == null) {
            throw new IllegalArgumentException("Not part of this session: " + pomFile);
        }
        return document;
    }

    public List<PomDocument> documents() {
        return List.copyOf(documents.values());
    }

    /**
     * Sets an element's version, following a {@code ${...}} reference to the property
     * that defines it.
     *
     * @param expectedCurrent the value the element must currently resolve to, or null to overwrite regardless
     * @return true if an edit was queued
     */
    public boolean setVersion(PomDocument document, XmlElement element, String expectedCurrent, String newValue) {
        String raw = document.valueOf(element);

        Optional<String> property = BranchVersions.propertyReference(raw);
        if (property.isPresent()) {
            return setProperty(document, property.get(), expectedCurrent, newValue, element);
        }

        if (expectedCurrent != null && !raw.equals(expectedCurrent)) {
            return false;
        }
        if (raw.equals(newValue)) {
            return false;
        }
        document.setValue(element, newValue);
        return true;
    }

    private boolean setProperty(PomDocument origin, String propertyName, String expectedCurrent, String newValue,
            XmlElement reference) {
        // Look in the pom that made the reference first, then anywhere else in the
        // reactor, since parent poms commonly hold the shared property.
        List<PomDocument> searchOrder = new ArrayList<>();
        searchOrder.add(origin);
        documents.values().stream().filter(d -> d != origin).forEach(searchOrder::add);

        for (PomDocument candidate : searchOrder) {
            XmlElement propertyElement = candidate.properties().get(propertyName);
            if (propertyElement == null) {
                continue;
            }
            String current = candidate.valueOf(propertyElement);
            if (expectedCurrent != null && !current.equals(expectedCurrent)) {
                continue;
            }
            if (current.equals(newValue)) {
                return false;
            }
            log.info("  " + origin.getPath().getFileName() + ": <" + reference.getName() + "> is ${" + propertyName
                    + "}, updating the property in " + candidate.getPath());
            candidate.setValue(propertyElement, newValue);
            return true;
        }

        log.warn("Cannot update <" + reference.getName() + ">${" + propertyName + "}</" + reference.getName() + "> in "
                + origin.getPath() + ": property '" + propertyName
                + "' is not defined in the reactor. Leaving it unchanged.");
        return false;
    }

    /** Writes every modified document. Returns the files that changed on disk. */
    public List<Path> save() {
        List<Path> written = new ArrayList<>();
        for (PomDocument document : documents.values()) {
            if (document.save()) {
                written.add(document.getPath());
            }
        }
        return written;
    }
}
