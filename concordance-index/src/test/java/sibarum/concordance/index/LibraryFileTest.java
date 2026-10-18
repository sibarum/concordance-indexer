package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A library file: a sources jar with its index prebuilt, used when it can be and reindexed when it cannot. */
class LibraryFileTest {

    @TempDir Path dir;

    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();
    static {
        LIBRARY.put("lib/api/Widget.java", """
                package lib.api;

                public class Widget implements Runnable {
                    public static final int SIZE = 3;
                    protected int count;
                    int hidden;

                    public Widget() {}

                    public void run() {}
                    protected void layout(String name, int times) {}
                    private void helper() {}

                    public static class Gear { public void turn() {} }
                }
                """);
        LIBRARY.put("lib/api/Shade.java", """
                package lib.api;

                public enum Shade { LIGHT, DARK }
                """);
        LIBRARY.put("lib/internal/Hidden.java", """
                package lib.internal;

                class Hidden { public void no() {} }
                """);
    }

    private Path sourcesJar() throws IOException {
        Path jar = dir.resolve("repo/lib-1.0-sources.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (Map.Entry<String, String> e : LIBRARY.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar.toAbsolutePath().normalize();
    }

    private Path classJar() throws IOException {
        Path src = dir.resolve("src");
        Path out = dir.resolve("classes");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "21"));
        for (Map.Entry<String, String> e : LIBRARY.entrySet()) {
            Path file = src.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            args.add(file.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args.toArray(String[]::new)), "the test library compiles");
        Path jar = dir.resolve("repo/lib-1.0.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(raw);
             Stream<Path> classes = Files.walk(out)) {
            for (Path c : classes.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
        }
        return jar.toAbsolutePath().normalize();
    }

    private ProjectIndex indexed(Path jar, List<ProjectIndex.Changes> changes) throws IOException {
        ProjectIndex live = ProjectIndex.empty(dir);
        changes.add(live.synchronize(List.of(), List.of(jar)));
        return live;
    }

