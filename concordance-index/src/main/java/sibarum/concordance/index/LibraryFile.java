package sibarum.concordance.index;

import sibarum.intrabuf.Compat;
import sibarum.intrabuf.IntrabufException;
import sibarum.intrabuf.annotation.Field;
import sibarum.intrabuf.annotation.IntrabufMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Concordance's own library format: a library's source text and its index, prebuilt, in one file that takes the
 * place of its sources jar.
 *
 * <p>The file <em>is</em> a sources jar. Its {@code .java} entries sit at the root, where any sources jar keeps them,
 * and what Concordance adds goes under {@code META-INF/concordance/}, which indexing a sources jar skips:
 *
 * <pre>
 *   a/b/C.java                          the text an answer points into: the library's own source, or for a
 *   ...                                 library without sources, each top-level class as Vineflower decompiled it
 *   META-INF/concordance/library.ib     {@link Manifest}: what the file is and what its index was built by
 *   META-INF/concordance/index.ib       {@link Index}: what each entry exports, in the jar cache's terms
 * </pre>
 *
 * <p>Both {@code .ib} entries are intrabuf payloads. The manifest is BACKWARD, so a newer Concordance reads an older
 * file's manifest; the index is STRICT and carries {@link JarCache#fingerprint}, and is used only by a Concordance
 * that would have built exactly that index. Any other Concordance reindexes the file from its text, as it would a
 * sources jar, so a library file never stops working: the index is what makes it fast, not what makes it usable.
 * An index that cannot be decoded, however it got that way, is an index that is not there.
 *
 * <p>In a Maven repository it sits beside the artifact with the classifier {@code concordance}
 * ({@code guava-33.0-concordance.jar}), and is taken in preference to the {@code -sources.jar}. That and
 * {@code --sources <file>} are how one is imported; {@link #export} is how one is made.
 *
 * <p>A library without sources is decompiled once, when its file is made, and the decompiled text indexed as source:
 * its declarations have real positions, and showing one needs no decompiler. A class Vineflower cannot decompile, or
 * whose decompiled text does not parse, keeps the declarations its class file gives, placed at the top of a short
 * text saying so; a declaration the decompiled text leaves out, such as a default constructor, is placed at its type.
 */
public final class LibraryFile {

    /** The classifier a library file has in a Maven repository. */
    public static final String CLASSIFIER = "concordance";

    static final String MANIFEST = "META-INF/concordance/library.ib";
    static final String INDEX = "META-INF/concordance/index.ib";

    /** What the manifest may take, read; it holds a few strings, so anything near this is not one. */
    private static final int MAX_MANIFEST_BYTES = 1 << 20;

    /** Zip entries carry a local time; one fixed time makes a file a function of what it holds. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 2, 1, 0, 0);

    private LibraryFile() {}

    /** Whether the file's text is the library's own source or what its classes decompiled to. */
    enum Origin { SOURCES, CLASSES }

    /**
     * What a library file is.
     *
     * @param origin           what the text was made from
     * @param madeFrom         the SHA-256, in hex, of the jar the file was made from
     * @param indexFingerprint the {@link JarCache#fingerprint} of the Concordance that built the index
     * @param exportedPackages for a file made from classes, the packages its {@code module-info.class} exports to
     *                         everyone; null when every package counts, or when the text holds a
     *                         {@code module-info.java} that says
     */
    @IntrabufMessage(name = "concordance.LibraryManifest", compat = Compat.BACKWARD)
    record Manifest(@Field(nonNull = true) Origin origin,
                    @Field(nonNull = true) String madeFrom,
                    @Field(nonNull = true) String indexFingerprint,
                    List<String> exportedPackages) {}

    /**
     * What each entry of one jar exports: the index a library file carries, and what the {@link JarCache} keeps for
     * each jar. Every string is an index into {@code strings}, since owners, qualified names and kinds repeat a great
     * deal and intrabuf writes a string wherever it occurs.
     *
     * <p>{@code compiled} says what the entries name. In a library file they are always {@code .java} entries, placed
     * as {@link Source.InArchive}; a jar the cache read for its classes has one entry per top-level class file, placed
     * as {@link Source.Decompiled}. Neither kind records a modification time or references, which an archive's
     * entries never have.
     */
    @IntrabufMessage(name = "concordance.LibraryIndex")
    record Index(boolean compiled,
                 @Field(nonNull = true) List<String> strings,
                 @Field(nonNull = true) List<Integer> problems,
                 @Field(nonNull = true) List<IndexedEntry> entries) {}

    /** One {@code .java} entry: its name, its stamp, what it exports, and why it could not be indexed, or empty. */
    @IntrabufMessage(name = "concordance.LibraryEntry")
    record IndexedEntry(int name, long size, @Field(nonNull = true) byte[] digest,
                        @Field(nonNull = true) List<IndexedSymbol> symbols, int problem) {}

    /** One exported declaration; {@code kind} names a {@link Symbol.Kind}, so reordering the enum cannot alias. */
    @IntrabufMessage(name = "concordance.LibrarySymbol")
    record IndexedSymbol(int kind, int name, int owner, int qualified, int line, int column,
                         @Field(nonNull = true) List<Integer> supers) {}

    /**
     * What opening a library file found: the packages its manifest says are exported (null when its text decides),
     * its index when this Concordance can use it, and anything worth reporting about the file.
     */
    record Opened(Set<String> exportedPackages, Optional<Contents> contents, Optional<String> problem) {}

    /** What an {@link Index} holds, its sources placed in the jar it is loaded for. */
    record Contents(List<ProjectIndex.Entry> entries, List<String> problems) {}

    // --- making one ---------------------------------------------------------------------------

    /**
     * Where {@link #export} puts the library file for {@code jar} by default: beside it, named as Maven names the
     * {@code concordance} classifier of the same artifact — {@code a-1.0.jar} and {@code a-1.0-sources.jar} both
     * give {@code a-1.0-concordance.jar}.
     */
    public static Path defaultFor(Path jar) {
        String name = jar.getFileName().toString();
        String stem = name.endsWith(".jar") ? name.substring(0, name.length() - ".jar".length()) : name;
        if (stem.endsWith("-sources")) stem = stem.substring(0, stem.length() - "-sources".length());
        return jar.toAbsolutePath().resolveSibling(stem + "-" + CLASSIFIER + ".jar");
    }

    /**
     * Makes the library file for {@code jar} at {@code out}: from its sources if it holds any {@code .java} entries,
     * else from its classes, decompiled. The write goes to a sibling first and is moved into place.
     *
     * @throws IOException when the jar cannot be read, or indexing it was interrupted
     */
    public static Path export(Path jar, Path out, Limits limits) throws IOException {
        jar = jar.toAbsolutePath().normalize();
        byte[] digest = ProjectIndex.digestOf(jar);
        boolean compiled = !holdsSources(jar);

        ProjectIndex scratch = ProjectIndex.empty(jar.getParent(), limits);
        scratch.synchronize(List.of(), compiled ? List.of() : List.of(jar), compiled ? List.of(jar) : List.of());
        ProjectIndex.Archive archive = scratch.archiveEntries().iterator().next();
        boolean interrupted = archive.problems().stream().anyMatch(p -> p.startsWith("interrupted"))
                || archive.entries().stream().anyMatch(e -> e.problem().startsWith("interrupted"));
        if (interrupted) throw new IOException("indexing " + jar + " was interrupted");

        Map<String, byte[]> texts = new TreeMap<>();
        List<ProjectIndex.Entry> entries = new ArrayList<>();
        Set<String> exported = null;
        if (compiled) {
            exported = exportsOfClasses(jar, limits);
            decompiled(jar, archive, exported, limits, texts, entries);
        } else {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ProjectIndex.Entry entry : archive.entries()) {
                    String name = ((Source.InArchive) entry.source()).entry();
                    byte[] text = read(zip, name, limits.maxSourceBytes());
                    if (text != null) texts.put(name, text);   // one over the limit is indexed as nothing, and shown as nothing
                    entries.add(entry);
                }
            }
        }

        Manifest manifest = new Manifest(compiled ? Origin.CLASSES : Origin.SOURCES, HexFormat.of().formatHex(digest),
                JarCache.fingerprint(limits), exported == null ? null : List.copyOf(exported));
        write(out, texts, LibraryFile_ManifestIntrabuf.toBytes(manifest),
                LibraryFile_IndexIntrabuf.toBytes(index(false, entries, archive.problems())));
        return out;
    }

    /**
     * Each top-level class decompiled and its text indexed as source, or, where that fails, its class file's
     * declarations placed at the top of a text that says why.
     */
    private static void decompiled(Path jar, ProjectIndex.Archive archive, Set<String> exported, Limits limits,
                                   Map<String, byte[]> texts, List<ProjectIndex.Entry> entries) {
        try (ParseWorker worker = new ParseWorker(limits)) {
            for (ProjectIndex.Entry entry : archive.entries()) {
                String classEntry = ((Source.Decompiled) entry.source()).entry();
                String name = classEntry.substring(0, classEntry.length() - ".class".length()) + ".java";
                Source at = new Source.InArchive(jar, name);
                List<Symbol> symbols = new ArrayList<>();
                String why;
                byte[] text = null;
                try {
                    text = Decompilation.of(jar, classEntry, limits).getBytes(StandardCharsets.UTF_8);
                    String problem = worker.collect(at, text, true, exported, symbols, new ArrayList<>());
                    why = problem == null ? null : "its decompiled text does not parse: " + problem;
                } catch (IOException failed) {
                    why = failed.getMessage();
                }
                if (why != null) {
                    String qualified = name.substring(0, name.length() - ".java".length()).replace('/', '.');
                    text = ("// " + qualified + " could not be shown as source when this library file was made:\n// "
                            + why.replace("\n", "\n// ") + "\n").getBytes(StandardCharsets.UTF_8);
                    symbols.clear();
                }
                placeTheRest(entry.symbols(), at, symbols);
                texts.put(name, text);
                entries.add(new ProjectIndex.Entry(at, text.length, 0, ProjectIndex.digest(text), symbols, List.of(),
                        entry.problem()));
            }
        }
    }

    /**
     * Adds to {@code parsed} each declaration of the class file that the text did not show — a decompiler leaves out
     * what the compiler would put back, a default constructor say — placed as {@link Source.Decompiled#locate}
     * places one: at its type when the text declares it, else at the top.
     */
    private static void placeTheRest(List<Symbol> fromClasses, Source at, List<Symbol> parsed) {
        Map<String, SourceRef> placed = new LinkedHashMap<>();
        for (Symbol s : parsed) placed.putIfAbsent(ClassFiles.normalized(s.qualified()), s.at());
        for (Symbol s : fromClasses) {
            if (placed.containsKey(ClassFiles.normalized(s.qualified()))) continue;
            SourceRef where = placed.getOrDefault(ClassFiles.normalized(s.owner()), new SourceRef(at, 1, 1));
            parsed.add(new Symbol(s.kind(), s.name(), s.owner(), s.qualified(), where, s.supers()));
        }
    }

    /** A jar's entries and problems as an {@link Index}, its entries of the kind {@code compiled} says. */
    static Index index(boolean compiled, List<ProjectIndex.Entry> entries, List<String> problems) {
        Map<String, Integer> table = new LinkedHashMap<>();
        List<Integer> problemRefs = problems.stream().map(p -> ref(table, p)).toList();
        List<IndexedEntry> indexed = new ArrayList<>();
        for (ProjectIndex.Entry entry : entries) {
            List<IndexedSymbol> symbols = new ArrayList<>();
            for (Symbol s : entry.symbols()) {
                symbols.add(new IndexedSymbol(ref(table, s.kind().name()), ref(table, s.name()), ref(table, s.owner()),
                        ref(table, s.qualified()), s.at().line(), s.at().column(),
                        s.supers().stream().map(p -> ref(table, p)).toList()));
            }
            String name = switch (entry.source()) {
                case Source.InArchive a when !compiled -> a.entry();
                case Source.Decompiled d when compiled -> d.entry();
                default -> throw new IllegalArgumentException("not an entry of a jar read this way: " + entry.source());
            };
            indexed.add(new IndexedEntry(ref(table, name), entry.size(), entry.digest(), symbols,
                    ref(table, entry.problem())));
        }
        return new Index(compiled, List.copyOf(table.keySet()), problemRefs, indexed);
    }

    private static int ref(Map<String, Integer> table, String s) {
        return table.computeIfAbsent(s, k -> table.size());
    }

    private static void write(Path out, Map<String, byte[]> texts, byte[] manifest, byte[] index) throws IOException {
        Path target = out.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".partial");
        try {
            try (OutputStream raw = Files.newOutputStream(partial); ZipOutputStream zip = new ZipOutputStream(raw)) {
                put(zip, MANIFEST, manifest);
                put(zip, INDEX, index);
                for (Map.Entry<String, byte[]> text : texts.entrySet()) put(zip, text.getKey(), text.getValue());
            }
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Whether the jar holds any {@code .java} entry outside {@code META-INF}: a sources jar, to be read as one. */
    private static boolean holdsSources(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry e = all.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".java") && !e.getName().startsWith("META-INF/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What a jar's {@code module-info.class} exports to everyone; null when it has none, or it cannot be read. */
    private static Set<String> exportsOfClasses(Path jar, Limits limits) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            byte[] bytes = read(zip, "module-info.class", limits.maxSourceBytes());
            if (bytes == null) return null;
            Set<String> exports = ClassFiles.exportsOf(bytes);
            return exports == null ? null : new LinkedHashSet<>(exports);
        } catch (RuntimeException | StackOverflowError unreadable) {
            return null;   // as indexing the classes treats it: every package counts as exported
        }
    }

    // --- reading one --------------------------------------------------------------------------

    /**
     * What the library file at {@code jar} holds for an index built within {@code limits}, its sources placed in
     * {@code jar}; empty when {@code jar} is not a library file, only a sources jar. Never fails on what the file
     * holds: a manifest or index that cannot be decoded is reported, and the file is read as a sources jar.
     *
     * @throws IOException only when the file cannot be opened as a zip at all
     */
    static Optional<Opened> open(Path jar, Limits limits) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry(MANIFEST) == null) return Optional.empty();
            Manifest manifest;
            try {
                byte[] bytes = read(zip, MANIFEST, MAX_MANIFEST_BYTES);
                if (bytes == null) return Optional.of(unusable("its manifest is over " + MAX_MANIFEST_BYTES + " bytes"));
                manifest = LibraryFile_ManifestIntrabuf.fromBytes(bytes);
            } catch (IntrabufException | IOException unreadable) {
                return Optional.of(unusable("its manifest cannot be read (" + unreadable.getMessage() + ")"));
            }
            Set<String> exported = manifest.exportedPackages() == null ? null
                    : new LinkedHashSet<>(manifest.exportedPackages());
            if (!manifest.indexFingerprint().equals(JarCache.fingerprint(limits))) {
                // Built by another Concordance, or within other limits: not a problem, just not a shortcut.
                return Optional.of(new Opened(exported, Optional.empty(), Optional.empty()));
            }
            try {
                byte[] bytes = read(zip, INDEX, limits.maxArchiveSourceBytes());
                if (bytes == null) {
                    return Optional.of(new Opened(exported, Optional.empty(),
                            Optional.of("its prebuilt index is missing or over the limit, so it is indexed from its text")));
                }
                Index index = LibraryFile_IndexIntrabuf.fromBytes(bytes);
                if (index.compiled()) throw new IllegalArgumentException("its entries are class files, not text");
                return Optional.of(new Opened(exported, Optional.of(contents(index, jar)), Optional.empty()));
            } catch (RuntimeException | IOException broken) {
                // Truncated, from another build, or strings out of the table: no index here to use.
                return Optional.of(new Opened(exported, Optional.empty(),
                        Optional.of("its prebuilt index cannot be read (" + broken.getMessage()
                                + "), so it is indexed from its text")));
            }
        }
    }

    private static Opened unusable(String why) {
        return new Opened(null, Optional.empty(), Optional.of(why + ", so it is indexed as a sources jar"));
    }

    /**
     * What {@code index} holds, its sources placed in {@code jar}.
     *
     * @throws RuntimeException when the index does not hold together: a string out of its table, a kind that does
     *                          not exist
     */
    static Contents contents(Index index, Path jar) {
        String[] table = index.strings().toArray(String[]::new);
        List<String> problems = index.problems().stream().map(i -> table[i]).toList();
        List<ProjectIndex.Entry> entries = new ArrayList<>();
        for (IndexedEntry e : index.entries()) {
            Source source = index.compiled() ? new Source.Decompiled(jar, table[e.name()])
                    : new Source.InArchive(jar, table[e.name()]);
            List<Symbol> symbols = new ArrayList<>();
            for (IndexedSymbol s : e.symbols()) {
                symbols.add(new Symbol(Symbol.Kind.valueOf(table[s.kind()]), table[s.name()], table[s.owner()],
                        table[s.qualified()], new SourceRef(source, s.line(), s.column()),
                        s.supers().stream().map(i -> table[i]).toList()));
            }
            entries.add(new ProjectIndex.Entry(source, e.size(), 0, e.digest(), symbols, List.of(), table[e.problem()]));
        }
        return new Contents(entries, problems);
    }

    /** An entry's bytes, within {@code limit}; null when it is absent or over. */
    private static byte[] read(ZipFile zip, String name, long limit) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return ParseWorker.readBounded(in, limit);
        }
    }
}
