package sibarum.concordance.project;

import java.nio.file.Path;

/**
 * A library a project's main source can name, resolved against the local repository.
 *
 * @param coordinates the version Maven's mediation would pick: the nearest declaration, the first at a tie
 * @param type        {@code jar} unless declared otherwise
 * @param classifier  empty unless declared
 * @param scope       {@code compile}, {@code provided} or {@code system}, after Maven's scope rules for transitives
 * @param depth       1 for a dependency the project declares, 2 for one of those's dependencies, and so on
 * @param jar         the artifact's file in the local repository, or null when it is not there
 * @param sourcesJar  its {@code -sources.jar}, or null when it is not there
 */
public record Dependency(Coordinates coordinates, String type, String classifier, String scope, int depth,
                         Path jar, Path sourcesJar) {

    /** Whether the project names this dependency itself, rather than reaching it through another. */
    public boolean direct() {
        return depth == 1;
    }

    @Override
    public String toString() {
        return coordinates + (classifier.isEmpty() ? "" : ":" + classifier) + " (" + scope + ", depth " + depth + ")";
    }
}