    /** Each declaration as kind, qualified name and position, which is what a library file must give back. */
    private static Set<String> placed(ProjectIndex live) {
        return live.index().symbols().stream()
                .map(s -> s.kind() + " " + s.qualified() + " " + s.line() + ":" + s.at().column() + " " + s.supers())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> normalized(ProjectIndex live) {
        return live.index().symbols().stream().map(s -> s.kind() + " " + ClassFiles.normalized(s.qualified()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void aLibraryFileGivesBackTheIndexItsSourcesWouldWithoutParsingThem() throws IOException {
        Path sources = sourcesJar();
        Path file = LibraryFile.export(sources, LibraryFile.defaultFor(sources), Limits.DEFAULT);
        assertEquals(dir.resolve("repo/lib-1.0-concordance.jar").toAbsolutePath().normalize(), file.toAbsolutePath().normalize());

        List<ProjectIndex.Changes> fromSources = new ArrayList<>();
        List<ProjectIndex.Changes> fromFile = new ArrayList<>();
        ProjectIndex expected = indexed(sources, fromSources);
        ProjectIndex actual = indexed(file, fromFile);

        assertEquals(placed(expected), placed(actual));
        assertEquals(0, fromSources.getFirst().prebuilt());
        assertEquals(1, fromFile.getFirst().prebuilt(), "the file's own index is used, not a parse");
        assertTrue(actual.problems().isEmpty(), actual.problems().toString());

        Symbol widget = actual.index().symbols().stream().filter(s -> s.qualified().equals("lib.api.Widget"))
                .findFirst().orElseThrow();
        assertEquals(new Source.InArchive(file.toAbsolutePath().normalize(), "lib/api/Widget.java"), widget.source());
        assertEquals(LIBRARY.get("lib/api/Widget.java"), widget.source().text(), "an answer opens in the file itself");
        assertTrue(actual.index().symbols().stream().noneMatch(s -> s.qualified().startsWith("lib.internal")),
                "only what the library exports");
    }

    @Test
    void anIndexBuiltByAnotherConcordanceIsRebuiltFromTheFilesText() throws IOException {
        Path sources = sourcesJar();
        Limits other = new Limits(Limits.DEFAULT.maxSourceBytes(), Limits.DEFAULT.maxArchiveSourceBytes(),
                Limits.DEFAULT.maxArchiveEntries() - 1, Limits.DEFAULT.parseStackBytes(), Limits.DEFAULT.decompileMillis());
        Path file = LibraryFile.export(sources, dir.resolve("lib.concordance.jar"), other);

        List<ProjectIndex.Changes> changes = new ArrayList<>();
        ProjectIndex live = indexed(file, changes);
        assertEquals(0, changes.getFirst().prebuilt(), "a fingerprint that is not ours is not trusted");
        assertEquals(placed(indexed(sources, new ArrayList<>())), placed(live), "but the text is, as a sources jar's is");
        assertTrue(live.problems().isEmpty(), "another Concordance's index is no problem: " + live.problems());
    }

    @Test
    void aBrokenIndexIsAMissingOneAndTheFileIsStillASourcesJar() throws IOException {
        Path sources = sourcesJar();
        Path good = LibraryFile.export(sources, dir.resolve("good.jar"), Limits.DEFAULT);
        Path broken = dir.resolve("broken.jar");
        try (ZipFile in = new ZipFile(good.toFile());
             OutputStream raw = Files.newOutputStream(broken); ZipOutputStream out = new ZipOutputStream(raw)) {
            for (Enumeration<? extends ZipEntry> all = in.entries(); all.hasMoreElements(); ) {
                ZipEntry e = all.nextElement();
                byte[] bytes;
                try (InputStream s = in.getInputStream(e)) {
                    bytes = s.readAllBytes();
                }
                if (e.getName().equals(LibraryFile.INDEX)) bytes = java.util.Arrays.copyOf(bytes, bytes.length / 2);
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(bytes);
                out.closeEntry();
            }
        }

        List<ProjectIndex.Changes> changes = new ArrayList<>();
        ProjectIndex live = indexed(broken, changes);
        assertEquals(0, changes.getFirst().prebuilt());
        assertEquals(placed(indexed(sources, new ArrayList<>())), placed(live));
        assertEquals(1, live.problems().size(), live.problems().toString());
        assertTrue(live.problems().getFirst().contains("prebuilt index cannot be read"), live.problems().toString());
    }

    @Test
    void aLibraryWithoutSourcesIsDecompiledOnceAndPlacedForReal() throws IOException {
        Path classes = classJar();
        Path file = LibraryFile.export(classes, LibraryFile.defaultFor(classes), Limits.DEFAULT);

        List<ProjectIndex.Changes> changes = new ArrayList<>();
        ProjectIndex live = indexed(file, changes);
        assertEquals(1, changes.getFirst().prebuilt());
        assertTrue(live.problems().isEmpty(), live.problems().toString());

        ProjectIndex fromClasses = ProjectIndex.empty(dir);
        fromClasses.synchronize(List.of(), List.of(), List.of(classes));
        assertEquals(normalized(fromClasses), normalized(live),
                "the file declares what the class files do, a default constructor and values() included");

        for (Symbol s : live.index().symbols()) {
            assertInstanceOf(Source.InArchive.class, s.source(), "shown from the file, not from a decompiler");
            assertTrue(s.line() > 0, s.qualified() + " has a real position");
            List<String> lines = s.source().text().lines().toList();
            // A declaration starts at its annotations, so look a few lines on for its name.
            String line = String.join("\n", lines.subList(s.line() - 1, Math.min(lines.size(), s.line() + 3)));
            String type = s.owner().substring(s.owner().lastIndexOf('.') + 1);
            assertTrue(line.contains(s.name()) || line.contains(type),
                    s.qualified() + " is on its line, or one the compiler implied is on its type's: " + line);
        }
    }

    @Test
    void theSameJarMakesTheSameFile() throws IOException {
        Path sources = sourcesJar();
        byte[] first = Files.readAllBytes(LibraryFile.export(sources, dir.resolve("a.jar"), Limits.DEFAULT));
        byte[] second = Files.readAllBytes(LibraryFile.export(sources, dir.resolve("b.jar"), Limits.DEFAULT));
        assertArrayEquals(first, second);
    }

    @Test
    void aSourcesJarIsNotALibraryFile() throws IOException {
        assertTrue(LibraryFile.open(sourcesJar(), Limits.DEFAULT).isEmpty());
    }
}
