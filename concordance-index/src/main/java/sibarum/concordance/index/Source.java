package sibarum.concordance.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where a piece of indexed source text lives: a file on disk, or an entry inside an archive such as a
 * {@code -sources.jar}.
 *
 * <p>An index answer has to be somewhere a caret can go, and not every such place is a file — a library's source
 * arrives inside a jar, and the editor shows it from there. So positions name a {@code Source}, and
 * {@link #read} hands back the exact bytes the positions were taken from, whichever kind it is.
 *
 * <p>A zip-filesystem {@link Path} would have spared the type, and was passed over: one only works while its
 * filesystem stays open, loses the archive from its {@code toString}, is not equal to the same entry reached
 * through a second opening, and would have to be saved as a {@code jar:} URI. A record has none of those problems.
 */
public sealed interface Source {

    /** A file on disk — project source, which an editor can open and change. */
    record OnDisk(Path file) implements Source {

        public OnDisk {
            if (file == null) {
                throw new IllegalArgumentException("OnDisk file must be non-null");
            }
        }

        @Override
        public byte[] read() throws IOException {
            try (InputStream in = Files.newInputStream(file)) {
                byte[] bytes = ParseWorker.readBounded(in, Limits.DEFAULT.maxSourceBytes());
                if (bytes == null) {
                    throw new IOException(file + " is over the " + Limits.DEFAULT.maxSourceBytes()
                            + "-byte limit for one source");
                }
                return bytes;
            }
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public String toString() {
            return file.toString();
        }
    }

    /**
     * An entry inside an archive — library source, which an editor shows but does not change.
     *
     * @param archive the archive on disk
     * @param entry   the entry's name inside it, {@code /}-separated as zip writes it
     */
    record InArchive(Path archive, String entry) implements Source {

        public InArchive {
            if (archive == null || entry == null || entry.isEmpty()) {
                throw new IllegalArgumentException("InArchive needs an archive and a non-empty entry");
            }
        }

        @Override
        public byte[] read() throws IOException {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry found = zip.getEntry(entry);
                if (found == null) {
                    throw new NoSuchFileException(toString());
                }
                // Through the central directory and within the same limit as indexing, so what is read back is
                // exactly what was indexed, and a zip bomb is no bigger here than it was there.
                try (InputStream in = zip.getInputStream(found)) {
                    byte[] bytes = ParseWorker.readBounded(in, Limits.DEFAULT.maxSourceBytes());
                    if (bytes == null) {
                        throw new IOException(this + " is over the " + Limits.DEFAULT.maxSourceBytes()
                                + "-byte limit for one source");
                    }
                    return bytes;
                }
            }
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        /** {@code archive!/entry}, the spelling a {@code jar:} URL and most tools use. */
        @Override
        public String toString() {
            return archive + "!/" + entry;
        }
    }

    /**
     * A library class that came without its source, shown as Vineflower decompiles it: the top-level class at
     * {@code entry} in {@code archive}, with its nested classes. Its declarations were read from the class files
     * and start unplaced, at line 0; {@link #locate} finds them in the text.
     *
     * @param archive the jar on disk
     * @param entry   the top-level class file's entry, {@code a/b/Outer.class}
     */
    record Decompiled(Path archive, String entry) implements Source {

        public Decompiled {
            if (archive == null || entry == null || !entry.endsWith(".class")) {
                throw new IllegalArgumentException("Decompiled needs an archive and a .class entry: " + entry);
            }
        }

        /** The decompiled text, within {@link Limits#DEFAULT}'s size and time budgets. */
        @Override
        public byte[] read() throws IOException {
            return text().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String text() throws IOException {
            return Decompilation.of(archive, entry, Limits.DEFAULT);
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        /**
         * Decompiles the class, parses the text, and finds the declaration in it: the member itself when the
         * decompiled text declares it recognisably, else its type, else the top of the class. Decompiled text
         * that does not parse, which obfuscated bytecode can produce, places everything at the top.
         */
        @Override
        public SourceRef locate(Symbol symbol) throws IOException {
            if (!symbol.source().equals(this) || symbol.line() > 0) return symbol.at();
            String text = text();
            List<Symbol> declared = new ArrayList<>();
            try (ParseWorker worker = new ParseWorker(Limits.DEFAULT)) {
                worker.collect(this, text.getBytes(StandardCharsets.UTF_8), false, null, declared, new ArrayList<>());
            }
            Map<String, SourceRef> placed = new HashMap<>();
            for (Symbol d : declared) placed.putIfAbsent(ClassFiles.normalized(d.qualified()), d.at());
            SourceRef found = placed.get(ClassFiles.normalized(symbol.qualified()));
            if (found == null) found = placed.get(ClassFiles.normalized(symbol.owner()));
            return found != null ? found : new SourceRef(this, 1, 1);
        }

        /** {@code archive!/entry}, the spelling a {@code jar:} URL and most tools use. */
        @Override
        public String toString() {
            return archive + "!/" + entry;
        }
    }

    /** A file on disk, made absolute and normalized so one file is one source however it was spelled. */
    static Source of(Path file) {
        return new OnDisk(file.toAbsolutePath().normalize());
    }

    /** The bytes this source holds now — the ones the index took its positions from, if it has not changed. */
    byte[] read() throws IOException;

    /** The text this source holds now, decoded as the index decodes it to parse it. */
    default String text() throws IOException {
        return new String(read(), StandardCharsets.UTF_8);
    }

    /** Whether an editor should refuse to change this source: true for anything that is not a file of its own. */
    boolean readOnly();

    /**
     * Where {@code symbol}, declared in this source, is in this source's text. Its own position, except for a
     * declaration read from a class file, which is unplaced until its class is decompiled; see
     * {@link Decompiled#locate}.
     */
    default SourceRef locate(Symbol symbol) throws IOException {
        return symbol.at();
    }
}
