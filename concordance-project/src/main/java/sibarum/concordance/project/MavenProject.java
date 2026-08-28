package sibarum.concordance.project;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Maven reactor: the module the read started from, plus every module reachable through {@code <modules>},
 * flattened in the order a depth-first walk found them.
 *
 * <p>Order is load-bearing. A receipt-graph's node order is source declaration order, and the skeleton check is
 * structural equality against a fresh draft, so two reads of an unchanged tree must produce the same sequence.
 */
public record MavenProject(Path rootDir, List<MavenModule> modules) {

    public MavenProject {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("MavenProject must have at least the root module");
        }
        modules = List.copyOf(modules);
    }

    /** The module the read started from. */
    public MavenModule root() {
        return modules.get(0);
    }

    /** The modules that actually carry code, in reactor order. */
    public List<MavenModule> codeModules() {
        return modules.stream().filter(m -> !m.sourceRoots().isEmpty()).toList();
    }

    public Optional<MavenModule> moduleNamed(String artifactId) {
        return modules.stream().filter(m -> m.artifactId().equals(artifactId)).findFirst();
    }

    /** Every source root in the reactor, in reactor order. */
    public List<Path> sourceRoots() {
        return modules.stream().flatMap(m -> m.sourceRoots().stream()).toList();
    }

    /** Every {@code .java} file in the reactor, in reactor order then file order. */
    public List<Path> javaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        for (MavenModule m : modules) {
            out.addAll(m.javaFiles());
        }
        return List.copyOf(out);
    }

    /** The module a file belongs to — the one whose source root is the longest matching prefix. */
    public Optional<MavenModule> moduleOf(Path javaFile) {
        Path abs = javaFile.toAbsolutePath().normalize();
        MavenModule best = null;
        int bestLength = -1;
        for (MavenModule m : modules) {
            for (Path root : m.sourceRoots()) {
                Path r = root.toAbsolutePath().normalize();
                if (abs.startsWith(r) && r.getNameCount() > bestLength) {
                    best = m;
                    bestLength = r.getNameCount();
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
