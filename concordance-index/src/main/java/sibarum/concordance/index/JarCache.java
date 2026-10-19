package sibarum.concordance.index;

import sibarum.concordance.project.Versions;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
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
 * <p><b>Rot.</b> An index nobody will load again is dropped on its own, in two ways:
 * <ul>
 *   <li>When a jar's path is stamped with a new hash — a snapshot installed again, the same artifact version
 *       built anew — the index of what the path held before goes at once, unless another path still holds those
 *       bytes.</li>
 *   <li>A {@link #sweep}, which a synchronize runs at most once every {@link #SWEEP_EVERY}, drops each index that
 *       no project uses, that a newer version of the same artifact has in the cache, and that has not been built
 *       or loaded for {@link #STALE_AFTER}. Which project uses what is recorded by each synchronize; an artifact
 *       and its version are read from the jar's path in a Maven repository
 *       ({@code .../artifactId/version/artifactId-version[-classifier].jar}), so a jar outside one, whose version
 *       cannot be known, is never swept. The newest version of an artifact is never swept either.</li>
 * </ul>
 * Anything dropped is simply indexed again if a project needs it after all. {@link #prune} trims to a size, least
 * recently used first, and {@link #purge} empties the cache.
 */
public final class JarCache {

    /** How long an index of an outdated, unused artifact version is kept since it was last built or loaded. */
    public static final Duration STALE_AFTER = Duration.ofDays(3);

    /** How often a synchronize sweeps the cache for stale indexes. */
    public static final Duration SWEEP_EVERY = Duration.ofDays(1);

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

    /**
     * One cache file: the jar's index in the form a {@link LibraryFile} carries it, and the {@link #fingerprint} of
     * the Concordance that built it. STRICT, so a file written by any other build of this schema does not decode,
     * and neither does one in the cache's older format: either is a miss, and is written afresh.
     */
    @IntrabufMessage(name = "concordance.JarCacheEntry")
    record Cached(@Field(nonNull = true) String fingerprint, @Field(nonNull = true) LibraryFile.Index index) {}

    /** The jar index for these bytes read this way, its sources placed in {@code jar}; empty on any miss. */
    Optional<LibraryFile.Contents> load(byte[] digest, boolean compiled, Path jar, Limits limits) {
        Path file = fileFor(digest, compiled);
        try {
            Cached cached = JarCache_CachedIntrabuf.fromBytes(Files.readAllBytes(file));
            if (!cached.fingerprint().equals(fingerprint(limits)) || cached.index().compiled() != compiled) {
                return Optional.empty();
            }
            LibraryFile.Contents found = LibraryFile.contents(cached.index(), jar);
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));   // what prune counts as use
            return Optional.of(found);
        } catch (IOException | RuntimeException unusable) {
            // Absent, truncated, from another build or format, or strings out of its table: index the jar.
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
            Cached cached = new Cached(fingerprint(limits),
                    LibraryFile.index(archive.compiled(), archive.entries(), archive.problems()));
            write(fileFor(archive.digest(), archive.compiled()), JarCache_CachedIntrabuf.toBytes(cached));
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

    /**
     * Records that the jar at {@code jar}, at this size and modification time, hashes to {@code digest}. If the
     * path held other bytes before, their index has been superseded and goes, unless some other path holds them.
     */
    void remember(Path jar, long size, long modified, byte[] digest) {
        Path stamp = stampFor(jar);
        String hex = HexFormat.of().formatHex(digest);
        try {
            String before = readStamp(stamp).map(Stamp::digest).orElse(hex);
            write(stamp, List.of(STAMP_FORMAT, key(jar), Long.toString(size), Long.toString(modified), hex));
            if (!before.equals(hex) && stamps().stream().noneMatch(s -> s.digest().equals(before))) {
                Files.deleteIfExists(dir.resolve(before + SOURCES));
                Files.deleteIfExists(dir.resolve(before + CLASSES));
            }
        } catch (IOException | RuntimeException unwritable) {
            // Without a stamp the jar is hashed next time, which is all a missing stamp costs.
        }
    }

    /**
     * Records that the project at {@code root} now uses the jars with these hashes, and only those: an index
     * some project uses is never swept, however old its version.
     */
    void used(Path root, Collection<byte[]> digests) {
        Path record = dir.resolve(PROJECTS).resolve(nameFor(root));
        try {
            if (digests.isEmpty()) {
                Files.deleteIfExists(record);
                return;
            }
            List<String> lines = new ArrayList<>(List.of(PROJECT_FORMAT, key(root)));
            digests.stream().map(HexFormat.of()::formatHex).sorted().distinct().forEach(lines::add);
            if (Files.isRegularFile(record) && Files.readAllLines(record).equals(lines)) return;
            write(record, lines);
        } catch (IOException | RuntimeException unwritable) {
            // Unrecorded, the project's jars may be swept if they are old versions; they are indexed again if so.
        }
    }

    /** {@link #sweep}s the cache if it has not been swept within {@link #SWEEP_EVERY}; never fails. */
    void sweepIfDue() {
        Path marker = dir.resolve(SWEPT);
        Instant now = Instant.now();
        try {
            if (!Files.isDirectory(dir)) return;
            if (Files.isRegularFile(marker)
                    && Files.getLastModifiedTime(marker).toInstant().isAfter(now.minus(SWEEP_EVERY))) {
                return;
            }
            Files.write(marker, new byte[0]);
            sweep(now);
        } catch (IOException | RuntimeException unswept) {
            // tried again next time it is due
        }
    }

    /**
     * Drops every index that is {@link Indexed#stale stale} at {@code now}, and the records of projects that are
     * no longer on disk; returns how many indexes went.
     */
    public int sweep(Instant now) throws IOException {
        int deleted = 0;
        for (Path record : list(dir.resolve(PROJECTS))) {
            Optional<List<String>> lines = readProject(record);
            if (lines.isEmpty() || !onDisk(lines.get().get(1))) Files.deleteIfExists(record);
        }
        for (Indexed index : indexes()) {
            if (index.stale(now) && Files.deleteIfExists(dir.resolve(index.digest() + suffix(index.compiled())))) {
                deleted++;
            }
        }
        if (deleted > 0) dropOrphanStamps();
        return deleted;
    }

    /**
     * One jar index the cache holds, for looking at: its size, when it was last built or loaded, the jar paths
     * known to hold its bytes, how many projects use it, and whether the cache holds a newer version of every
     * artifact it is known as.
     */
    public record Indexed(String digest, boolean compiled, long bytes, Instant lastUsed, List<Path> jars,
                          int projects, boolean outdated) {

        public Indexed {
            jars = List.copyOf(jars);
        }

        /** Whether a sweep at {@code now} drops it: unused, outdated, and not built or loaded for a while. */
        public boolean stale(Instant now) {
            return projects == 0 && outdated && lastUsed.isBefore(now.minus(STALE_AFTER));
        }
    }

    /** Every jar index the cache holds, largest first. */
    public List<Indexed> indexes() throws IOException {
        List<Stamp> stamps = stamps();
        Map<String, List<Path>> jarsOf = new HashMap<>();
        stamps.forEach(s -> jarsOf.computeIfAbsent(s.digest(), k -> new ArrayList<>()).add(Path.of(s.jar())));
        Map<String, Integer> projectsOf = new HashMap<>();
        for (Path record : list(dir.resolve(PROJECTS))) {
            Optional<List<String>> lines = readProject(record);
            if (lines.isEmpty() || !onDisk(lines.get().get(1))) continue;
            lines.get().subList(2, lines.get().size()).forEach(hex -> projectsOf.merge(hex, 1, Integer::sum));
        }

        List<Indexed> out = new ArrayList<>();
        for (Path file : files()) {
            String name = file.getFileName().toString();
            boolean compiled = name.endsWith(CLASSES);
            String hex = name.substring(0, name.length() - suffix(compiled).length());
            List<Path> jars = jarsOf.getOrDefault(hex, List.of());
            boolean outdated = !jars.isEmpty() && jars.stream().allMatch(jar -> artifactOf(jar)
                    .map(mine -> stamps.stream().anyMatch(s -> !s.digest().equals(hex)
                            && Files.exists(dir.resolve(s.digest() + suffix(compiled)))
                            && artifactOf(Path.of(s.jar())).map(mine::olderThan).orElse(false)))
                    .orElse(false));
            out.add(new Indexed(hex, compiled, file.toFile().length(),
                    Instant.ofEpochMilli(file.toFile().lastModified()), jars, projectsOf.getOrDefault(hex, 0),
                    outdated));
        }
        out.sort(Comparator.comparingLong(Indexed::bytes).reversed());
        return out;
    }

    /** How many jar indexes the cache holds. */
    public int count() throws IOException {
        return files().size();
    }

    /** How much the jar indexes take on disk, in bytes. */
    public long sizeBytes() throws IOException {
        return files().stream().mapToLong(f -> f.toFile().length()).sum();
    }

    /** How much the cache's bookkeeping takes on disk besides: its stamps and its records of which project uses what. */
    public long overheadBytes() throws IOException {
        long total = 0;
        for (Path f : list(dir.resolve(STAMPS))) total += f.toFile().length();
        for (Path f : list(dir.resolve(PROJECTS))) total += f.toFile().length();
        return total;
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

    /**
     * Deletes every jar index, stamp and project record, and returns how many indexes went. Only what the cache
     * itself wrote is touched, so a cache pointed at a directory holding other things leaves them be.
     */
    public int purge() throws IOException {
        int deleted = 0;
        for (Path file : files()) {
            if (Files.deleteIfExists(file)) deleted++;
        }
        for (String sub : List.of(STAMPS, PROJECTS)) {
            for (Path f : list(dir.resolve(sub))) Files.deleteIfExists(f);
            if (Files.isDirectory(dir.resolve(sub))) Files.deleteIfExists(dir.resolve(sub));
        }
        Files.deleteIfExists(dir.resolve(SWEPT));
        for (Path f : list(dir)) {
            String name = f.getFileName().toString();
            if (name.endsWith(".partial") && (name.contains(SOURCES) || name.contains(CLASSES))) {
                Files.deleteIfExists(f);
            }
        }
        return deleted;
    }

    @Override
    public String toString() {
        return dir.toString();
    }

    // ------------------------------------------------------------------------------------------

    private static final String STAMP_FORMAT = "concordance-jar-stamp 1";
    private static final String PROJECT_FORMAT = "concordance-jar-users 1";
    private static final String SOURCES = ".sources";
    private static final String CLASSES = ".classes";
    private static final String STAMPS = "stamps";
    private static final String PROJECTS = "projects";
    private static final String SWEPT = "swept";

    private record Stamp(String jar, String digest) {}

    /**
     * An artifact as a Maven repository lays it out: {@code key} names the artifact and the kind of jar (main,
     * sources, another classifier) without the version, so two of them with one key are versions of one thing.
     */
    record Artifact(String key, String version) {

        boolean olderThan(Artifact other) {
            return key.equals(other.key) && Versions.compare(version, other.version) < 0;
        }
    }

    private static final Pattern SNAPSHOT_STAMP = Pattern.compile("\\d{8}\\.\\d{6}-\\d+");

    /**
     * The artifact the jar at {@code jar} is, read from a Maven repository's layout
     * ({@code .../artifactId/version/artifactId-version[-classifier].jar}, a snapshot's version possibly a
     * timestamp); empty when the path is not laid out that way.
     */
    static Optional<Artifact> artifactOf(Path jar) {
        Path versionDir = jar.getParent();
        Path artifactDir = versionDir == null ? null : versionDir.getParent();
        if (artifactDir == null || artifactDir.getFileName() == null) return Optional.empty();
        String name = jar.getFileName().toString();
        String version = versionDir.getFileName().toString();
        String artifactId = artifactDir.getFileName().toString();
        if (!name.endsWith(".jar")) return Optional.empty();
        String rest;
        if (name.startsWith(artifactId + "-" + version)) {
            rest = name.substring((artifactId + "-" + version).length());
        } else if (version.endsWith("-SNAPSHOT")
                && name.startsWith(artifactId + "-" + version.substring(0, version.length() - "SNAPSHOT".length()))) {
            String timestamped = name.substring(artifactId.length() + version.length() - "SNAPSHOT".length() + 1);
            var m = SNAPSHOT_STAMP.matcher(timestamped);
            if (!m.lookingAt()) return Optional.empty();
            rest = timestamped.substring(m.end());
        } else {
            return Optional.empty();
        }
        if (!rest.equals(".jar") && !(rest.startsWith("-") && rest.length() > "-.jar".length())) return Optional.empty();
        return Optional.of(new Artifact(key(artifactDir) + "|" + rest, version));
    }

    /** A stamp's file: named by a hash of the jar's path, so any path has a fixed, safe file name. */
    private Path stampFor(Path jar) {
        return dir.resolve(STAMPS).resolve(nameFor(jar));
    }

    private static String nameFor(Path path) {
        return HexFormat.of().formatHex(ProjectIndex.digest(key(path).getBytes(StandardCharsets.UTF_8)));
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String suffix(boolean compiled) {
        return compiled ? CLASSES : SOURCES;
    }

    private static Optional<Stamp> readStamp(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() == 5 && lines.get(0).equals(STAMP_FORMAT)) return Optional.of(new Stamp(lines.get(1), lines.get(4)));
        } catch (IOException | RuntimeException unreadable) {
            // not a stamp this reads
        }
        return Optional.empty();
    }

    /** A project record's lines — format, root, then each hash it uses — or empty if it is not one. */
    private static Optional<List<String>> readProject(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() >= 2 && lines.get(0).equals(PROJECT_FORMAT)) return Optional.of(lines);
        } catch (IOException | RuntimeException unreadable) {
            // not a record this reads
        }
        return Optional.empty();
    }

    /** Whether a project record's root is still a directory; a project that is gone uses nothing. */
    private static boolean onDisk(String root) {
        try {
            return Files.isDirectory(Path.of(root));
        } catch (RuntimeException notAPath) {
            return false;
        }
    }

    private List<Stamp> stamps() throws IOException {
        List<Stamp> out = new ArrayList<>();
        for (Path file : list(dir.resolve(STAMPS))) readStamp(file).ifPresent(out::add);
        return out;
    }

    /** Stamps whose hash no longer has an entry, which pruning leaves behind. */
    private void dropOrphanStamps() throws IOException {
        for (Path stamp : list(dir.resolve(STAMPS))) {
            Optional<Stamp> read = readStamp(stamp);
            if (read.isEmpty() || !Files.exists(dir.resolve(read.get().digest() + SOURCES))
                    && !Files.exists(dir.resolve(read.get().digest() + CLASSES))) {
                Files.deleteIfExists(stamp);
            }
        }
    }

    /** Writes {@code lines} to a sibling of {@code file} and moves it into place. */
    private static void write(Path file, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append(System.lineSeparator()));
        write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Writes {@code bytes} to a sibling of {@code file} and moves it into place. */
    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path partial = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".partial");
        try {
            Files.write(partial, bytes);
            try {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private Path fileFor(byte[] digest, boolean compiled) {
        return dir.resolve(HexFormat.of().formatHex(digest) + suffix(compiled));
    }

    /** What decides whether a cached jar index is the one this Concordance would build. */
    static String fingerprint(Limits limits) {
        // Only the limits that change what is indexed: how long a decompile may take does not.
        return IndexCodec.fingerprint() + " limits=" + limits.maxSourceBytes() + "," + limits.maxArchiveSourceBytes()
                + "," + limits.maxArchiveEntries() + "," + limits.parseStackBytes();
    }

    private List<Path> files() throws IOException {
        return list(dir).stream().filter(f -> {
            String name = f.getFileName().toString();
            return name.endsWith(CLASSES) || name.endsWith(SOURCES);
        }).toList();
    }

    /** The regular files directly in {@code directory}; none if it does not exist. */
    private static List<Path> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> listing = Files.list(directory)) {
            return listing.filter(Files::isRegularFile).toList();
        }
    }
}
