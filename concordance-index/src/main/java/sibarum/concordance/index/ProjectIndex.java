package sibarum.concordance.index;

import com.github.javaparser.JavaParser;
import sibarum.concordance.project.MavenProject;

import java.io.ByteArrayInputStream;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
 * <p><b>Libraries</b> arrive as sources jars, and contribute only what they export: see
 * {@link IndexBuilder#collectExports}. A jar is stamped as a whole, exactly as a file is, so an unchanged one is
 * not even opened — the usual case, since a released jar in a local repository never changes. One that has
 * changed, a reinstalled snapshot say, is read through, and only the entries whose content differs are reparsed.
 *
 * <p><b>Order</b> is the order of the files given to {@link #synchronize}, then each jar in the order given and
 * its entries by name. Files alone give the same index, list for list, as {@link IndexBuilder#buildFiles} over
 * the same files.
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

    /** The one entry in a library's sources that decides which of the others it exports. */
    static final String MODULE_INFO = "module-info.java";

    /**
     * What one source contributed, and the stamp that says whether it still does. An archive entry has no
     * modification time of its own, its archive's stands for it, so its {@code modified} is zero.
     */
    record Entry(Source source, long size, long modified, byte[] digest,
                 List<Symbol> symbols, List<Reference> references) {

        Entry {
            symbols = List.copyOf(symbols);
            references = List.copyOf(references);
        }

        Entry restamped(long size, long modified) {
            return new Entry(source, size, modified, digest, symbols, references);
        }
    }

    /** A sources jar, stamped as a whole, and what each of its {@code .java} entries contributed. */
    record Archive(Path file, long size, long modified, byte[] digest, List<Entry> entries) {

        Archive {
            entries = List.copyOf(entries);
        }

        Archive restamped(long size, long modified) {
            return new Archive(file, size, modified, digest, entries);
        }
    }

    /**
     * What a {@link #synchronize} found, by file and by jar: a jar is one item here however many entries it
     * holds. A file whose stat changed but whose content did not is unchanged: it was not reparsed, and nothing
     * in the index moved.
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
    private Map<Path, Archive> archives;
    private Index index;
    private Corpus corpus;

    ProjectIndex(Path root, Map<Path, Entry> entries, Map<Path, Archive> archives) {
        this.root = normalize(root);
        this.entries = new LinkedHashMap<>(entries);
        this.archives = new LinkedHashMap<>(archives);
    }

    /** An index of nothing yet, for a project rooted at {@code root}. Synchronize it to fill it. */
    public static ProjectIndex empty(Path root) {
        return new ProjectIndex(root, Map.of(), Map.of());
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

    /** The sources jars this index holds, in index order. */
    public List<Path> archives() {
        return List.copyOf(archives.keySet());
    }

    /** The index as it stands after the last synchronize. Cheap to call again when nothing has changed. */
    public Index index() {
        if (index == null) {
            List<Symbol> symbols = new ArrayList<>();
            List<Reference> references = new ArrayList<>();
            for (Entry entry : allEntries()) {
                symbols.addAll(entry.symbols());
                references.addAll(entry.references());
            }
            index = new Index(symbols, references);
        }
        return index;
    }

    /** The index cut up by source for a staged {@link Search}; kept until the index changes. */
    public Corpus corpus() {
        if (corpus == null) corpus = Corpus.of(index());
        return corpus;
    }

    /** Brings the index up to date with every main source file of {@code project}, as it is on disk now. */
    public Changes synchronize(MavenProject project) throws IOException {
        return synchronize(project.javaFiles());
    }

    /**
     * Brings the index up to date with {@code files}, which are the whole of what it should hold: a file held
     * now and missing from {@code files} is removed, as is one listed that no longer exists — and so is every
     * sources jar, since none is listed.
     */
    public Changes synchronize(Collection<Path> files) throws IOException {
        return synchronize(files, List.of());
    }

    /**
     * Brings the index up to date with {@code files} and {@code sourcesJars}, which together are the whole of
     * what it should hold. Anything held now and not listed is removed, as is anything listed that no longer
     * exists.
     */
    public Changes synchronize(Collection<Path> files, Collection<Path> sourcesJars) throws IOException {
        Sync sync = new Sync(System.currentTimeMillis());
        Map<Path, Entry> nextEntries = new LinkedHashMap<>();
        for (Path given : files) {
            Path file = normalize(given);
            if (nextEntries.containsKey(file)) continue;
            Entry entry = sync.file(file, entries.get(file));
            if (entry != null) nextEntries.put(file, entry);
        }
        Map<Path, Archive> nextArchives = new LinkedHashMap<>();
        for (Path given : sourcesJars) {
            Path jar = normalize(given);
            if (nextArchives.containsKey(jar)) continue;
            Archive archive = sync.archive(jar, archives.get(jar));
            if (archive != null) nextArchives.put(jar, archive);
        }

        List<Path> removed = new ArrayList<>();
        entries.keySet().stream().filter(f -> !nextEntries.containsKey(f)).forEach(removed::add);
        archives.keySet().stream().filter(j -> !nextArchives.containsKey(j)).forEach(removed::add);
        boolean reordered = !List.copyOf(nextEntries.keySet()).equals(List.copyOf(entries.keySet()))
                || !List.copyOf(nextArchives.keySet()).equals(List.copyOf(archives.keySet()));
        entries = nextEntries;
        archives = nextArchives;
        if (!sync.added.isEmpty() || !sync.changed.isEmpty() || !removed.isEmpty() || reordered) {
            index = null;
            corpus = null;
        }
        return new Changes(sync.added, sync.changed, removed, sync.unchanged);
    }

    /** One synchronize's worth of bookkeeping: the parser, made only if something needs parsing, and the tally. */
    private static final class Sync {
        final long startedAt;
        final List<Path> added = new ArrayList<>();
        final List<Path> changed = new ArrayList<>();
        int unchanged;
        private JavaParser parser;

        Sync(long startedAt) {
            this.startedAt = startedAt;
        }

        JavaParser parser() {
            if (parser == null) parser = IndexBuilder.parser();
            return parser;
        }

        /** The file's entry as it stands now, or null when it no longer exists. */
        Entry file(Path file, Entry old) throws IOException {
            Read read = read(file, old == null ? null : new Stamp(old.size(), old.modified(), old.digest()));
            switch (read) {
                case null -> { return null; }
                case Read.Untouched u -> {
                    unchanged++;
                    return old;
                }
                case Read.Same(long size, long modified) -> {
                    unchanged++;
                    return old.restamped(size, modified);
                }
                case Read.Fresh(byte[] bytes, long modified, byte[] digest) -> {
                    List<Symbol> symbols = new ArrayList<>();
                    List<Reference> references = new ArrayList<>();
                    Source source = new Source.OnDisk(file);
                    IndexBuilder.collectBytes(parser(), source, bytes, symbols, references);
                    (old == null ? added : changed).add(file);
                    return new Entry(source, bytes.length, modified, digest, symbols, references);
                }
            }
        }

        /** The jar's archive as it stands now, or null when it no longer exists. */
        Archive archive(Path jar, Archive old) throws IOException {
            Read read = read(jar, old == null ? null : new Stamp(old.size(), old.modified(), old.digest()));
            switch (read) {
                case null -> { return null; }
                case Read.Untouched u -> {
                    unchanged++;
                    return old;
                }
                case Read.Same(long size, long modified) -> {
                    unchanged++;
                    return old.restamped(size, modified);
                }
                case Read.Fresh(byte[] bytes, long modified, byte[] digest) -> {
                    (old == null ? added : changed).add(jar);
                    return new Archive(jar, bytes.length, modified, digest, entriesOf(jar, bytes, old));
                }
            }
        }

        /**
         * Every {@code .java} entry of a jar, by name, reusing what {@code old} knew of any whose content is the
         * same — unless {@code module-info.java} changed, since that can change what every other entry exports.
         * {@code META-INF} is skipped: what it holds is versioned copies of the entries outside it, or not Java.
         */
        private List<Entry> entriesOf(Path jar, byte[] bytes, Archive old) throws IOException {
            Map<String, byte[]> sources = new TreeMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".java") || name.startsWith("META-INF/")) continue;
                    sources.putIfAbsent(name, zip.readAllBytes());
                }
            }

            Map<String, Entry> known = new LinkedHashMap<>();
            if (old != null) {
                old.entries().forEach(e -> known.put(((Source.InArchive) e.source()).entry(), e));
            }
            byte[] moduleInfo = sources.get(MODULE_INFO);
            Entry oldModuleInfo = known.get(MODULE_INFO);
            boolean exportsSame = moduleInfo == null
                    ? oldModuleInfo == null
                    : oldModuleInfo != null && Arrays.equals(oldModuleInfo.digest(), digest(moduleInfo));
            Set<String> exported = moduleInfo == null ? null : IndexBuilder.exportsOf(parser(), moduleInfo);

            List<Entry> out = new ArrayList<>();
            for (Map.Entry<String, byte[]> source : sources.entrySet()) {
                byte[] content = source.getValue();
                byte[] digest = digest(content);
                Entry was = known.get(source.getKey());
                if (exportsSame && was != null && Arrays.equals(was.digest(), digest)) {
                    out.add(was);
                    continue;
                }
                Source at = new Source.InArchive(jar, source.getKey());
                List<Symbol> symbols = new ArrayList<>();
                IndexBuilder.collectExports(parser(), at, content, exported, symbols);
                out.add(new Entry(at, content.length, 0, digest, symbols, List.of()));
            }
            return out;
        }

        /**
         * Whether a file has changed since it was stamped {@code old}: untouched by its stat, the same once read and
         * hashed, or fresh content to parse. Null when there is no file.
         */
        private Read read(Path file, Stamp old) throws IOException {
            BasicFileAttributes stat;
            try {
                stat = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException gone) {
                return null;
            }
            long modified = stat.lastModifiedTime().toMillis();
            if (old != null && old.modified() != UNTRUSTED
                    && old.size() == stat.size() && old.modified() == modified) {
                return new Read.Untouched();
            }

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (NoSuchFileException gone) {
                return null;
            }
            // Stamped with the stat taken before the read: if the file changed in between, the stamp is stale
            // and the next synchronize looks again, which errs the safe way.
            long stamp = modified >= startedAt - RACY_MILLIS ? UNTRUSTED : modified;
            byte[] digest = digest(bytes);
            if (old != null && Arrays.equals(old.digest(), digest)) {
                return new Read.Same(bytes.length, stamp);
            }
            return new Read.Fresh(bytes, stamp, digest);
        }
    }

    private record Stamp(long size, long modified, byte[] digest) {}

    /** What reading a stamped file found. */
    private sealed interface Read {
        /** The stat matched, so the file was not read and its stamp stands. */
        record Untouched() implements Read {}
        /** Read and hashed, and the content is what it was; only the stamp moves. */
        record Same(long size, long modified) implements Read {}
        record Fresh(byte[] bytes, long modified, byte[] digest) implements Read {}
    }

    // ------------------------------------------------------------------------------------------

    Collection<Entry> entries() {
        return entries.values();
    }

    Collection<Archive> archiveEntries() {
        return archives.values();
    }

    private List<Entry> allEntries() {
        List<Entry> out = new ArrayList<>(entries.values());
        archives.values().forEach(a -> out.addAll(a.entries()));
        return out;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform is required to provide SHA-256", e);
        }
    }
}
