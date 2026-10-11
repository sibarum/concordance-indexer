package sibarum.concordance.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>Finding the hash means reading the jar, so the cache also keeps a <em>stamp</em> per jar path: the size and
 * modification time the jar had when it was last hashed, and the hash. A project meeting a jar whose path, size
 * and modification time match a stamp takes the hash from it without reading the jar. A stamp is recorded only once
 * the jar's modification time is safely in the past (see {@link ProjectIndex#RACY_MILLIS}), since a jar rewritten
 * within the clock's resolution could keep its stat and change its bytes. A stamp only ever supplies a hash: if the
 * entry it points to is gone, the jar is read and hashed as before.
 *
 * <p>Nothing is evicted on its own; {@link #prune} trims to a size, least recently used first, and drops the stamps
 * of what it removed.
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

    /**
     * The hash of the jar at {@code jar} when it has exactly this size and modification time, as recorded by
     * {@link #remember}; empty when no stamp matches all three.
     */
    Optional<byte[]> digestFor(Path jar, long size, long modified) {
        try {
            List<String> lines = Files.readAllLines(stampFor(jar));
            if (lines.size() == 5 && lines.get(0).equals(STAMP_FORMAT) && lines.get(1).equals(key(jar))
                    && lines.get(2).equals(Long.toString(size)) && lines.get(3).equals(Long.toString(modified))) {
                return Optional.of(HexFormat.of().parseHex(lines.get(4)));
            }
        } catch (IOException | RuntimeException noStamp) {
            // no stamp, or not one this reads: the jar is hashed
        }
        return Optional.empty();
    }

    /** Records that the jar at {@code jar}, at this size and modification time, hashes to {@code digest}. */
    void remember(Path jar, long size, long modified, byte[] digest) {
        Path stamp = stampFor(jar);
        try {
            Files.createDirectories(stamp.getParent());
            Path partial = Files.createTempFile(stamp.getParent(), stamp.getFileName().toString(), ".partial");
            try {
                Files.write(partial, List.of(STAMP_FORMAT, key(jar), Long.toString(size), Long.toString(modified),
                        HexFormat.of().formatHex(digest)));
                try {
                    Files.move(partial, stamp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(partial, stamp, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(partial);
            }
        } catch (IOException | RuntimeException unwritable) {
            // Without a stamp the jar is hashed next time, which is all a missing stamp costs.
        }
    }

    /** How many jar indexes the cache holds. */
    public int count() throws IOException {
        return files().size();
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
        if (deleted > 0) dropOrphanStamps();
        return deleted;
    }

    @Override
    public String toString() {
        return dir.toString();
    }

    // ------------------------------------------------------------------------------------------

    private static final String STAMP_FORMAT = "concordance-jar-stamp 1";

    /** A stamp's file: named by a hash of the jar's path, so any path has a fixed, safe file name. */
    private Path stampFor(Path jar) {
        return dir.resolve("stamps").resolve(HexFormat.of().formatHex(
                ProjectIndex.digest(key(jar).getBytes(StandardCharsets.UTF_8))));
    }

    private static String key(Path jar) {
        return jar.toAbsolutePath().normalize().toString();
    }

    /** Stamps whose hash no longer has an entry, which pruning leaves behind. */
    private void dropOrphanStamps() throws IOException {
        Path stamps = dir.resolve("stamps");
        if (!Files.isDirectory(stamps)) return;
        try (Stream<Path> listing = Files.list(stamps)) {
            for (Path stamp : listing.toList()) {
                try {
                    List<String> lines = Files.readAllLines(stamp);
                    String hex = lines.size() == 5 ? lines.get(4) : "";
                    if (!Files.exists(dir.resolve(hex + ".sources")) && !Files.exists(dir.resolve(hex + ".classes"))) {
                        Files.deleteIfExists(stamp);
                    }
                } catch (IOException | RuntimeException unreadable) {
                    Files.deleteIfExists(stamp);
                }
            }
        }
    }

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
