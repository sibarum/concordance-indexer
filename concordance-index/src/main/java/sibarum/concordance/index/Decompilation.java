package sibarum.concordance.index;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Java text for a library class that came without its source, made by Vineflower when somebody asks to see it.
 *
 * <p>Only display comes from here. What a class declares is read from the class file itself (see
 * {@link ClassFiles}), which is exact and fast; decompiling is neither, so it is done one class at a time, on
 * demand, and its result is not trusted to parse — a class whose decompiled text does not parse can still be
 * shown, its declarations just are not placed in it (see {@link Source#locate}).
 *
 * <p>A class is decompiled with its nested classes, with the rest of its jar as a library for resolving names, and
 * within the same budgets as everything else read from a jar: each class file read through
 * {@link Limits#maxSourceBytes}, the text no larger than that either, and the whole decompilation done within
 * {@link Limits#decompileMillis} on a thread of its own. A decompilation that overruns is abandoned: the caller gets
 * an {@link IOException} at once. The abandoned thread is a daemon that finishes, or not, on its own; Java offers no
 * way to stop it, which is why the budget is generous and the result is cached.
 *
 * <p>Vineflower never runs the code it reads. Output is deterministic for a given class and Vineflower version.
 */
final class Decompilation {

    /** What the text of a class depends on besides its bytes: the decompiler and how it is asked. */
    static final String VERSION = "vineflower-1.12.0;indent=4";

    private static final int CACHED = 32;

    private record Key(Path jar, String entry, long size, long modified) {}

    private static final Map<Key, String> cache = new LinkedHashMap<>(CACHED, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, String> eldest) {
            return size() > CACHED;
        }
    };

    private Decompilation() {}

    /**
     * The decompiled text of the top-level class at {@code classEntry} in {@code jar}, nested classes included.
     *
     * @throws IOException when the class is not there, a class file is over the limit, the text would be, or
     *                     decompiling it overran the time budget or failed outright
     */
    static String of(Path jar, String classEntry, Limits limits) throws IOException {
        Key key = new Key(jar, classEntry, Files.size(jar), Files.getLastModifiedTime(jar).toMillis());
        synchronized (cache) {
            String known = cache.get(key);
            if (known != null) return known;
        }
        String text = decompile(jar, classEntry, limits);
        synchronized (cache) {
            cache.put(key, text);
        }
        return text;
    }

    private static String decompile(Path jar, String classEntry, Limits limits) throws IOException {
        if (!classEntry.endsWith(".class")) throw new IOException(classEntry + " is not a class file");
        String internalName = classEntry.substring(0, classEntry.length() - ".class".length());
        String[] text = new String[1];
        // The caller owns the jar, not the decompiling thread, so that abandoning a decompilation closes it at
        // once: an open file cannot be replaced on Windows, and a runaway thread must not keep a jar in the local
        // repository from being reinstalled. A closed jar also tends to end the runaway at its next read.
        ZipFile zip = new ZipFile(jar.toFile());
        FutureTask<Void> work = new FutureTask<>(() -> {
            {
                List<String> own = new ArrayList<>();
                List<String> all = new ArrayList<>();
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
                    String path = name.substring(0, name.length() - ".class".length());
                    all.add(path);
                    if (path.equals(internalName) || path.startsWith(internalName + "$")) own.add(path);
                }
                if (!own.contains(internalName)) throw new IOException(jar + "!/" + classEntry + " is not there");
                Map<String, String> out = new LinkedHashMap<>();
                Decompiler.builder()
                        .inputs(new Classes(zip, own, limits, out))
                        .libraries(new Classes(zip, all, limits, null))
                        .output(NO_FILES)
                        .logger(IFernflowerLogger.NO_OP)
                        .option("banner", "")
                        .option("thread-count", "1")
                        .option("decompile-inner", "1")
                        .option("indent-string", "    ")
                        .build()
                        .decompile();
                text[0] = out.get(internalName);
            }
            return null;
        });
        Thread thread = new Thread(null, work, "concordance-decompile", limits.parseStackBytes());
        thread.setDaemon(true);
        thread.start();
        try {
            work.get(limits.decompileMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            thread.interrupt();
            zip.close();
            throw new IOException("decompiling " + jar + "!/" + classEntry + " took longer than "
                    + limits.decompileMillis() + " ms; abandoned");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while decompiling " + classEntry, e);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof OutOfMemoryError oom) throw oom;
            throw new IOException("Vineflower could not decompile " + jar + "!/" + classEntry + ": " + cause, cause);
        } finally {
            zip.close();
        }
        if (text[0] == null) throw new IOException("Vineflower produced nothing for " + jar + "!/" + classEntry);
        if (text[0].length() > limits.maxSourceBytes()) {
            throw new IOException("decompiled " + classEntry + " is over the " + limits.maxSourceBytes()
                    + "-byte limit for one source");
        }
        return text[0];
    }

    /**
     * Some of a jar's classes, as Vineflower reads its input: each class file through the size limit. As input it
     * collects the text written for each class into {@code out}; as a library, {@code out} is null and it is only
     * read from.
     */
    private record Classes(ZipFile zip, List<String> paths, Limits limits, Map<String, String> out)
            implements IContextSource {

        @Override
        public String getName() {
            return zip.getName();
        }

        @Override
        public Entries getEntries() {
            return new Entries(paths.stream().map(Entry::atBase).toList(), List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resource) throws IOException {
            String name = resource.endsWith(".class") ? resource : resource + ".class";
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) return null;
            try (InputStream in = zip.getInputStream(entry)) {
                byte[] bytes = ParseWorker.readBounded(in, limits.maxSourceBytes());
                if (bytes == null) {
                    throw new IOException(name + " is over the " + limits.maxSourceBytes() + "-byte limit");
                }
                return new ByteArrayInputStream(bytes);
            }
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override public void begin() {}

                @Override
                public void acceptClass(String qualifiedName, String fileName, String content, int[] mapping) {
                    if (out != null && content != null) out.put(qualifiedName, content);
                }

                @Override public void acceptDirectory(String directory) {}

                @Override public void acceptOther(String path) {}

                @Override public void close() {}
            };
        }
    }

    /** Vineflower's file-writing side, unused: every class's text arrives through {@link Classes}' sink. */
    private static final IResultSaver NO_FILES = new IResultSaver() {
        @Override public void saveFolder(String path) {}

        @Override public void copyFile(String source, String path, String entryName) {}

        @Override public void saveClassFile(String path, String qualifiedName, String entryName, String content,
                                            int[] mapping) {}

        @Override public void createArchive(String path, String archiveName, Manifest manifest) {}

        @Override public void saveDirEntry(String path, String archiveName, String entryName) {}

        @Override public void copyEntry(String source, String path, String archiveName, String entry) {}

        @Override public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName,
                                             String content) {}

        @Override public void closeArchive(String path, String archiveName) {}
    };
}
