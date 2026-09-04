package com.jardoapps.pao.pom;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pom.xml held as raw text plus an index of its elements.
 *
 * <p>Edits are applied as targeted splices into the original source, so
 * formatting, comments, entities and attribute quoting outside the edited
 * values are preserved byte for byte. This is deliberately not a DOM: marshalling
 * a parsed model back out would reformat the whole file.
 */
public final class PomDocument {

    /** A dependency (or dependencyManagement / plugin dependency) entry. */
    public record DependencyEntry(String groupId, String artifactId, XmlElement versionElement) {

        public String key() {
            return groupId + ":" + artifactId;
        }
    }

    private record Edit(int start, int end, String replacement) {
    }

    /** {@code encoding="..."} in the XML prolog, if the document declares one. */
    private static final Pattern PROLOG_ENCODING =
            Pattern.compile("\\A<\\?xml\\s[^>]*?encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    private final Path path;
    private final String source;
    private final Charset charset;
    private final List<XmlElement> elements;
    private final List<Edit> edits = new ArrayList<>();

    private PomDocument(Path path, String source, Charset charset, List<XmlElement> elements) {
        this.path = path;
        this.source = source;
        this.charset = charset;
        this.elements = elements;
    }

    public static PomDocument load(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            Charset charset = declaredCharset(bytes, path);
            return parse(path, new String(bytes, charset), charset);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    public static PomDocument parse(Path path, String source) {
        return parse(path, source, StandardCharsets.UTF_8);
    }

    public static PomDocument parse(Path path, String source, Charset charset) {
        return new PomDocument(path, source, charset, scan(source, path));
    }

    /**
     * The encoding named in the XML prolog, defaulting to UTF-8. The same charset is
     * used again on the way out, so the bytes the plugin did not touch survive
     * unchanged.
     *
     * <p>The prolog itself is ASCII by definition, and ISO-8859-1 maps every byte to a
     * character without ever failing, so it can be read out of the raw bytes before the
     * real encoding is known. That covers the byte-oriented encodings a pom.xml
     * realistically uses; a UTF-16 document, whose prolog is not byte-per-character,
     * falls through to the UTF-8 default as it did before.
     */
    private static Charset declaredCharset(byte[] bytes, Path path) {
        String prolog = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        Matcher matcher = PROLOG_ENCODING.matcher(prolog);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        String name = matcher.group(1);
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            throw new IllegalArgumentException(path + ": unsupported encoding '" + name + "' in the XML prolog", e);
        }
    }

    // --- Scanning ---------------------------------------------------------

    private static List<XmlElement> scan(String s, Path path) {
        List<XmlElement> found = new ArrayList<>();
        Deque<Integer> open = new ArrayDeque<>();
        int i = 0;

        while ((i = s.indexOf('<', i)) >= 0) {
            if (s.startsWith("<!--", i)) {
                i = requireEnd(s.indexOf("-->", i), path, "unterminated comment") + 3;
            } else if (s.startsWith("<![CDATA[", i)) {
                i = requireEnd(s.indexOf("]]>", i), path, "unterminated CDATA section") + 3;
            } else if (s.startsWith("<?", i)) {
                i = requireEnd(s.indexOf("?>", i), path, "unterminated processing instruction") + 2;
            } else if (s.startsWith("<!", i)) {
                i = requireEnd(s.indexOf('>', i), path, "unterminated declaration") + 1;
            } else if (s.startsWith("</", i)) {
                int gt = requireEnd(s.indexOf('>', i), path, "unterminated end tag");
                if (open.isEmpty()) {
                    throw new IllegalArgumentException(path + ": end tag without matching start tag at offset " + i);
                }
                found.get(open.pop()).closeAt(i, s);
                i = gt + 1;
            } else {
                int gt = findTagEnd(s, i, path);
                int nameEnd = i + 1;
                while (nameEnd < gt && !isNameEnd(s.charAt(nameEnd))) {
                    nameEnd++;
                }
                String name = s.substring(i + 1, nameEnd);
                XmlElement element = new XmlElement(name, found.size(), open.isEmpty() ? -1 : open.peek(), gt + 1);
                found.add(element);
                if (s.charAt(gt - 1) == '/') {
                    element.closeAt(gt + 1, s);
                } else {
                    open.push(element.getIndex());
                }
                i = gt + 1;
            }
        }

        if (!open.isEmpty()) {
            throw new IllegalArgumentException(path + ": unclosed element <" + found.get(open.peek()).getName() + ">");
        }
        return found;
    }

    private static int requireEnd(int position, Path path, String what) {
        if (position < 0) {
            throw new IllegalArgumentException(path + ": " + what);
        }
        return position;
    }

    private static boolean isNameEnd(char c) {
        return Character.isWhitespace(c) || c == '/' || c == '>';
    }

    /** Finds the '>' closing a start tag, ignoring any inside quoted attribute values. */
    private static int findTagEnd(String s, int start, Path path) {
        char quote = 0;
        for (int j = start + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return j;
            }
        }
        throw new IllegalArgumentException(path + ": unterminated start tag at offset " + start);
    }

