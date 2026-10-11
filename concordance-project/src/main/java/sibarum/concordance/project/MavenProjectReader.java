package sibarum.concordance.project;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static sibarum.concordance.project.Xml.childElement;
import static sibarum.concordance.project.Xml.childElements;
import static sibarum.concordance.project.Xml.childText;
import static sibarum.concordance.project.Xml.text;

/**
 * Reads a Maven reactor by reading poms, not by asking Maven.
 *
 * <p>Shelling out to {@code mvn} would be the accurate way to resolve a build model, and it is not available: the
 * indexer ships as a single binary and has to work on a machine with no Maven, no network, and no populated local
 * repository. So this reads what it needs and is candid about the rest.
 *
 * <h2>What it resolves</h2>
 *
 * <ul>
 *   <li>{@code <modules>}, recursively, depth-first in declaration order.
 *   <li>{@code artifactId}, falling back to the parent's when a module omits its own.
 *   <li>{@code <properties>}, inherited down the reactor, and {@code ${...}} interpolation of the fields read
 *       here. A property defined in a child shadows the parent's.
 *   <li>{@code <build><sourceDirectory>}, defaulting to {@code src/main/java}, and only when the directory
 *       exists — a declared source root that is not there contributes nothing to index.
 * </ul>
 *
 * <h2>What it does not</h2>
 *
 * <p>Profiles are ignored, so a source root that only exists under an activated profile is missed. Parents are
 * inherited only <em>down the reactor</em>: a pom whose parent lives outside the tree (in the local repository,
 * say) does not get that parent's properties here. Dependency classpaths are not this class's business:
 * {@link DependencyResolver} reads them, following {@code <parent>} properly into the local repository.
 *
 * <p>Each of those is a real gap rather than a simplification, and each will surface as a missing source root or
 * an uninterpolated {@code ${...}} rather than as silence — {@link #read} throws on a module directory that a pom
 * names and the disk does not have.
 */
public final class MavenProjectReader {

    /** A pom that cannot be read, or names a module that is not there. */
    public static class BrokenReactorException extends IOException {
        public BrokenReactorException(String message) { super(message); }
        public BrokenReactorException(String message, Throwable cause) { super(message, cause); }
    }

    private MavenProjectReader() {}

    /** Reads the reactor rooted at {@code pomOrDir}, which may be a {@code pom.xml} or the directory holding one. */
    public static MavenProject read(Path pomOrDir) throws IOException {
        Path pom = Files.isDirectory(pomOrDir) ? pomOrDir.resolve("pom.xml") : pomOrDir;
        if (!Files.isRegularFile(pom)) {
            throw new BrokenReactorException("no pom.xml at " + pomOrDir);
        }
        Path root = pom.toAbsolutePath().normalize().getParent();
        List<MavenModule> modules = new ArrayList<>();
        readInto(pom, Map.of(), modules, new LinkedHashSet<>());
        return new MavenProject(root, modules);
    }

    private static void readInto(Path pom, Map<String, String> inheritedProperties,
                                 List<MavenModule> out, Set<Path> visited) throws IOException {
        Path normalised = pom.toAbsolutePath().normalize();
        if (!visited.add(normalised)) {
            return;   // a pom reachable twice is read once; cycles cannot spin
        }
        Path baseDir = normalised.getParent();
        Element project = Xml.parse(normalised);

        Map<String, String> properties = new LinkedHashMap<>(inheritedProperties);
        childElement(project, "properties").ifPresent(props -> {
            for (Element p : childElements(props)) {
                properties.put(p.getTagName(), text(p));
            }
        });
        properties.put("project.basedir", baseDir.toString());

        String artifactId = childText(project, "artifactId")
                .or(() -> childElement(project, "parent").flatMap(p -> childText(p, "artifactId")))
                .map(v -> interpolate(v, properties))
                .orElseThrow(() -> new BrokenReactorException(
                        normalised + " declares no artifactId and no parent artifactId to inherit"));

        String packaging = childText(project, "packaging")
                .map(v -> interpolate(v, properties))
                .orElse("jar");

        List<Path> sourceRoots = new ArrayList<>();
        Path sourceDir = childElement(project, "build")
                .flatMap(b -> childText(b, "sourceDirectory"))
                .map(v -> interpolate(v, properties))
                .map(baseDir::resolve)
                .orElse(baseDir.resolve("src").resolve("main").resolve("java"));
        if (Files.isDirectory(sourceDir)) {
            sourceRoots.add(sourceDir.normalize());
        }

        List<String> declared = new ArrayList<>();
        childElement(project, "modules").ifPresent(ms -> {
            for (Element m : childElements(ms)) {
                if (m.getTagName().equals("module")) {
                    declared.add(interpolate(text(m), properties));
                }
            }
        });

        out.add(new MavenModule(artifactId, baseDir, normalised, packaging, sourceRoots, declared));

        for (String child : declared) {
            Path childDir = baseDir.resolve(child).normalize();
            Path childPom = Files.isDirectory(childDir) ? childDir.resolve("pom.xml") : childDir;
            if (!Files.isRegularFile(childPom)) {
                throw new BrokenReactorException(
                        normalised + " names module '" + child + "' but there is no pom at " + childPom);
            }
            readInto(childPom, properties, out, visited);
        }
    }

    /**
     * Substitutes {@code ${name}} from {@code properties}. An unknown property is left as written rather than
     * blanked, so it shows up in a path or a module name instead of vanishing.
     */
    static String interpolate(String value, Map<String, String> properties) {
        if (value == null || value.indexOf("${") < 0) return value;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int open = value.indexOf("${", i);
            if (open < 0) {
                out.append(value, i, value.length());
                break;
            }
            int close = value.indexOf('}', open + 2);
            if (close < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, open);
            String name = value.substring(open + 2, close);
            String replacement = properties.get(name);
            out.append(replacement != null ? replacement : value.substring(open, close + 1));
            i = close + 1;
        }
        return out.toString();
    }
}
