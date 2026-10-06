package sibarum.concordance.index;

import com.github.javaparser.JavaParser;
import sibarum.concordance.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link Index} that remembers which file said what, so it can be brought up to date by reparsing only what
 * changed, and saved to disk so an editor reopening a project need not reparse what it already knew.
 *
 * <p>The life of one in an editor:
 *
 * <pre>{@code
 * ProjectIndex live = ProjectIndex.load(cacheFile, root).orElseGet(() -> ProjectIndex.empty(root));
 * live.synchronize(project);   // on open: reparses only what changed while the editor was closed
 * ...
 * live.synchronize(project);   // on save, on focus, on a file-watcher event
 * live.index().usagesOf("area");
 * ...
 * live.save(cacheFile);        // on close, or after a synchronize that changed something
 * }</pre>
 *
 * <p><b>Change detection</b> is a stat first and a hash only when the stat disagrees. A file whose size and
 * modification time match its stamp is taken as unchanged without being read. One whose stat differs is read
 * and hashed, and reparsed only if its content really did change — so a checkout or a build that touches files
 * without editing them costs a read apiece, not a parse.
 *
 * <p>A modification time is trusted only once it is safely in the past. A file written within
 * {@link #RACY_MILLIS} of a synchronize could be written again within the clock's resolution, at the same size,
 * and a stat would not see it; such a file is stamped as untrusted, and the next synchronize hashes it rather
 * than believing the stat. This is git's "racily clean" problem, and the same answer.
 *
 * <p><b>Order</b> is the order of the files given to {@link #synchronize}, so a synchronized index is the same
 * index, list for list, as {@link IndexBuilder#buildFiles} over the same files.
 *
 * <p><b>Paths</b> are made absolute and normalized on the way in, so one file is one entry however it was
 * spelled, and every {@link SourceRef} in the index is somewhere an editor can open without knowing the working
 * directory.
 *
 * <p>Not thread-safe: synchronize from one thread. An {@link Index} already handed out by {@link #index} is
 * immutable and can go on answering queries on another thread while the next synchronize runs.
 */
public final class ProjectIndex {

    /** How recently a file may have been written before its modification time is not yet trusted. */
    static final long RACY_MILLIS = 2_000;

    /** A modification time that is never trusted, so the file is hashed on the next synchronize. */
    static final long UNTRUSTED = Long.MIN_VALUE;

    /** What one file contributed, and the stamp that says whether it still does. */
    record Entry(Path file, long size, long modified, byte[] digest,
                 List<Symbol> symbols, List<Reference> references) {

        Entry {
            symbols = List.copyOf(symbols);
            references = List.copyOf(references);
        }

        Entry restamped(long size, long modified) {
            return new Entry(file, size, modified, digest, symbols, references);
        }
    }

    /**
     * What a {@link #synchronize} found. A file whose stat changed but whose content did not is unchanged: it
     * was not reparsed, and nothing in the index moved.
     */
    public record Changes(List<Path> added, List<Path> changed, List<Path> removed, int unchanged) {

        public Changes {
            added = List.copyOf(added);
            changed = List.copyOf(changed);
            removed = List.copyOf(removed);
        }

        /** Whether the index answers anything differently than before. */
        public boolean isEmpty() {
            return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
        }

        @Override
        public String toString() {
            return added.size() + " added, " + changed.size() + " changed, " + removed.size() + " removed, "
                    + unchanged + " unchanged";
        }
    }

    private final Path root;
    private Map<Path, Entry> entries;
    private Index index;

    ProjectIndex(Path root, Map<Path, Entry> entries) {
        this.root = normalize(root);
        this.entries = new LinkedHashMap<>(entries);
    }

    /** An index of nothing yet, for a project rooted at {@code root}. Synchronize it to fill it. */
    public static ProjectIndex empty(Path root) {
        return new ProjectIndex(root, Map.of());
    }

    /** A fresh index of every main source file of a project. */
    public static ProjectIndex build(MavenProject project) throws IOException {
        ProjectIndex built = empty(project.rootDir());
        built.synchronize(project);
        return built;
    }

    /**
     * The index saved at {@code file}, with its paths resolved against {@code root} — or empty when there is
     * nothing there, when it was written by a Concordance whose index would differ from this one's, or when it
     * is not a complete index at all. Empty means "build afresh"; it is never an error to lose a cache.
     *
     * <p>Paths are saved relative to the project root, so a checkout that moves keeps its index.
     *
     * @throws IOException only when the file exists and cannot be read
     */
    public static Optional<ProjectIndex> load(Path file, Path root) throws IOException {
        return IndexCodec.read(file, normalize(root));
    }

    /**
     * Writes this index to {@code file}, replacing whatever is there. The write goes to a sibling first and is
     * moved into place, so a reader — or an editor that dies mid-save — never sees half an index.
     */
    public void save(Path file) throws IOException {
        IndexCodec.write(this, file);
    }

    public Path root() {
        return root;
    }

    /** The files this index holds, in index order. */
    public List<Path> files() {
        return List.copyOf(entries.keySet());
    }

    /** The index as it stands after the last synchronize. Cheap to call again when nothing has changed. */
    public Index index() {
        if (index == null) {
            List<Symbol> symbols = new ArrayList<>();
            List<Reference> references = new ArrayList<>();
            for (Entry entry : entries.values()) {
                symbols.addAll(entry.symbols());
                references.addAll(entry.references());
            }
            index = new Index(symbols, references);
        }
        return index;
    }

    /** Brings the index up to date with every main source file of {@code project}, as it is on disk now. */
    public Changes synchronize(MavenProject project) throws IOException {
        return synchronize(project.javaFiles());
    }

    /**
     * Brings the index up to date with {@code files}, which are the whole of what it should hold: a file held
     * now and missing from {@code files} is removed, as is one listed that no longer exists.
     */
    public Changes synchronize(Collection<Path> files) throws IOException {
        long startedAt = System.currentTimeMillis();
        JavaParser parser = null;
        Map<Path, Entry> next = new LinkedHashMap<>();
        List<Path> added = new ArrayList<>();
        List<Path> changed = new ArrayList<>();
        int unchanged = 0;

        for (Path given : files) {
            Path file = normalize(given);
            if (next.containsKey(file)) continue;
            Entry old = entries.get(file);

            BasicFileAttributes stat;
            try {
                stat = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException gone) {
                continue;
            }
            long modified = stat.lastModifiedTime().toMillis();
            if (old != null && old.modified() != UNTRUSTED
                    && old.size() == stat.size() && old.modified() == modified) {
                next.put(file, old);
                unchanged++;
                continue;
            }

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (NoSuchFileException gone) {
                continue;
            }
            // Stamped with the stat taken before the read: if the file changed in between, the stamp is stale
            // and the next synchronize looks again, which errs the safe way.
            long stamp = modified >= startedAt - RACY_MILLIS ? UNTRUSTED : modified;
            byte[] digest = digest(bytes);
            if (old != null && Arrays.equals(old.digest(), digest)) {
                next.put(file, old.restamped(bytes.length, stamp));
                unchanged++;
                continue;
            }

            if (parser == null) parser = IndexBuilder.parser();
            List<Symbol> symbols = new ArrayList<>();
            List<Reference> references = new ArrayList<>();
            IndexBuilder.collectBytes(parser, file, bytes, symbols, references);
            next.put(file, new Entry(file, bytes.length, stamp, digest, symbols, references));
            (old == null ? added : changed).add(file);
        }

        List<Path> removed = entries.keySet().stream().filter(f -> !next.containsKey(f)).toList();
        boolean reordered = !List.copyOf(next.keySet()).equals(List.copyOf(entries.keySet()));
        entries = next;
        if (!added.isEmpty() || !changed.isEmpty() || !removed.isEmpty() || reordered) {
            index = null;
        }
        return new Changes(added, changed, removed, unchanged);
    }

    // ------------------------------------------------------------------------------------------

    Collection<Entry> entries() {
        return entries.values();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform is required to provide SHA-256", e);
        }
    }
}
