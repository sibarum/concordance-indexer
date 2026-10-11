package sibarum.concordance.index;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A {@link ProjectIndex} as bytes on disk.
 *
 * <p>Written by hand with {@link DataOutputStream} rather than by Java serialization, which would need
 * reflection metadata to cross native-image — the same reason the parser runs at RAW.
 *
 * <pre>
 *   int     magic "CNCD"
 *   string  fingerprint — what wrote this; see {@link #fingerprint()}
 *   int     string count, then each string           every string below is an index into this table
 *   int     file count, then per file:
 *             string  path, relative to the project root when it is under it
 *             stamp   long size, long modified, int digest length, digest bytes
 *             body
 *   int     archive count, then per sources jar:
 *             string  path, relative to the project root when it is under it
 *             stamp
 *             int     problem count, then each problem string
 *             int     entry count, then per entry:
 *                       string entry name; stamp; body
 *
 *   body:   int     symbol count, then per symbol:
 *                     string kind, name, owner, qualified; int line, column; int super count, strings
 *           int     reference count, then per reference:
 *                     string kind, target, name, from; int line, column
 *           string  why the source could not be indexed, or empty
 *   int     end marker
 * </pre>
 *
 * <p>A string is an int byte length and that many bytes of UTF-8. Kinds are written by name, not ordinal, so
 * reordering an enum cannot silently turn one kind into another.
 *
 * <p><b>Invalidation.</b> A saved index is only usable by a Concordance that would have built the same index,
 * and the fingerprint is how a reader knows. It carries {@link #FORMAT}, {@link IndexBuilder#VERSION} and every
 * symbol and reference kind, and a reader that does not find exactly its own fingerprint refuses the file. The
 * kinds are read from the enums, so adding or renaming one invalidates old indexes without anyone remembering
 * to; the two versions are bumped by hand, and a test pins the record shapes so a change to them that forgets
 * to bump {@link #FORMAT} fails the build rather than a user's editor.
 */
final class IndexCodec {

    /** The byte layout above. Bump it whenever the layout, or any record written through it, changes shape. */
    static final int FORMAT = 3;

    private static final int MAGIC = 0x434E4344;       // "CNCD"
    private static final int END = 0x2F434E43;         // "/CNC"
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_DIGEST_BYTES = 64;

    private IndexCodec() {}

    /** Everything that decides whether a saved index is one this build would produce. */
    static String fingerprint() {
        return "concordance-index format=" + FORMAT
                + " builder=" + IndexBuilder.VERSION
                + " symbols=" + Arrays.stream(Symbol.Kind.values()).map(Enum::name).collect(Collectors.joining(","))
                + " references=" + Arrays.stream(Reference.Kind.values()).map(Enum::name)
                .collect(Collectors.joining(","));
    }

    // --- writing ------------------------------------------------------------------------------

    static void write(ProjectIndex index, Path file) throws IOException {
        // The body is written first, so the string table it fills can be written ahead of it.
        Map<String, Integer> table = new LinkedHashMap<>();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);

        body.writeInt(index.entries().size());
        for (ProjectIndex.Entry entry : index.entries()) {
            writeRef(body, table, pathFor(index.root(), ((Source.OnDisk) entry.source()).file()));
            writeStamp(body, entry.size(), entry.modified(), entry.digest());
            writeBody(body, table, entry);
        }

        body.writeInt(index.archiveEntries().size());
        for (ProjectIndex.Archive archive : index.archiveEntries()) {
            writeRef(body, table, pathFor(index.root(), archive.file()));
            writeStamp(body, archive.size(), archive.modified(), archive.digest());
            body.writeInt(archive.problems().size());
            for (String problem : archive.problems()) {
                writeRef(body, table, problem);
            }
            body.writeInt(archive.entries().size());
            for (ProjectIndex.Entry entry : archive.entries()) {
                writeRef(body, table, ((Source.InArchive) entry.source()).entry());
                writeStamp(body, entry.size(), entry.modified(), entry.digest());
                writeBody(body, table, entry);
            }
        }
        body.flush();

        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path partial = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".partial");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(partial)))) {
                out.writeInt(MAGIC);
                writeString(out, fingerprint());
                out.writeInt(table.size());
                for (String s : table.keySet()) {
                    writeString(out, s);
                }
                bytes.writeTo(out);
                out.writeInt(END);
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

    private static void writeStamp(DataOutputStream out, long size, long modified, byte[] digest) throws IOException {
        out.writeLong(size);
        out.writeLong(modified);
        out.writeInt(digest.length);
        out.write(digest);
    }

    /** What one source contributed. Its positions are written without the source, which the reader knows. */
    private static void writeBody(DataOutputStream out, Map<String, Integer> table, ProjectIndex.Entry entry)
            throws IOException {
        out.writeInt(entry.symbols().size());
        for (Symbol symbol : entry.symbols()) {
            writeRef(out, table, symbol.kind().name());
            writeRef(out, table, symbol.name());
            writeRef(out, table, symbol.owner());
            writeRef(out, table, symbol.qualified());
            out.writeInt(symbol.at().line());
            out.writeInt(symbol.at().column());
            out.writeInt(symbol.supers().size());
            for (String parent : symbol.supers()) {
                writeRef(out, table, parent);
            }
        }

        out.writeInt(entry.references().size());
        for (Reference reference : entry.references()) {
            writeRef(out, table, reference.kind().name());
            writeRef(out, table, reference.target());
            writeRef(out, table, reference.name());
            writeRef(out, table, reference.from());
            out.writeInt(reference.at().line());
            out.writeInt(reference.at().column());
        }
        writeRef(out, table, entry.problem());
    }

    /** Relative to the root with {@code /} between names when under it, so a moved checkout keeps its index. */
    private static String pathFor(Path root, Path file) {
        if (!file.startsWith(root)) return file.toString();
        List<String> names = new ArrayList<>();
        root.relativize(file).forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    private static void writeRef(DataOutputStream out, Map<String, Integer> table, String s) throws IOException {
        out.writeInt(table.computeIfAbsent(s, k -> table.size()));
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    // --- reading ------------------------------------------------------------------------------

    static Optional<ProjectIndex> read(Path file, Path root, Limits limits) throws IOException {
        InputStream raw;
        long maxCount;
        try {
            raw = Files.newInputStream(file);
            // Every count below is of things at least a byte long, so none can honestly exceed the file's size:
            // a count that does is not trusted with an allocation.
            maxCount = Files.size(file);
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            if (in.readInt() != MAGIC) return Optional.empty();
            if (!fingerprint().equals(readString(in))) return Optional.empty();

            String[] table = new String[count(in, maxCount)];
            for (int i = 0; i < table.length; i++) {
                table[i] = readString(in);
            }

            Map<Path, ProjectIndex.Entry> entries = new LinkedHashMap<>();
            int files = count(in, maxCount);
            for (int f = 0; f < files; f++) {
                Path path = root.resolve(table[in.readInt()]).normalize();
                entries.put(path, readEntry(in, maxCount, table, new Source.OnDisk(path)));
            }

            Map<Path, ProjectIndex.Archive> archives = new LinkedHashMap<>();
            int jars = count(in, maxCount);
            for (int j = 0; j < jars; j++) {
                Path path = root.resolve(table[in.readInt()]).normalize();
                long size = in.readLong();
                long modified = in.readLong();
                byte[] digest = readDigest(in);
                List<String> problems = new ArrayList<>();
                int problemCount = count(in, maxCount);
                for (int p = 0; p < problemCount; p++) {
                    problems.add(table[in.readInt()]);
                }
                List<ProjectIndex.Entry> inside = new ArrayList<>();
                int entryCount = count(in, maxCount);
                for (int e = 0; e < entryCount; e++) {
                    inside.add(readEntry(in, maxCount, table, new Source.InArchive(path, table[in.readInt()])));
                }
                archives.put(path, new ProjectIndex.Archive(path, size, modified, digest, inside, problems));
            }

            if (in.readInt() != END || in.read() != -1) return Optional.empty();
            return Optional.of(new ProjectIndex(root, entries, archives, limits));
        } catch (EOFException | RuntimeException notAnIndex) {
            // Truncated, or bytes that are not this format: a string index out of the table, a kind that does
            // not exist, a negative count. Either way there is no index here to use.
            return Optional.empty();
        }
    }

    /** One source's stamp and body, its positions placed in {@code source}. */
    private static ProjectIndex.Entry readEntry(DataInputStream in, long maxCount, String[] table, Source source)
            throws IOException {
        long size = in.readLong();
        long modified = in.readLong();
        byte[] digest = readDigest(in);

        List<Symbol> symbols = new ArrayList<>();
        int symbolCount = count(in, maxCount);
        for (int i = 0; i < symbolCount; i++) {
            Symbol.Kind kind = Symbol.Kind.valueOf(table[in.readInt()]);
            String name = table[in.readInt()];
            String owner = table[in.readInt()];
            String qualified = table[in.readInt()];
            SourceRef at = new SourceRef(source, in.readInt(), in.readInt());
            List<String> supers = new ArrayList<>();
            int superCount = count(in, maxCount);
            for (int s = 0; s < superCount; s++) {
                supers.add(table[in.readInt()]);
            }
            symbols.add(new Symbol(kind, name, owner, qualified, at, supers));
        }

        List<Reference> references = new ArrayList<>();
        int referenceCount = count(in, maxCount);
        for (int i = 0; i < referenceCount; i++) {
            Reference.Kind kind = Reference.Kind.valueOf(table[in.readInt()]);
            String target = table[in.readInt()];
            String name = table[in.readInt()];
            String from = table[in.readInt()];
            SourceRef at = new SourceRef(source, in.readInt(), in.readInt());
            references.add(new Reference(kind, target, name, from, at));
        }

        String problem = table[in.readInt()];
        return new ProjectIndex.Entry(source, size, modified, digest, symbols, references, problem);
    }

    private static byte[] readDigest(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_DIGEST_BYTES) {
            throw new IllegalArgumentException("implausible digest length " + length);
        }
        byte[] digest = in.readNBytes(length);
        if (digest.length != length) throw new EOFException();
        return digest;
    }

    private static int count(DataInputStream in, long max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("implausible count " + n);
        return n;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("implausible string length " + length);
        }
        byte[] utf8 = in.readNBytes(length);
        if (utf8.length != length) throw new EOFException();
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
