package sibarum.concordance.project;

import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A Maven local repository — {@code ~/.m2/repository} unless configured otherwise — read as a directory layout.
 *
 * <p>Nothing here touches a network. What is in the local repository is what can be resolved, and what is not
 * there is reported as missing by whoever asked for it.
 */
public final class LocalRepository {

    private final Path root;

    private LocalRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The repository rooted at {@code root}. */
    public static LocalRepository at(Path root) {
        return new LocalRepository(root);
    }

    /**
     * The repository Maven itself would use on this machine: the {@code maven.repo.local} system property, else
     * {@code <localRepository>} in {@code ~/.m2/settings.xml}, else {@code ~/.m2/repository}.
     */
    public static LocalRepository locate() throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        String configured = System.getProperty("maven.repo.local");
        if (configured != null && !configured.isBlank()) {
            return at(Path.of(configured));
        }
        Optional<Path> fromSettings = fromSettings(home.resolve(".m2").resolve("settings.xml"), home);
        return at(fromSettings.orElse(home.resolve(".m2").resolve("repository")));
    }

    /** {@code <localRepository>} from a settings file, with {@code ${user.home}} and {@code ${env.X}} filled in. */
    static Optional<Path> fromSettings(Path settings, Path home) throws IOException {
        if (!Files.isRegularFile(settings)) return Optional.empty();
        Element root = Xml.parse(settings);
        return Xml.childText(root, "localRepository").map(value -> {
            String filled = MavenProjectReader.interpolate(value, Map.of("user.home", home.toString()));
            for (Map.Entry<String, String> env : System.getenv().entrySet()) {
                filled = filled.replace("${env." + env.getKey() + "}", env.getValue());
            }
            return Path.of(filled);
        });
    }

    public Path root() {
        return root;
    }

    /** {@code <root>/<group/as/dirs>/<artifactId>} — where every version of one artifact lives. */
    Path artifactDir(String groupId, String artifactId) {
        Path dir = root;
        for (String part : groupId.split("\\.")) dir = dir.resolve(part);
        return dir.resolve(artifactId);
    }

    /** Where a file of an artifact would be, present or not: {@code <a>-<v>[-<classifier>].<extension>}. */
    public Path file(Coordinates c, String classifier, String extension) {
        String name = c.artifactId() + "-" + c.version()
                + (classifier == null || classifier.isEmpty() ? "" : "-" + classifier) + "." + extension;
        return artifactDir(c.groupId(), c.artifactId()).resolve(c.version()).resolve(name);
    }

    /** Where an artifact's pom would be, present or not. */
    public Path pom(Coordinates c) {
        return file(c, null, "pom");
    }

    /** The versions of an artifact present here, in no particular order. */
    List<String> versionsOf(String groupId, String artifactId) throws IOException {
        Path dir = artifactDir(groupId, artifactId);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory).forEach(v -> {
                String version = v.getFileName().toString();
                if (Files.isRegularFile(v.resolve(artifactId + "-" + version + ".pom"))
                        || Files.isRegularFile(v.resolve(artifactId + "-" + version + ".jar"))) {
                    out.add(version);
                }
            });
        }
        return out;
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
