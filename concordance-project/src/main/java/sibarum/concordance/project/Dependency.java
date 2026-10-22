package sibarum.concordance.project;

import java.nio.file.Path;
import java.util.List;

/**
 * A library a project's main source can name, resolved against the local repository.
 *
 * @param coordinates the version Maven's mediation would pick: the nearest declaration, the first at a tie
 * @param type        {@code jar} unless declared otherwise
 * @param classifier  empty unless declared
 * @param scope       {@code compile}, {@code provided} or {@code system}, after Maven's scope rules for transitives
 * @param depth       1 for a dependency the project declares, 2 for one of those's dependencies, and so on
 * @param jar         the artifact's file in the local repository, or null when it is not there
 * @param sourcesJar  its {@code -concordance.jar} — a sources jar with Concordance's index prebuilt — or failing
 *                    that its {@code -sources.jar}; null when neither is there
 * @param via         the dependencies it was reached through, the project's own declaration first; empty for one
 *                    the project declares
 */
public record Dependency(Coordinates coordinates, String type, String classifier, String scope, int depth,
                         Path jar, Path sourcesJar, List<Coordinates> via) {

    public Dependency {
        via = List.copyOf(via);
    }

    /** One reached through nothing: a dependency the project declares. */
    public Dependency(Coordinates coordinates, String type, String classifier, String scope, int depth, Path jar,
                      Path sourcesJar) {
        this(coordinates, type, classifier, scope, depth, jar, sourcesJar, List.of());
    }

    /** Whether its source text comes with Concordance's index prebuilt: a {@code -concordance.jar}. */
    public boolean prebuilt() {
        return sourcesJar != null && sourcesJar.getFileName().toString().endsWith("-concordance.jar");
    }

    /** Whether the project names this dependency itself, rather than reaching it through another. */
    public boolean direct() {
        return depth == 1;
    }

    @Override
    public String toString() {
        return coordinates + (classifier.isEmpty() ? "" : ":" + classifier) + " (" + scope + ", depth " + depth + ")";
    }
}
