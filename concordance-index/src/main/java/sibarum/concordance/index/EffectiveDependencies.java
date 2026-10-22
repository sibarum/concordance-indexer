package sibarum.concordance.index;

import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Coordinates;
import sibarum.concordance.project.Dependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of a project's libraries its code really uses, set against which ones its poms declare: the dependencies
 * in effect, as opposed to the ones written down.
 *
 * <p>A library is used when a project file links to it — imports a type it declares, or names one through an
 * import or in full; see {@link Links}. That gives each library one of five standings:
 *
 * <ul>
 *   <li>{@link Status#USED} — declared, and linked to;
 *   <li>{@link Status#UNDECLARED} — linked to, but only reached through another library: the project leans on
 *       something it never asked for, and which an upgrade of the other library can take away;
 *   <li>{@link Status#UNUSED} — declared, and nothing links to it;
 *   <li>{@link Status#UNKNOWN} — declared, but nothing of it was indexed, so there is nothing to judge it by;
 *   <li>{@link Status#TRANSITIVE} — reached through another library and not linked to: there for that library's
 *       sake.
 * </ul>
 *
 * <p>Linking is read from source text, so a library the code reaches only by reflection, a service loader, an
 * annotation processor or a name in a config file shows as unused. Read {@code UNUSED} as a question, not a
 * verdict. A declared {@code pom} dependency has no code of its own and counts as used when anything reached
 * through it is linked to.
 *
 * <p>Working it out is one pass over the files' imports and type names against the libraries' declared types,
 * so an editor keeps it current by working it out again after each {@link ProjectIndex#synchronize}, from the
 * new {@link ProjectIndex#corpus}. A library the corpus does not hold cannot be linked to: index the libraries
 * as well as the project, or every declared one is {@code UNKNOWN}.
 *
 * @param uses every library in the classpath, in its order: nearest first
 */
public record EffectiveDependencies(List<Use> uses) {

    public enum Status { USED, UNDECLARED, UNUSED, UNKNOWN, TRANSITIVE }

    /**
     * One library and where it stands.
     *
     * @param dependency the library
     * @param status     where it stands
     * @param from       the project files that link to it, in the order they were given; empty unless it is used
     */
    public record Use(Dependency dependency, Status status, List<Path> from) {

        public Use {
            from = List.copyOf(from);
        }
    }

    public EffectiveDependencies {
        uses = List.copyOf(uses);
    }

    /**
     * Where each library of {@code classpath} stands for {@code files}, read from {@code corpus}, which should hold
     * the files and the libraries both. Give the files and classpath of one module to learn what that module
     * should declare; the dependencies of a module are its own, not its neighbours'.
     */
    public static EffectiveDependencies of(Corpus corpus, Classpath classpath, Collection<Path> files) {
        // Which library each archive is; the nearer one wins an archive two of them name.
        Map<Path, Dependency> byArchive = new HashMap<>();
        for (Dependency d : classpath.dependencies()) {
            for (Path archive : Arrays.asList(d.sourcesJar(), d.jar())) {
                if (archive != null) byArchive.putIfAbsent(archive.toAbsolutePath().normalize(), d);
            }
        }
        Map<Source, Dependency> library = new LinkedHashMap<>();
        for (Source s : corpus.sources()) {
            Path archive = switch (s) {
                case Source.InArchive a -> a.archive();
                case Source.Decompiled d -> d.archive();
                case Source.OnDisk d -> null;
            };
            Dependency d = archive == null ? null : byArchive.get(archive);
            if (d != null) library.put(s, d);
        }
        List<Source> nearestFirst = new ArrayList<>(library.keySet());
        nearestFirst.sort(Comparator.comparingInt(s -> library.get(s).depth()));   // stable within a depth
        Map<String, Source> declaredBy = Links.declaredBy(corpus, nearestFirst);

        Map<Dependency, Set<Path>> from = new HashMap<>();
        for (Path file : files) {
            Path normalized = file.toAbsolutePath().normalize();
            corpus.slice(Source.of(normalized)).ifPresent(slice -> {
                Set<Source> linked = new LinkedHashSet<>();
                Links.of(slice, declaredBy, linked);
                for (Source s : linked) {
                    from.computeIfAbsent(library.get(s), k -> new LinkedHashSet<>()).add(normalized);
                }
            });
        }
        Set<Dependency> indexed = new HashSet<>(library.values());
        Set<Coordinates> bringsUsed = new HashSet<>();   // the declarations linked libraries are reached through
        from.keySet().forEach(d -> bringsUsed.addAll(d.via().stream().limit(1).toList()));

        List<Use> uses = new ArrayList<>();
        for (Dependency d : classpath.dependencies()) {
            List<Path> linking = List.copyOf(from.getOrDefault(d, Set.of()));
            Status status;
            if (!linking.isEmpty()) {
                status = d.direct() ? Status.USED : Status.UNDECLARED;
            } else if (!d.direct()) {
                status = Status.TRANSITIVE;
            } else if (d.type().equals("pom")) {
                status = bringsUsed.contains(d.coordinates()) ? Status.USED : Status.UNUSED;
            } else {
                status = indexed.contains(d) ? Status.UNUSED : Status.UNKNOWN;
            }
            uses.add(new Use(d, status, linking));
        }
        return new EffectiveDependencies(uses);
    }

    /** The libraries standing as {@code status}, nearest first. */
    public List<Use> with(Status status) {
        return uses.stream().filter(u -> u.status() == status).toList();
    }
}
