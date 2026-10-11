package sibarum.concordance.project;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static sibarum.concordance.project.Xml.childElement;
import static sibarum.concordance.project.Xml.childElements;
import static sibarum.concordance.project.Xml.childText;

/**
 * Effective poms, as far as dependencies are concerned: a pom with its {@code <parent>} chain merged in, its
 * properties interpolated, its {@code dependencyManagement} merged with every BOM it imports, and its own
 * dependencies' missing versions and scopes filled in from that management.
 *
 * <p>A parent is looked for where Maven looks: at {@code relativePath} (by default {@code ../pom.xml}) when the
 * pom there is the parent named, and otherwise in the local repository. A pom that cannot be found is a problem
 * recorded for the caller, not an exception — one missing pom deep in a graph should not cost the rest of it.
 *
 * <p>Not read: profiles, which is the largest gap — a dependency declared only in an active profile is missed;
 * CI-friendly {@code ${revision}} versions supplied on a command line; relocations; and {@code ${env.X}} or
 * Java system properties inside poms.
 */
final class Poms {

    /** A dependency as a pom declares it, interpolated. Absent version and scope are null, not defaulted. */
    record Declared(String groupId, String artifactId, String version, String type, String classifier,
                    String scope, boolean optional, List<String> exclusions, String systemPath, Path declaredIn) {

        Declared {
            type = type == null ? "jar" : type;
            classifier = classifier == null ? "" : classifier;
            exclusions = List.copyOf(exclusions);
        }

        /** What Maven treats as the same dependency: group, artifact, type and classifier, but not version. */
        String key() {
            return groupId + ":" + artifactId + ":" + type + ":" + classifier;
        }

        Declared managedBy(Declared managed) {
            return new Declared(groupId, artifactId,
                    version != null ? version : managed.version,
                    type, classifier,
                    scope != null ? scope : managed.scope,
                    optional,
                    exclusions.isEmpty() ? managed.exclusions : exclusions,
                    systemPath != null ? systemPath : managed.systemPath,
                    declaredIn);
        }
    }

    /** A pom with everything it inherits and imports folded in. */
    record Effective(Path file, Coordinates coordinates, String packaging, List<Declared> dependencies,
                     Map<String, Declared> managed) {

        Effective {
            dependencies = List.copyOf(dependencies);
            managed = Map.copyOf(managed);
        }
    }

    private record Parent(String groupId, String artifactId, String version, String relativePath) {}

    private record Raw(Path file, Element project, String groupId, String artifactId, String version,
                       Parent parent) {

        String effectiveGroupId() {
            return groupId != null ? groupId : parent != null ? parent.groupId : null;
        }

        String effectiveVersion() {
            return version != null ? version : parent != null ? parent.version : null;
        }
    }

    private final LocalRepository repository;
    private final List<Path> reactorPoms;
    private final Set<String> problems;
    private final Map<Path, Optional<Effective>> cache = new HashMap<>();
    private final Set<Path> loading = new HashSet<>();
    private Map<String, Path> reactor;

    Poms(LocalRepository repository, List<Path> reactorPoms, Set<String> problems) {
        this.repository = repository;
        this.reactorPoms = List.copyOf(reactorPoms);
        this.problems = problems;
    }

    /** The effective pom of a file on disk, or empty (with a problem recorded) when it cannot be built. */
    Optional<Effective> fromFile(Path pom) {
        Path file = pom.toAbsolutePath().normalize();
        Optional<Effective> known = cache.get(file);
        if (known != null) return known;
        if (!loading.add(file)) {
            problems.add("a pom inherits or imports itself: " + file);
            return Optional.empty();
        }
        try {
            Optional<Effective> built = build(file);
            cache.put(file, built);
            return built;
        } finally {
            loading.remove(file);
        }
    }

    /** The effective pom of an artifact: a reactor module's own pom if it is one, else the local repository's. */
    Optional<Effective> fromCoordinates(Coordinates c) {
        Optional<Path> sibling = reactorPom(c.groupId(), c.artifactId());
        if (sibling.isPresent()) return fromFile(sibling.get());
        Path pom = repository.pom(c);
        if (!Files.isRegularFile(pom)) {
            problems.add("not in the local repository: " + c + " (no " + pom.getFileName()
                    + "), so what it depends on is unknown");
            return Optional.empty();
        }
        return fromFile(pom);
    }

