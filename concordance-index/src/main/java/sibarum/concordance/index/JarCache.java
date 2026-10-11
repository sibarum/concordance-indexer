package sibarum.concordance.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What each library jar contributes to an index, kept once per machine rather than once per project.
 *
 * <p>A jar's index depends on nothing but its bytes: the same {@code guava-33.0.jar} says the same things in every
 * project that uses it, wherever it sits on disk. So it is keyed by its SHA-256 — which a synchronize computes anyway
 * to see whether the jar changed — and whether it was read for its sources or its classes, and stored without a path.
 * A project indexing a jar some other project already indexed loads it instead of parsing it, and places its
 * sources in its own copy of the jar.
 *
 * <p>Each file carries the fingerprint of the index format and of the {@link Limits} it was built within, so a
 * Concordance that would index the jar differently treats it as a miss and writes it afresh. Writes go to a sibling
 * and are moved into place, so two processes caching the same jar at once cannot leave half a file. Everything
 * here is best-effort: a cache that cannot be read or written is a cache that missed, never a failed synchronize.
 *
 * <p>Nothing is evicted on its own; {@link #prune} trims to a size, least recently used first.
 */
public final class JarCache {

    private final Path dir;

    private JarCache(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    /** A cache in {@code dir}, created when first written to. */
    public static JarCache at(Path dir) {
        return new JarCache(dir);
    }

    /** {@code CONCORDANCE_JAR_CACHE} if it is set, else {@code ~/.concordance/jars}. */
    public static JarCache defaultLocation() {
        String configured = System.getenv("CONCORDANCE_JAR_CACHE");
        if (configured != null && !configured.isBlank()) return at(Path.of(configured));
        return at(Path.of(System.getProperty("user.home"), ".concordance", "jars"));
    }

    public Path dir() {
        return dir;
    }

    /** The jar index for these bytes read this way, its sources placed in {@code jar}; empty on any miss. */
    Optional<IndexCodec.JarContents> load(byte[] digest, boolean compiled, Path jar, Limits limits) {
        Path file = fileFor(digest, compiled);
        try {
            Optional<IndexCodec.JarContents> found = IndexCodec.readJar(file, jar, compiled, fingerprint(limits));
            if (found.isPresent()) {
                Files.setLastModifiedTime(file, FileTime.from(Instant.now()));   // what prune counts as use
            }
            return found;
        } catch (IOException | RuntimeException unusable) {
            return Optional.empty();
        }
    }

    /**
     * Keeps what {@code archive} contributes, unless something about it was not the jar's doing — an interrupted
     * parse says nothing about the bytes, and must not be served to the next project as if it did.
     */
    void store(ProjectIndex.Archive archive, Limits limits) {
        boolean transient_ = archive.problems().stream().anyMatch(p -> p.startsWith("interrupted"))
                || archive.entries().stream().anyMatch(e -> e.problem().startsWith("interrupted"));
        if (transient_) return;
        try {
            IndexCodec.writeJar(archive, fileFor(archive.digest(), archive.compiled()), fingerprint(limits));
        } catch (IOException | RuntimeException unwritable) {
            // A read-only home, a full disk: the next synchronize indexes the jar again, which is all a miss costs.
        }
    }

    /** How much the cache holds on disk, in bytes. */
    public long sizeBytes() throws IOException {
        return files().stream().mapToLong(f -> f.toFile().length()).sum();
    }

    /**
     * Deletes the least recently used files until the cache holds at most {@code maxBytes}, and returns how many
     * went. A file deleted here is simply indexed again the next time a project needs its jar.
     */
    public int prune(long maxBytes) throws IOException {
        List<Path> files = new ArrayList<>(files());
        files.sort(Comparator.comparingLong(f -> f.toFile().lastModified()));
        long total = files.stream().mapToLong(f -> f.toFile().length()).sum();
        int deleted = 0;
        for (Path file : files) {
            if (total <= maxBytes) break;
            long size = file.toFile().length();
            if (Files.deleteIfExists(file)) {
                total -= size;
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public String toString() {
        return dir.toString();
    }

    // ------------------------------------------------------------------------------------------

    private Path fileFor(byte[] digest, boolean compiled) {
        return dir.resolve(HexFormat.of().formatHex(digest) + (compiled ? ".classes" : ".sources"));
    }

    /** What decides whether a cached jar index is the one this Concordance would build. */
    static String fingerprint(Limits limits) {
        // Only the limits that change what is indexed: how long a decompile may take does not.
        return IndexCodec.fingerprint() + " limits=" + limits.maxSourceBytes() + "," + limits.maxArchiveSourceBytes()
                + "," + limits.maxArchiveEntries() + "," + limits.parseStackBytes();
    }

    private List<Path> files() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.filter(f -> {
                String name = f.getFileName().toString();
                return name.endsWith(".classes") || name.endsWith(".sources");
            }).toList();
        }
    }
}
