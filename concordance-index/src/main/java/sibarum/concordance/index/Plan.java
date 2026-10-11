package sibarum.concordance.index;

import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Dependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order a search widens in, by recency and adjacency. No query can avoid the libraries — a project's code
 * names them constantly, and a search that hid them would be wrong exactly when they are the answer — so instead
 * of being filtered out they are searched last, and the answers nearest the person asking come first:
 *
 * <ol>
 *   <li><b>open</b> — the files open or recently opened in the editor, most recent first;
 *   <li><b>project</b> — the rest of the project's source;
 *   <li><b>linked libraries</b> — the library sources a project file links to, those the open files link to
 *       first;
 *   <li><b>other libraries</b> — the rest, nearest first by depth in the classpath.
 * </ol>
 *
 * <p>A file links to a library source when it imports a type declared there, or names one through an import, a
 * wildcard import, or its fully qualified name. Imports are the one place a file says exactly which type it means,
 * so this is the reference graph read where it is precise: a file that merely uses a simple name another library
 * also happens to declare does not link to that library unless an import says so.
 *
 * <p>A stage with nothing in it is left out, and no source is in two stages.
 */
public final class Plan {

    /** One step of a search: a name to show while it runs, and the sources it covers, in order. */
    public record Stage(String name, List<Source> sources) {

        public Stage {
            sources = List.copyOf(sources);
        }

        @Override
        public String toString() {
            return name + " (" + sources.size() + " sources)";
        }
    }

    public static final String OPEN = "open";
    public static final String PROJECT = "project";
    public static final String LINKED_LIBRARIES = "linked libraries";
    public static final String OTHER_LIBRARIES = "other libraries";

    private final List<Source> recent;
    private final Map<Path, Integer> depths;

    private Plan(List<Source> recent, Map<Path, Integer> depths) {
        this.recent = List.copyOf(recent);
        this.depths = Map.copyOf(depths);
    }

    /**
     * A plan that searches {@code recent} first, then the project, then the libraries it links to, then the rest
     * by their depth in {@code classpath}. A sources jar the classpath does not know — one named by hand — counts
     * as depth 1, since somebody asked for it by name.
     *
     * @param recent    the editor's open and recently opened sources, most recent first
     * @param classpath the project's resolved libraries, or {@code null} when there are none
     */
    public static Plan of(List<Source> recent, Classpath classpath) {
        Map<Path, Integer> depths = new HashMap<>();
        if (classpath != null) {
            for (Dependency d : classpath.dependencies()) {
                if (d.sourcesJar() != null) {
                    depths.merge(d.sourcesJar().toAbsolutePath().normalize(), d.depth(), Math::min);
                }
            }
        }
        return new Plan(recent, depths);
    }

    /** A plan with no recent files and no classpath. */
    public static Plan everything() {
        return of(List.of(), null);
    }

    /** The stages this plan makes of {@code corpus}, empty ones left out. */
    public List<Stage> stages(Corpus corpus) {
        Set<Source> taken = new LinkedHashSet<>();
        List<Source> open = new ArrayList<>();
        for (Source s : recent) {
            if (corpus.slice(s).isPresent() && taken.add(s)) open.add(s);
        }
        List<Source> project = new ArrayList<>();
        List<Source> libraries = new ArrayList<>();
        for (Source s : corpus.sources()) {
            if (taken.contains(s)) continue;
            (s instanceof Source.OnDisk ? project : libraries).add(s);
        }
        // Stable: within a depth, the order the index holds them, which is the order the classpath gave the jars.
        libraries.sort(Comparator.comparingInt(this::depthOf));

        // Which library source declares each type; the nearer jar wins a type two of them declare.
        Map<String, Source> declaredBy = new HashMap<>();
        for (Source s : libraries) {
            corpus.slice(s).ifPresent(slice -> slice.symbols().stream()
                    .filter(sym -> sym.kind().isType())
                    .forEach(sym -> declaredBy.putIfAbsent(sym.qualified(), s)));
        }

        Set<Source> linked = new LinkedHashSet<>();
        for (List<Source> from : List.of(open, project)) {
            for (Source s : from) {
                corpus.slice(s).ifPresent(slice -> linksOf(slice, declaredBy, linked));
            }
        }
        linked.removeAll(taken);
        List<Source> other = libraries.stream().filter(s -> !linked.contains(s)).toList();

        List<Stage> stages = new ArrayList<>();
        for (Stage stage : List.of(new Stage(OPEN, open), new Stage(PROJECT, project),
                new Stage(LINKED_LIBRARIES, List.copyOf(linked)), new Stage(OTHER_LIBRARIES, other))) {
            if (!stage.sources().isEmpty()) stages.add(stage);
        }
        return stages;
    }

    /**
     * Adds to {@code out}, in the order the file reaches them, the library sources one project file links to:
     * through its imports, and through type names it writes, read against those imports.
     */
    private static void linksOf(Corpus.Slice slice, Map<String, Source> declaredBy, Set<Source> out) {
        Map<String, String> single = new LinkedHashMap<>();   // simple name -> what it was imported as
        List<String> wildcards = new ArrayList<>();
        for (Reference r : slice.references()) {
            if (r.kind() != Reference.Kind.IMPORT) continue;
            String target = r.target();
            if (target.endsWith(".*")) {
                String scope = target.substring(0, target.length() - 2);
                wildcards.add(scope);
                link(scope, declaredBy, out);   // import static a.b.C.* links to C
            } else {
                single.put(r.name(), target);
                // import a.b.C, or import static a.b.C.m: the type is the target or the target's owner.
                if (!link(target, declaredBy, out)) {
                    int dot = target.lastIndexOf('.');
                    if (dot > 0) link(target.substring(0, dot), declaredBy, out);
                }
            }
        }
        for (Reference r : slice.references()) {
            String written = switch (r.kind()) {
                case TYPE_USE, EXTENDS, IMPLEMENTS -> r.target();
                case CONSTRUCT -> r.name();
                default -> null;
            };
            if (written == null) continue;
            if (link(written, declaredBy, out)) continue;   // written fully qualified
            int dot = written.indexOf('.');
            String first = dot < 0 ? written : written.substring(0, dot);
            String rest = dot < 0 ? "" : written.substring(dot);
            String imported = single.get(first);
            if (imported != null && link(imported + rest, declaredBy, out)) continue;
            for (String scope : wildcards) {
                if (link(scope + "." + written, declaredBy, out)) break;
            }
        }
    }

    private static boolean link(String qualified, Map<String, Source> declaredBy, Set<Source> out) {
        Source s = declaredBy.get(qualified);
        if (s == null) return false;
        out.add(s);
        return true;
    }

    private int depthOf(Source source) {
        return source instanceof Source.InArchive a ? depths.getOrDefault(a.archive(), 1) : 0;
    }
}
