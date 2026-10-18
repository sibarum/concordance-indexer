package sibarum.concordance.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out which libraries a project's main source can name, from its poms and the local repository, the way
 * Maven would — without Maven and without a network.
 *
 * <h2>The rules followed</h2>
 *
 * <ul>
 *   <li><b>Scopes.</b> Only {@code compile}, {@code provided} and {@code system} dependencies are visible to main
 *       source, so only those are resolved; {@code test} and {@code runtime} ones are not. A transitive
 *       dependency's scope comes from Maven's table: under {@code compile} a {@code compile} dependency stays
 *       {@code compile} and a {@code runtime} one becomes {@code runtime} (and so drops out); under
 *       {@code provided} both become {@code provided}. A transitive {@code provided} or {@code test} dependency is
 *       never inherited.
 *   <li><b>Mediation.</b> Breadth-first, so the nearest declaration of an artifact wins, and the first declared at
 *       a tie. Later ones, at any version, are not followed.
 *   <li><b>Management.</b> The resolving module's {@code dependencyManagement}, BOM imports included, overrides
 *       the version and scope of every transitive dependency; each library's own management fills in only its own
 *       declarations.
 *   <li><b>Optional and exclusions.</b> An optional dependency is taken where it is declared and not past it.
 *       Exclusions, wildcards included, apply to everything beneath the dependency that declares them.
 *   <li><b>Ranges.</b> A version range picks the highest version the local repository holds inside it; see
 *       {@link Versions}.
 *   <li><b>The reactor.</b> A dependency on another module of the same project is that module's source, already
 *       indexed, so it is not a library; what it depends on is followed as usual.
 * </ul>
 *
 * <p>What this cannot see, it says: every gap goes into {@link Classpath#problems}, and the gaps of the pom
 * reading underneath are listed on {@link Poms}.
 */
public final class DependencyResolver {

    private static final Set<String> VISIBLE_SCOPES = Set.of("compile", "provided", "system");

    private DependencyResolver() {}

    /**
     * Every library some code module of {@code project} can name, each at the nearest depth any module reaches
     * it. Two modules that settle on different versions of one library both keep theirs.
     */
    public static Classpath resolve(MavenProject project, LocalRepository repository) {
        Set<String> problems = new LinkedHashSet<>();
        Poms poms = poms(project, repository, problems);
        Map<String, Dependency> nearest = new LinkedHashMap<>();
        for (MavenModule module : project.codeModules()) {
            for (Dependency d : resolve(poms, module, repository, problems)) {
                String key = d.coordinates() + ":" + d.type() + ":" + d.classifier();
                Dependency known = nearest.get(key);
                if (known == null || d.depth() < known.depth()) nearest.put(key, d);
            }
        }
        List<Dependency> ordered = new ArrayList<>(nearest.values());
        ordered.sort(Comparator.comparingInt(Dependency::depth));   // stable: declaration order within a depth
        return new Classpath(ordered, List.copyOf(problems));
    }

    /** The libraries one module of {@code project} can name. */
    public static Classpath resolve(MavenProject project, MavenModule module, LocalRepository repository) {
        Set<String> problems = new LinkedHashSet<>();
        List<Dependency> resolved = resolve(poms(project, repository, problems), module, repository, problems);
        return new Classpath(resolved, List.copyOf(problems));
    }

    private static Poms poms(MavenProject project, LocalRepository repository, Set<String> problems) {
        return new Poms(repository, project.modules().stream().map(MavenModule::pomFile).toList(), problems);
    }

    // ------------------------------------------------------------------------------------------

    /** One dependency waiting to be visited, with the scope and exclusions its path down to it imposes. */
    private record Pending(Poms.Declared declared, int depth, String scope, List<String> exclusions) {}

    private static List<Dependency> resolve(Poms poms, MavenModule module, LocalRepository repository,
                                            Set<String> problems) {
        Optional<Poms.Effective> rootPom = poms.fromFile(module.pomFile());
        if (rootPom.isEmpty()) return List.of();
        Poms.Effective root = rootPom.get();

        Deque<Pending> queue = new ArrayDeque<>();
        for (Poms.Declared d : root.dependencies()) {
            String scope = d.scope() == null ? "compile" : d.scope();
            if (VISIBLE_SCOPES.contains(scope)) queue.add(new Pending(d, 1, scope, d.exclusions()));
        }

        List<Dependency> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(root.coordinates().groupId() + ":" + root.coordinates().artifactId() + ":jar:");
        while (!queue.isEmpty()) {
            Pending next = queue.poll();
            Poms.Declared d = next.declared();
            if (!seen.add(d.key())) continue;   // a nearer, or earlier, declaration already decided it

            String version = d.version();
            String scope = next.scope();
            if (next.depth() > 1) {
                Poms.Declared managed = root.managed().get(d.key());
                if (managed != null) {
                    if (managed.version() != null) version = managed.version();
                    if (managed.scope() != null) scope = managed.scope();
                    if (!VISIBLE_SCOPES.contains(scope)) continue;
                }
            }
            String name = d.groupId() + ":" + d.artifactId();
            if (version == null) {
                problems.add(name + " has no version, declared in " + d.declaredIn());
                continue;
            }
            if (version.contains("${")) {
                problems.add(name + " has version " + version + ", a property nothing defines, declared in "
                        + d.declaredIn());
                continue;
            }
            if (Versions.isRange(version) || version.equals("LATEST") || version.equals("RELEASE")) {
                Optional<String> picked;
                try {
                    picked = Versions.select(version, repository.versionsOf(d.groupId(), d.artifactId()));
                } catch (IOException e) {
                    picked = Optional.empty();
                }
                if (picked.isEmpty()) {
                    problems.add(name + ":" + version + " matches no version in the local repository");
                    continue;
                }
                version = picked.get();
            }
            Coordinates coordinates = new Coordinates(d.groupId(), d.artifactId(), version);

            if (poms.reactorPom(d.groupId(), d.artifactId()).isEmpty()) {
                if (scope.equals("system")) {
                    Path jar = d.systemPath() == null ? null : Path.of(d.systemPath());
                    out.add(new Dependency(coordinates, d.type(), d.classifier(), scope, next.depth(),
                            jar != null && Files.isRegularFile(jar) ? jar : null, null));
                    continue;   // a system dependency has no pom to follow
                }
                out.add(new Dependency(coordinates, d.type(), d.classifier(), scope, next.depth(),
                        present(repository.file(coordinates, classifierOf(d), extensionOf(d.type()))),
                        d.type().equals("pom") ? null : sourcesOf(repository, coordinates, d)));
            }

            Optional<Poms.Effective> pom = poms.fromCoordinates(coordinates);
            if (pom.isEmpty()) continue;
            for (Poms.Declared child : pom.get().dependencies()) {
                if (child.optional()) continue;
                String childScope = transitiveScope(scope, child.scope() == null ? "compile" : child.scope());
                if (childScope == null || excluded(next.exclusions(), child)) continue;
                List<String> exclusions = new ArrayList<>(next.exclusions());
                exclusions.addAll(child.exclusions());
                queue.add(new Pending(child, next.depth() + 1, childScope, exclusions));
            }
        }
        return out;
    }

    /** Maven's scope table, cut down to the scopes main source can see; null when the dependency drops out. */
    private static String transitiveScope(String parent, String child) {
        if (!child.equals("compile") && !child.equals("runtime")) return null;   // provided, test, system: not inherited
        if (parent.equals("provided")) return "provided";
        return child.equals("compile") ? "compile" : null;   // runtime under compile stays runtime: not visible
    }

    /** Whether any {@code group:artifact} pattern, {@code *} allowed in either half, matches {@code d}. */
    private static boolean excluded(List<String> exclusions, Poms.Declared d) {
        for (String pattern : exclusions) {
            int colon = pattern.indexOf(':');
            String g = pattern.substring(0, colon);
            String a = pattern.substring(colon + 1);
            if ((g.equals("*") || g.equals(d.groupId())) && (a.equals("*") || a.equals(d.artifactId()))) {
                return true;
            }
        }
        return false;
    }

    private static Path present(Path file) {
        return Files.isRegularFile(file) ? file : null;
    }

    private static String classifierOf(Poms.Declared d) {
        if (!d.classifier().isEmpty()) return d.classifier();
        return switch (d.type()) {
            case "test-jar" -> "tests";
            case "ejb-client" -> "client";
            default -> "";
        };
    }

    /**
     * The library's source text: its Concordance library file ({@code -concordance.jar}, a sources jar with its
     * index prebuilt) when there is one, else its {@code -sources.jar}; null when neither is there.
     */
    private static Path sourcesOf(LocalRepository repository, Coordinates coordinates, Poms.Declared d) {
        boolean tests = d.type().equals("test-jar") || d.classifier().equals("tests");
        Path prebuilt = present(repository.file(coordinates, tests ? "test-concordance" : "concordance", "jar"));
        return prebuilt != null ? prebuilt : present(repository.file(coordinates, tests ? "test-sources" : "sources", "jar"));
    }

    private static String extensionOf(String type) {
        return switch (type) {
            case "jar", "test-jar", "ejb", "ejb-client", "maven-plugin", "bundle", "java-source", "javadoc" -> "jar";
            default -> type;
        };
    }
}