    // --- Queries ----------------------------------------------------------

    public Path getPath() {
        return path;
    }

    public String valueOf(XmlElement element) {
        return source.substring(element.getValueStart(), element.getValueEnd());
    }

    private boolean hasPath(XmlElement element, String... namesFromRoot) {
        XmlElement current = element;
        for (int i = namesFromRoot.length - 1; i >= 0; i--) {
            if (current == null || !current.getName().equals(namesFromRoot[i])) {
                return false;
            }
            current = current.getParentIndex() < 0 ? null : elements.get(current.getParentIndex());
        }
        return current == null;
    }

    private Optional<XmlElement> child(XmlElement parent, String name) {
        return elements.stream()
                .filter(e -> e.getParentIndex() == parent.getIndex() && e.getName().equals(name))
                .findFirst();
    }

    private Optional<XmlElement> firstWithPath(String... namesFromRoot) {
        return elements.stream().filter(e -> hasPath(e, namesFromRoot)).findFirst();
    }

    /** The {@code <version>} that is a direct child of {@code <project>}, if the project declares one. */
    public Optional<XmlElement> projectVersion() {
        return firstWithPath("project", "version");
    }

    /** The {@code <version>} inside {@code <project><parent>}, if there is a parent. */
    public Optional<XmlElement> parentVersion() {
        return firstWithPath("project", "parent", "version");
    }

    public Optional<String> projectArtifactId() {
        return firstWithPath("project", "artifactId").map(this::valueOf);
    }

    public Optional<String> projectGroupId() {
        return firstWithPath("project", "groupId").map(this::valueOf);
    }

    public Optional<String> parentGroupId() {
        return firstWithPath("project", "parent", "groupId").map(this::valueOf);
    }

    public Optional<String> parentArtifactId() {
        return firstWithPath("project", "parent", "artifactId").map(this::valueOf);
    }

    /** Module names declared in {@code <project><modules>}. */
    public List<String> modules() {
        return elements.stream()
                .filter(e -> hasPath(e, "project", "modules", "module"))
                .map(this::valueOf)
                .toList();
    }

    /**
     * Every {@code <dependency>} in the file that declares a version, wherever it
     * sits: plain dependencies, dependencyManagement, profiles and plugin
     * dependencies are all included.
     */
    public List<DependencyEntry> dependencies() {
        List<DependencyEntry> result = new ArrayList<>();
        for (XmlElement element : elements) {
            if (!element.getName().equals("dependency")) {
                continue;
            }
            Optional<XmlElement> version = child(element, "version");
            if (version.isEmpty()) {
                continue;
            }
            String groupId = child(element, "groupId").map(this::valueOf).orElse("");
            String artifactId = child(element, "artifactId").map(this::valueOf).orElse("");
            result.add(new DependencyEntry(groupId, artifactId, version.get()));
        }
        return result;
    }

    /** Properties declared in {@code <project><properties>}, in document order. */
    public Map<String, XmlElement> properties() {
        Map<String, XmlElement> result = new LinkedHashMap<>();
        for (XmlElement element : elements) {
            if (hasPath(element, "project", "properties", element.getName())) {
                result.putIfAbsent(element.getName(), element);
            }
        }
        return result;
    }

    /** Every {@code <version>} element in the file, in document order. */
    public List<XmlElement> allVersionElements() {
        return elements.stream().filter(e -> e.getName().equals("version")).toList();
    }

    // --- Editing ----------------------------------------------------------

    /**
     * Queues a replacement of an element's text value. Repeating the same replacement
     * is a no-op, which happens when several coordinates resolve to one shared
     * property; asking for two different values for one element is a bug and fails.
     */
    public void setValue(XmlElement element, String newValue) {
        Edit edit = new Edit(element.getValueStart(), element.getValueEnd(), newValue);
        for (Edit existing : edits) {
            if (existing.start() == edit.start() && existing.end() == edit.end()) {
                if (!existing.replacement().equals(newValue)) {
                    throw new IllegalStateException(path + ": conflicting replacements for <" + element.getName()
                            + ">: '" + existing.replacement() + "' and '" + newValue + "'");
                }
                return;
            }
        }
        edits.add(edit);
    }

    public boolean isModified() {
        return !edits.isEmpty();
    }

    /** Renders the document with all queued edits applied. */
    public String render() {
        if (edits.isEmpty()) {
            return source;
        }
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start));
        StringBuilder out = new StringBuilder(source.length() + 64);
        int cursor = 0;
        for (Edit edit : ordered) {
            if (edit.start() < cursor) {
                throw new IllegalStateException(path + ": overlapping edits at offset " + edit.start());
            }
            out.append(source, cursor, edit.start()).append(edit.replacement());
            cursor = edit.end();
        }
        out.append(source, cursor, source.length());
        return out.toString();
    }

    /** Writes the edited document back to disk. Returns true if anything was written. */
    public boolean save() {
        if (edits.isEmpty()) {
            return false;
        }
        String rendered = render();
        if (rendered.equals(source)) {
            return false;
        }
        try {
            Files.writeString(path, rendered, charset);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + path, e);
        }
        return true;
    }
}
