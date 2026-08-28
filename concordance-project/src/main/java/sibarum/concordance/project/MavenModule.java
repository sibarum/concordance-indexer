package sibarum.concordance.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * One module of a Maven reactor, reduced to what indexing needs: where its pom is, what it is called, and which
 * directories hold its Java.
 *
 * @param artifactId    the module's own artifactId, inherited from its parent only when the pom omits it
 * @param baseDir       the directory containing {@link #pomFile}
 * @param pomFile       the module's {@code pom.xml}
 * @param packaging     {@code pom} for an aggregator, otherwise {@code jar} and friends
 * @param sourceRoots   existing directories holding main Java, in declaration order
 * @param declaredModules child module directory names, as written in {@code <modules>}
 */
public record MavenModule(
        String artifactId,
        Path baseDir,
        Path pomFile,
        String packaging,
        List<Path> sourceRoots,
        List<String> declaredModules) {

    public MavenModule {
        if (artifactId == null || artifactId.isEmpty()) {
            throw new IllegalArgumentException("MavenModule artifactId must be non-empty");
        }
        sourceRoots = List.copyOf(sourceRoots);
        declaredModules = List.copyOf(declaredModules);
    }

    /** An aggregator contributes no code of its own; its job is to name its children. */
    public boolean isAggregator() {
        return "pom".equals(packaging);
    }

    /**
     * Every {@code .java} file under this module's source roots, in a stable order.
     *
     * <p>Order is deterministic — source roots in declaration order, files sorted within each — because a
     * receipt-graph's node order is its source declaration order, and a graph that reorders between two drafts of
     * unchanged source would fail a skeleton check for no reason.
     */
    public List<Path> javaFiles() throws IOException {
        List<Path> out = new java.util.ArrayList<>();
        for (Path root : sourceRoots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(out::add);
            }
        }
        return List.copyOf(out);
    }
}