    /** The pom of the reactor module called {@code groupId:artifactId}, if there is one. */
    Optional<Path> reactorPom(String groupId, String artifactId) {
        if (reactor == null) {
            reactor = new HashMap<>();
            for (Path pom : reactorPoms) {
                try {
                    Raw raw = raw(pom);
                    if (raw.effectiveGroupId() != null) {
                        reactor.putIfAbsent(raw.effectiveGroupId() + ":" + raw.artifactId(), raw.file());
                    }
                } catch (IOException e) {
                    problems.add(e.getMessage());
                }
            }
        }
        return Optional.ofNullable(reactor.get(groupId + ":" + artifactId));
    }

    // --- building -------------------------------------------------------------------------------

    private Optional<Effective> build(Path file) {
        List<Raw> chain = new ArrayList<>();   // the pom first, its furthest ancestor last
        try {
            Raw current = raw(file);
            Set<Path> seen = new HashSet<>();
            while (current != null && seen.add(current.file())) {
                chain.add(current);
                current = parentOf(current);
            }
        } catch (IOException e) {
            problems.add(e.getMessage());
            if (chain.isEmpty()) return Optional.empty();
        }
        Raw leaf = chain.getFirst();
        String groupId = leaf.effectiveGroupId();
        String version = leaf.effectiveVersion();
        if (groupId == null || leaf.artifactId() == null || version == null) {
            problems.add(file + " does not say what it is: groupId, artifactId and version are not all there");
            return Optional.empty();
        }

        Map<String, String> properties = new LinkedHashMap<>();
        for (Raw raw : chain.reversed()) {
            childElement(raw.project(), "properties").ifPresent(props -> {
                for (Element p : childElements(props)) properties.put(p.getTagName(), Xml.text(p));
            });
        }
        for (String prefix : List.of("project.", "pom.", "")) {
            properties.put(prefix + "groupId", groupId);
            properties.put(prefix + "artifactId", leaf.artifactId());
            properties.put(prefix + "version", version);
        }
        if (leaf.parent() != null) {
            properties.put("project.parent.groupId", leaf.parent().groupId());
            properties.put("project.parent.artifactId", leaf.parent().artifactId());
            properties.put("project.parent.version", leaf.parent().version());
            properties.put("parent.version", leaf.parent().version());
        }
        properties.put("project.basedir", file.getParent().toString());
        properties.put("basedir", file.getParent().toString());
        settle(properties, file);

        Coordinates coordinates = new Coordinates(
                MavenProjectReader.interpolate(groupId, properties),
                MavenProjectReader.interpolate(leaf.artifactId(), properties),
                MavenProjectReader.interpolate(version, properties));
        String packaging = childText(leaf.project(), "packaging")
                .map(v -> MavenProjectReader.interpolate(v, properties)).orElse("jar");

        // The nearer pom wins a key both declare: a child overrides what it inherits.
        Map<String, Declared> managed = new LinkedHashMap<>();
        Map<String, Declared> dependencies = new LinkedHashMap<>();
        for (Raw raw : chain) {
            childElement(raw.project(), "dependencyManagement")
                    .flatMap(dm -> childElement(dm, "dependencies"))
                    .ifPresent(ds -> declared(ds, properties, raw.file()).forEach(d -> managed.putIfAbsent(d.key(), d)));
            childElement(raw.project(), "dependencies")
                    .ifPresent(ds -> declared(ds, properties, raw.file()).forEach(d -> dependencies.putIfAbsent(d.key(), d)));
        }
        importBoms(managed);

        List<Declared> filled = new ArrayList<>();
        for (Declared d : dependencies.values()) {
            Declared m = managed.get(d.key());
            filled.add(m == null ? d : d.managedBy(m));
        }
        return Optional.of(new Effective(file, coordinates, packaging, filled, managed));
    }

    /**
     * Replaces each {@code <scope>import</scope>} entry with the management of the BOM it names, keeping what is
     * already managed: the importing pom's own entries win, and an earlier import wins over a later one.
     */
    private void importBoms(Map<String, Declared> managed) {
        List<Declared> imports = managed.values().stream()
                .filter(d -> "import".equals(d.scope()) && "pom".equals(d.type()))
                .toList();
        for (Declared bom : imports) {
            managed.remove(bom.key());
        }
        for (Declared bom : imports) {
            if (bom.version() == null || bom.version().contains("${")) {
                problems.add("cannot import BOM " + bom.groupId() + ":" + bom.artifactId() + ":" + bom.version()
                        + " declared in " + bom.declaredIn() + ": its version is not known");
                continue;
            }
            fromCoordinates(new Coordinates(bom.groupId(), bom.artifactId(), bom.version()))
                    .ifPresent(e -> e.managed().values().forEach(d -> managed.putIfAbsent(d.key(), d)));
        }
    }

