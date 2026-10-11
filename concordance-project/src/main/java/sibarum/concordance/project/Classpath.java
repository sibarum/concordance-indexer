package sibarum.concordance.project;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The libraries a project's main source can name, nearest first, and what could not be resolved.
 *
 * <p>Order is by depth — everything the project declares, then what those declare, and so on — and declaration
 * order within a depth. That is the order a search should widen in, and the order {@link #sourcesJars} hands
 * them to an index.
 *
 * @param dependencies resolved libraries, nearest first
 * @param problems     what resolution could not do, one sentence each: a pom missing from the local repository, a
 *                     version that names an unknown property, a range nothing local satisfies
 */
public record Classpath(List<Dependency> dependencies, List<String> problems) {

    public Classpath {
        dependencies = List.copyOf(dependencies);
        problems = List.copyOf(problems);
    }

    /** The sources jars present for these dependencies, nearest first. */
    public List<Path> sourcesJars() {
        return dependencies.stream().map(Dependency::sourcesJar).filter(Objects::nonNull).toList();
    }

    /** The dependencies the project declares itself. */
    public List<Dependency> direct() {
        return dependencies.stream().filter(Dependency::direct).toList();
    }

    /** Dependencies with no sources jar in the local repository — what a decompiler would have to cover. */
    public List<Dependency> withoutSources() {
        return dependencies.stream().filter(d -> d.sourcesJar() == null).toList();
    }
}
