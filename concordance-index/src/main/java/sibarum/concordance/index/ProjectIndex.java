package sibarum.concordance.index;

import sibarum.concordance.project.MavenProject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
     * modification time of its own, its archive's stands for it, so its {@code modified} is zero. A source that
     * could not be indexed contributes nothing and says why in {@code problem}, which is empty otherwise.
     */
    record Entry(Source source, long size, long modified, byte[] digest,
                 List<Symbol> symbols, List<Reference> references, String problem) {

        Entry {
            symbols = List.copyOf(symbols);
            references = List.copyOf(references);
            problem = problem == null ? "" : problem;
        }

        Entry restamped(long size, long modified) {
            return new Entry(source, size, modified, digest, symbols, references, problem);
        }
    }

    /**
     * A sources jar, stamped as a whole, what each of its {@code .java} entries contributed, and what went wrong
     * with the jar as a whole: unreadable, or over a budget part way through. A {@code compiled} jar is one read for
     * its class files, its entries one per top-level class, each a {@link Source.Decompiled}.
     */
    record Archive(Path file, long size, long modified, byte[] digest, List<Entry> entries, List<String> problems,
                   boolean compiled) {

        Archive {
            entries = List.copyOf(entries);
            problems = List.copyOf(problems);
        }

        Archive restamped(long size, long modified) {
            return new Archive(file, size, modified, digest, entries, problems, compiled);
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
    private final Limits limits;
    private Map<Path, Entry> entries;
    private Map<Path, Archive> archives;
    private Index index;
    private Corpus corpus;

    ProjectIndex(Path root, Map<Path, Entry> entries, Map<Path, Archive> archives, Limits limits) {
        this.root = normalize(root);
        this.limits = limits;
        this.entries = new LinkedHashMap<>(entries);
        this.archives = new LinkedHashMap<>(archives);
    }

    /** An index of nothing yet, for a project rooted at {@code root}. Synchronize it to fill it. */
    public static ProjectIndex empty(Path root) {
        return empty(root, Limits.DEFAULT);
    }

    /** An index of nothing yet, spending no more than {@code limits} on any one input. */
    public static ProjectIndex empty(Path root, Limits limits) {
        return new ProjectIndex(root, Map.of(), Map.of(), limits);
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
        return load(file, root, Limits.DEFAULT);
    }

    /** As {@link #load(Path, Path)}, for an index that goes on to spend no more than {@code limits} per input. */
    public static Optional<ProjectIndex> load(Path file, Path root, Limits limits) throws IOException {
        return IndexCodec.read(file, normalize(root), limits);
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

    /**
     * What could not be indexed, one sentence each, naming the source: a file nested too deeply to parse, a jar
     * entry over the size limit, a jar that is not a readable zip. Each such input contributes nothing, and
     * everything else is indexed as usual.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (!entry.problem().isEmpty()) out.add(entry.source() + ": " + entry.problem());
        }
        for (Archive archive : archives.values()) {
            archive.problems().forEach(p -> out.add(archive.file() + ": " + p));
            for (Entry entry : archive.entries()) {
                if (!entry.problem().isEmpty()) out.add(entry.source() + ": " + entry.problem());
            }
        }
        return out;
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
        return synchronize(files, sourcesJars, List.of());
    }

    /**
     * Brings the index up to date with {@code files}, {@code sourcesJars}, and {@code classJars} — libraries with no
     * sources, indexed from their class files (see {@link ClassFiles}) and shown decompiled — which together are
     * the whole of what it should hold. A jar listed twice is indexed the first way it is listed.
     */
    public Changes synchronize(Collection<Path> files, Collection<Path> sourcesJars, Collection<Path> classJars)
            throws IOException {
        Map<Path, Entry> nextEntries = new LinkedHashMap<>();
        Map<Path, Archive> nextArchives = new LinkedHashMap<>();
        Sync sync = new Sync(System.currentTimeMillis(), limits);
        try (sync) {
            for (Path given : files) {
                Path file = normalize(given);
                if (nextEntries.containsKey(file)) continue;
                Entry entry = sync.file(file, entries.get(file));
                if (entry != null) nextEntries.put(file, entry);
            }
            for (Path given : sourcesJars) {
                Path jar = normalize(given);
                if (nextArchives.containsKey(jar)) continue;
                Archive archive = sync.archive(jar, archives.get(jar), false);
                if (archive != null) nextArchives.put(jar, archive);
            }
            for (Path given : classJars) {
                Path jar = normalize(given);
                if (nextArchives.containsKey(jar)) continue;
                Archive archive = sync.archive(jar, archives.get(jar), true);
                if (archive != null) nextArchives.put(jar, archive);
            }
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

    /**
     * One synchronize's worth of bookkeeping: the parse thread, started only if something needs parsing, and the
     * tally.
     */
    private static final class Sync implements AutoCloseable {
        final long startedAt;
        final Limits limits;
        final List<Path> added = new ArrayList<>();
        final List<Path> changed = new ArrayList<>();
        int unchanged;
        private ParseWorker worker;

        Sync(long startedAt, Limits limits) {
            this.startedAt = startedAt;
            this.limits = limits;
        }

        ParseWorker worker() {
            if (worker == null) worker = new ParseWorker(limits);
            return worker;
        }

        @Override
        public void close() {
            if (worker != null) worker.close();
        }

        /** The file's entry as it stands now, or null when it no longer exists. */
        Entry file(Path file, Entry old) throws IOException {
            Read read = read(file, old == null ? null : new Stamp(old.size(), old.modified(), old.digest()), true);
            Source source = new Source.OnDisk(file);
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
                case Read.TooLarge(long size, long modified) -> {
                    (old == null ? added : changed).add(file);
                    return new Entry(source, size, modified, new byte[0], List.of(), List.of(), tooLarge(size));
                }
                case Read.Fresh(byte[] bytes, long modified, byte[] digest) -> {
                    List<Symbol> symbols = new ArrayList<>();
                    List<Reference> references = new ArrayList<>();
                    String problem = worker().collect(source, bytes, false, null, symbols, references);
                    (old == null ? added : changed).add(file);
                    return new Entry(source, bytes.length, modified, digest, symbols, references, problem);
                }
            }
        }

        /** The jar's archive, read for its sources or its classes, as it stands now; null if it no longer exists. */
        Archive archive(Path jar, Archive old, boolean compiled) throws IOException {
            if (old != null && old.compiled() != compiled) old = null;   // read the other way before: start over
            Read read = read(jar, old == null ? null : new Stamp(old.size(), old.modified(), old.digest()), false);
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
                case Read.TooLarge t -> throw new IllegalStateException("a jar is hashed as a stream, never held");
                case Read.Fresh(byte[] unread, long modified, byte[] digest) -> {
                    (old == null ? added : changed).add(jar);
                    List<String> problems = new ArrayList<>();
                    List<Entry> entries;
                    try {
                        entries = compiled ? classEntriesOf(jar, problems) : entriesOf(jar, old, problems);
                    } catch (IOException unreadable) {
                        // A jar the zip reader refuses is a problem of that jar, not of the synchronize.
                        problems.add("not a readable zip: " + unreadable.getMessage());
                        entries = List.of();
                    }
                    return new Archive(jar, Files.size(jar), modified, digest, entries, problems, compiled);
                }
            }
        }

        /**
         * Every {@code .java} entry of a jar, by name, reusing what {@code old} knew of any whose content is the
         * same — unless {@code module-info.java} changed, since that can change what every other entry exports.
         * {@code META-INF} is skipped: what it holds is versioned copies of the entries outside it, or not Java.
         *
         * <p>Entries are found through the jar's central directory, as {@link Source.InArchive#read} finds them
         * again: a jar whose local headers tell a different story from its directory cannot show one text to the
         * index and another to the reader.
         */
        private List<Entry> entriesOf(Path jar, Archive old, List<String> problems) throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Map<String, ZipEntry> named = new TreeMap<>();
                for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                    ZipEntry e = all.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".java") || name.startsWith("META-INF/")) continue;
                    named.putIfAbsent(name, e);
                }
                if (named.size() > limits.maxArchiveEntries()) {
                    problems.add("has " + named.size() + " Java entries; only the first " + limits.maxArchiveEntries()
                            + " by name are indexed");
                }

                Map<String, Entry> known = new LinkedHashMap<>();
                if (old != null) {
                    old.entries().forEach(e -> known.put(((Source.InArchive) e.source()).entry(), e));
                }
                long[] budget = {limits.maxArchiveSourceBytes()};
                byte[] moduleInfo = named.containsKey(MODULE_INFO) ? take(zip, named.get(MODULE_INFO), budget) : null;
                Entry oldModuleInfo = known.get(MODULE_INFO);
                boolean exportsSame = moduleInfo == null
                        ? oldModuleInfo == null
                        : oldModuleInfo != null && Arrays.equals(oldModuleInfo.digest(), digest(moduleInfo));
                Set<String> exported = moduleInfo == null ? null : worker().exportsOf(moduleInfo);

                List<Entry> out = new ArrayList<>();
                for (Map.Entry<String, ZipEntry> each : named.entrySet()) {
                    if (out.size() == limits.maxArchiveEntries()) break;
                    String name = each.getKey();
                    Source at = new Source.InArchive(jar, name);
                    boolean budgetBinds = budget[0] < limits.maxSourceBytes();
                    byte[] content = name.equals(MODULE_INFO) ? moduleInfo : take(zip, each.getValue(), budget);
                    if (content == null && budgetBinds) {
                        problems.add("holds more than " + limits.maxArchiveSourceBytes()
                                + " bytes of source; entries from " + name + " on are not indexed");
                        break;
                    }
                    if (content == null) {
                        long claimed = each.getValue().getSize();
                        out.add(new Entry(at, claimed, 0, new byte[0], List.of(), List.of(), tooLarge(claimed)));
                        continue;
                    }
                    byte[] digest = digest(content);
                    Entry was = known.get(name);
                    if (exportsSame && was != null && Arrays.equals(was.digest(), digest)) {
                        out.add(was);
                        continue;
                    }
                    List<Symbol> symbols = new ArrayList<>();
                    String problem = worker().collect(at, content, true, exported, symbols, List.of());
                    out.add(new Entry(at, content.length, 0, digest, symbols, List.of(), problem));
                }
                return out;
            }
        }

        /**
         * A jar's exported declarations, read from its class files and grouped by top-level class: one entry per
         * top-level class, its source the {@link Source.Decompiled} that class is shown as. Class reading is fast
         * and needs no parser, so a changed jar is simply read again. A class file the reader refuses is that
         * class's problem; {@code META-INF}, multi-release copies included, is skipped.
         */
        private List<Entry> classEntriesOf(Path jar, List<String> problems) throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                List<ZipEntry> classes = new ArrayList<>();
                ZipEntry moduleInfo = null;
                for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                    ZipEntry e = all.nextElement();
                    String name = e.getName();
                    if (e.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    if (name.equals("module-info.class")) moduleInfo = e;
                    else classes.add(e);
                }
                classes.sort(java.util.Comparator.comparing(ZipEntry::getName));
                if (classes.size() > limits.maxArchiveEntries()) {
                    problems.add("has " + classes.size() + " class files; only the first " + limits.maxArchiveEntries()
                            + " by name are indexed");
                    classes = classes.subList(0, limits.maxArchiveEntries());
                }

                long[] budget = {limits.maxArchiveSourceBytes()};
                Set<String> exported = null;
                if (moduleInfo != null) {
                    byte[] bytes = take(zip, moduleInfo, budget);
                    try {
                        exported = bytes == null ? null : ClassFiles.exportsOf(bytes);
                    } catch (RuntimeException | StackOverflowError broken) {
                        problems.add("module-info.class could not be read, so every package counts as exported: "
                                + broken);
                    }
                }

                List<ClassFiles.Read> read = new ArrayList<>();
                for (ZipEntry e : classes) {
                    boolean budgetBinds = budget[0] < limits.maxSourceBytes();
                    byte[] bytes = take(zip, e, budget);
                    if (bytes == null && budgetBinds) {
                        problems.add("holds more than " + limits.maxArchiveSourceBytes()
                                + " bytes of classes; entries from " + e.getName() + " on are not indexed");
                        break;
                    }
                    if (bytes == null) {
                        problems.add(e.getName() + ": " + tooLarge(e.getSize()));
                        continue;
                    }
                    try {
                        read.add(ClassFiles.read(e.getName(), bytes));
                    } catch (RuntimeException | StackOverflowError broken) {
                        problems.add(e.getName() + ": not a class file this reader accepts (" + broken.getMessage()
                                + "), so not indexed");
                    }
                }

                List<Entry> out = new ArrayList<>();
                ClassFiles.symbols(jar, read, exported).forEach((entry, symbols) -> out.add(new Entry(
                        new Source.Decompiled(jar, entry), 0, 0, new byte[0], symbols, List.of(), "")));
                return out;
            }
        }

        /** An entry's bytes within both the per-file limit and what is left of the jar's budget; null if over. */
        private byte[] take(ZipFile zip, ZipEntry entry, long[] budget) throws IOException {
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = ParseWorker.readBounded(in, Math.min(limits.maxSourceBytes(), budget[0]));
                if (bytes != null) budget[0] -= bytes.length;
                return bytes;
            }
        }

        private String tooLarge(long size) {
            return "over the " + limits.maxSourceBytes() + "-byte limit for one source"
                    + (size >= 0 ? " (" + size + " bytes)" : "") + ", so not indexed";
        }

        /**
         * Whether a file has changed since it was stamped {@code old}: untouched by its stat, the same once read
         * and hashed, too large to index, or fresh content to parse. Null when there is no file. A file read with
         * {@code keep} false is hashed as a stream and never held whole, and its {@code Fresh} carries no bytes.
         */
        private Read read(Path file, Stamp old, boolean keep) throws IOException {
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
            // Stamped with the stat taken before the read: if the file changed in between, the stamp is stale
            // and the next synchronize looks again, which errs the safe way.
            long stamp = modified >= startedAt - RACY_MILLIS ? UNTRUSTED : modified;
            if (keep && stat.size() > limits.maxSourceBytes()) {
                return new Read.TooLarge(stat.size(), stamp);
            }

            byte[] bytes = null;
            byte[] digest;
            try {
                if (keep) {
                    try (InputStream in = Files.newInputStream(file)) {
                        bytes = ParseWorker.readBounded(in, limits.maxSourceBytes());
                    }
                    if (bytes == null) return new Read.TooLarge(-1, stamp);   // grew past the limit since the stat
                    digest = digest(bytes);
                } else {
                    digest = digestOf(file);
                }
            } catch (NoSuchFileException gone) {
                return null;
            }
            if (old != null && Arrays.equals(old.digest(), digest)) {
                return new Read.Same(bytes != null ? bytes.length : stat.size(), stamp);
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
        /** Over {@link Limits#maxSourceBytes}: stamped, so it is not read again until it changes, and not indexed. */
        record TooLarge(long size, long modified) implements Read {}
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

    /** SHA-256 of a file read as a stream, so a jar of any size costs a buffer, not its size in memory. */
    static byte[] digestOf(Path file) throws IOException {
        MessageDigest sha = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return sha.digest();
    }

    static byte[] digest(byte[] bytes) {
        return sha256().digest(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform is required to provide SHA-256", e);
        }
    }
}