    /**
     * Fills properties that refer to other properties, a few levels deep; a cycle is left unresolved, and a
     * property that grows past {@link MavenProjectReader#MAX_EXPANDED} is reported and left part expanded.
     */
    private void settle(Map<String, String> properties, Path file) {
        for (int pass = 0; pass < 8; pass++) {
            boolean moved = false;
            for (Map.Entry<String, String> p : properties.entrySet()) {
                String filled = MavenProjectReader.interpolate(p.getValue(), properties);
                if (!filled.equals(p.getValue())) {
                    p.setValue(filled);
                    moved = true;
                }
            }
            if (!moved) break;
        }
        properties.forEach((name, value) -> {
            if (value.length() > MavenProjectReader.MAX_EXPANDED) {
                problems.add("property " + name + " in " + file + " expands past "
                        + MavenProjectReader.MAX_EXPANDED + " characters; it is left part expanded");
            }
        });
    }

    private static List<Declared> declared(Element dependencies, Map<String, String> properties, Path declaredIn) {
        List<Declared> out = new ArrayList<>();
        for (Element d : childElements(dependencies, "dependency")) {
            String groupId = field(d, "groupId", properties);
            String artifactId = field(d, "artifactId", properties);
            if (groupId == null || artifactId == null) continue;
            List<String> exclusions = new ArrayList<>();
            childElement(d, "exclusions").ifPresent(es -> {
                for (Element e : childElements(es, "exclusion")) {
                    String g = field(e, "groupId", properties);
                    String a = field(e, "artifactId", properties);
                    exclusions.add((g == null ? "*" : g) + ":" + (a == null ? "*" : a));
                }
            });
            out.add(new Declared(groupId, artifactId,
                    field(d, "version", properties),
                    field(d, "type", properties),
                    field(d, "classifier", properties),
                    field(d, "scope", properties),
                    "true".equals(field(d, "optional", properties)),
                    exclusions,
                    field(d, "systemPath", properties),
                    declaredIn));
        }
        return out;
    }

    private static String field(Element parent, String tag, Map<String, String> properties) {
        return childText(parent, tag).map(v -> MavenProjectReader.interpolate(v, properties)).orElse(null);
    }

    // --- raw poms -------------------------------------------------------------------------------

    private static Raw raw(Path file) throws IOException {
        Element project = Xml.parse(file);
        Parent parent = childElement(project, "parent").map(p -> new Parent(
                childText(p, "groupId").orElse(null),
                childText(p, "artifactId").orElse(null),
                childText(p, "version").orElse(null),
                childElement(p, "relativePath").map(Xml::text).orElse(null))).orElse(null);
        return new Raw(file.toAbsolutePath().normalize(), project,
                childText(project, "groupId").orElse(null),
                childText(project, "artifactId").orElse(null),
                childText(project, "version").orElse(null),
                parent);
    }

    /** The parent pom, from {@code relativePath} if the pom there is the one named, else from the repository. */
    private Raw parentOf(Raw child) throws IOException {
        Parent p = child.parent();
        if (p == null) return null;
        if (p.groupId() == null || p.artifactId() == null || p.version() == null) {
            problems.add(child.file() + " names a parent without a groupId, artifactId and version");
            return null;
        }
        String relative = p.relativePath() == null ? "../pom.xml" : p.relativePath();
        if (!relative.isEmpty()) {
            Path candidate = child.file().getParent().resolve(relative).normalize();
            if (Files.isDirectory(candidate)) candidate = candidate.resolve("pom.xml");
            if (Files.isRegularFile(candidate)) {
                Raw found = raw(candidate);
                if (p.artifactId().equals(found.artifactId()) && p.groupId().equals(found.effectiveGroupId())) {
                    return found;
                }
            }
        }
        Path pom = repository.pom(new Coordinates(p.groupId(), p.artifactId(), p.version()));
        if (Files.isRegularFile(pom)) return raw(pom);
        problems.add("parent " + p.groupId() + ":" + p.artifactId() + ":" + p.version() + " of " + child.file()
                + " is neither at its relativePath nor in the local repository; what it would add is missing");
        return null;
    }
}
