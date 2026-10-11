package sibarum.concordance.index;

import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Dependency;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order a search widens in. No query can avoid the libraries — a project's code names them constantly, and a
 * search that hid them would be wrong exactly when they are the answer — so instead of being filtered out they are
 * searched last, and the answers nearest the person asking come first:
 *
 * <ol>
 *   <li><b>open</b> — the files open or recently opened in the editor, most recent first;
 *   <li><b>project</b> — the rest of the project's source;
 *   <li><b>direct libraries</b> — the libraries the project declares itself;
 *   <li><b>other libraries</b> — everything those bring in, nearest first.
 * </ol>
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
    public static final String DIRECT_LIBRARIES = "direct libraries";
    public static final String OTHER_LIBRARIES = "other libraries";

    private final List<Source> recent;
    private final Map<Path, Integer> depths;

    private Plan(List<Source> recent, Map<Path, Integer> depths) {
        this.recent = List.copyOf(recent);
        this.depths = Map.copyOf(depths);
    }

    /**
     * A plan that searches {@code recent} first, then the project, then libraries by their depth in
     * {@code classpath}. A sources jar the classpath does not know — one named by hand — counts as declared
     * directly, since somebody asked for it by name.
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

    /** A plan with no recent files and no classpath: the project, then every library as if declared directly. */
    public static Plan everything() {
        return of(List.of(), null);
    }

    /** The stages this plan makes of {@code sources}, empty ones left out. */
    public List<Stage> stages(Collection<Source> sources) {
        Set<Source> present = new LinkedHashSet<>(sources);
        Set<Source> taken = new LinkedHashSet<>();
        List<Source> open = new ArrayList<>();
        for (Source s : recent) {
            if (present.contains(s) && taken.add(s)) open.add(s);
        }
        List<Source> project = new ArrayList<>();
        List<Source> direct = new ArrayList<>();
        List<Source> other = new ArrayList<>();
        for (Source s : present) {
            if (taken.contains(s)) continue;
            switch (s) {
                case Source.OnDisk d -> project.add(s);
                case Source.InArchive a -> (depthOf(a) <= 1 ? direct : other).add(s);
            }
        }
        // Stable: within a depth, the order the index holds them, which is the order the classpath gave the jars.
        other.sort(Comparator.comparingInt(s -> depthOf((Source.InArchive) s)));

        List<Stage> stages = new ArrayList<>();
        for (Stage stage : List.of(new Stage(OPEN, open), new Stage(PROJECT, project),
                new Stage(DIRECT_LIBRARIES, direct), new Stage(OTHER_LIBRARIES, other))) {
            if (!stage.sources().isEmpty()) stages.add(stage);
        }
        return stages;
    }

    private int depthOf(Source.InArchive source) {
        return depths.getOrDefault(source.archive(), 1);
    }
}
