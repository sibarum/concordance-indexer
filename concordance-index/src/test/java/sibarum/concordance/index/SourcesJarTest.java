package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Library source arriving as a sources jar: what is taken from it, where it is said to be, and when it is reread. */
class SourcesJarTest {

    private static final String WIDGET = """
            package lib.api;

            public class Widget {
                public static final int SIZE = 3;
                protected int count;
                int hidden;
                private int secret;

                public Widget() {}
                Widget(int n) {}

                public void show() { helper(); new Local(); }
                protected void layout() {}
                void packagePrivate() {}
                private void helper() { class Local {} }

                public static class Part { public void fit() {} }
                protected interface Hook { void fire(); private void inner() {} }
                private static class Gear { public void turn() {} }
                static class Spring {}
            }

            class Helper { public void assist() {} }
            """;

    private static final String SHADE = """
            package lib.api;

            public enum Shade {
                LIGHT, DARK;
                Shade() {}
                public boolean light() { return this == LIGHT; }
            }
            """;

    private static final String POINT = "package lib.api;\npublic record Point(int x, int y) { private static int ORIGIN = 0; }\n";

    private static Path jar(Path file, Map<String, String> entries) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file.toAbsolutePath().normalize();
    }

    private static Path widgets(Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("lib/api/Widget.java", WIDGET);
        entries.put("lib/api/Shade.java", SHADE);
        entries.put("lib/api/Point.java", POINT);
        entries.put("META-INF/versions/21/lib/api/Widget.java", WIDGET.replace("Widget", "Gadget"));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n");
        return jar(dir.resolve("widgets-1.0-sources.jar"), entries);
    }

    private static ProjectIndex withJar(Path root, Path jar) throws IOException {
        ProjectIndex live = ProjectIndex.empty(root);
        live.synchronize(List.of(), List.of(jar));
        return live;
    }

    private static List<String> qualified(Index index) {
        return index.symbols().stream().map(Symbol::qualified).toList();
    }

    /** Pushes a file's modification time safely into the past, so its stat is trusted. */
    private static void settle(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
    }

    // --- what is taken ------------------------------------------------------------------------

    @Test
    void aLibraryContributesWhatItExportsAndNothingElse(@TempDir Path dir) throws IOException {
        Index index = withJar(dir, widgets(dir)).index();
        assertEquals(List.of(
                        "lib.api.Point", "lib.api.Point#x", "lib.api.Point#y",
                        "lib.api.Shade", "lib.api.Shade#light()",
                        "lib.api.Widget", "lib.api.Widget#show()", "lib.api.Widget#layout()",
                        "lib.api.Widget#<init>()", "lib.api.Widget#SIZE", "lib.api.Widget#count",
                        "lib.api.Widget.Part", "lib.api.Widget.Part#fit()",
                        "lib.api.Widget.Hook", "lib.api.Widget.Hook#fire()"),
                qualified(index),
                "public and protected members of exported types, interface members unless private, record "
                        + "components; never package-private, private, local, an enum constructor, or META-INF");
    }

    @Test
    void aLibraryContributesNoReferences(@TempDir Path dir) throws IOException {
        assertTrue(withJar(dir, widgets(dir)).index().references().isEmpty());
    }

    @Test
    void aModuleExportsOnlyThePackagesItNamesToEveryone(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("module-info.java", "module lib { exports lib.api; exports lib.friend to other; }");
        entries.put("lib/api/Open.java", "package lib.api; public class Open {}");
        entries.put("lib/friend/Shared.java", "package lib.friend; public class Shared {}");
        entries.put("lib/internal/Closed.java", "package lib.internal; public class Closed {}");
        Index index = withJar(dir, jar(dir.resolve("mod-sources.jar"), entries)).index();
        assertEquals(List.of("lib.api.Open"), qualified(index));
    }

    // --- where it is said to be ---------------------------------------------------------------

    @Test
    void aLibrarySymbolIsInItsEntryAndReadsBackAsTheTextItCameFrom(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir);
        Symbol show = withJar(dir, jar).index().namesContaining("show").getFirst();

        Source source = show.source();
        assertEquals(new Source.InArchive(jar, "lib/api/Widget.java"), source);
        assertTrue(source.readOnly());
        assertEquals(jar + "!/lib/api/Widget.java:12", show.at().toString());

        String line = source.text().lines().toList().get(show.line() - 1);
        assertEquals("public void show()", line.substring(show.at().column() - 1, show.at().column() + 17));
    }

    @Test
    void aMissingEntryReadsAsAMissingFile(@TempDir Path dir) throws IOException {
        Source gone = new Source.InArchive(widgets(dir), "lib/api/Gone.java");
        assertThrows(java.nio.file.NoSuchFileException.class, gone::read);
    }

    @Test
    void outlinesWorkInsideAnArchive(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir);
        List<Symbol> outline = withJar(dir, jar).index().declaredIn(new Source.InArchive(jar, "lib/api/Shade.java"));
        assertEquals(List.of("Shade", "light"), outline.stream().map(Symbol::name).toList());
    }

    // --- when it is reread --------------------------------------------------------------------

    @Test
    void anUnchangedJarIsNotOpened(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir);
        settle(jar);
        ProjectIndex live = withJar(dir, jar);
        Index before = live.index();

        // Same size, same modification time, different bytes: only a reader would notice, so none did.
        FileTime stamp = Files.getLastModifiedTime(jar);
        Files.write(jar, new byte[(int) Files.size(jar)]);
        Files.setLastModifiedTime(jar, stamp);

        ProjectIndex.Changes changes = live.synchronize(List.of(), List.of(jar));
        assertTrue(changes.isEmpty(), changes.toString());
        assertEquals(1, changes.unchanged());
        assertSame(before, live.index());
    }

    @Test
    void aRewrittenJarReparsesOnlyTheEntriesThatChanged(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir);
        ProjectIndex live = withJar(dir, jar);
        Map<String, ProjectIndex.Entry> before = entriesOf(live);

        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("lib/api/Widget.java", WIDGET);
        entries.put("lib/api/Shade.java", SHADE.replace("light()", "dim()"));
        entries.put("lib/api/Point.java", POINT);
        jar(jar, entries);

        ProjectIndex.Changes changes = live.synchronize(List.of(), List.of(jar));
        assertEquals(List.of(jar), changes.changed());
        Map<String, ProjectIndex.Entry> after = entriesOf(live);
        assertSame(before.get("lib/api/Widget.java"), after.get("lib/api/Widget.java"));
        assertSame(before.get("lib/api/Point.java"), after.get("lib/api/Point.java"));
        assertNotSame(before.get("lib/api/Shade.java"), after.get("lib/api/Shade.java"));
        assertTrue(qualified(live.index()).contains("lib.api.Shade#dim()"));
    }

    @Test
    void aChangedModuleInfoReconsidersEveryEntry(@TempDir Path dir) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("module-info.java", "module lib { exports lib.api; }");
        entries.put("lib/api/Open.java", "package lib.api; public class Open {}");
        entries.put("lib/internal/Closed.java", "package lib.internal; public class Closed {}");
        Path jar = jar(dir.resolve("mod-sources.jar"), entries);
        ProjectIndex live = withJar(dir, jar);

        entries.put("module-info.java", "module lib { exports lib.api; exports lib.internal; }");
        jar(jar, entries);
        live.synchronize(List.of(), List.of(jar));
        assertEquals(List.of("lib.api.Open", "lib.internal.Closed"), qualified(live.index()));
    }

    @Test
    void aJarNoLongerListedIsRemoved(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir);
        ProjectIndex live = withJar(dir, jar);
        ProjectIndex.Changes changes = live.synchronize(List.of());
        assertEquals(List.of(jar), changes.removed());
        assertTrue(live.index().symbols().isEmpty());
        assertTrue(live.archives().isEmpty());
    }

    @Test
    void filesComeBeforeJarsInTheIndex(@TempDir Path dir) throws IOException {
        Path jar = widgets(dir.resolve("repo"));
        Path file = dir.resolve("app/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package app; class App { void run() { new lib.api.Widget().show(); } }");

        ProjectIndex live = ProjectIndex.empty(dir.resolve("app"));
        live.synchronize(List.of(file), List.of(jar));
        List<Symbol> symbols = live.index().symbols();
        assertInstanceOf(Source.OnDisk.class, symbols.getFirst().source());
        assertInstanceOf(Source.InArchive.class, symbols.getLast().source());
        assertFalse(live.index().usagesOf("show").isEmpty(), "the project's references are still there");
    }

    // --- save and load ------------------------------------------------------------------------

    @Test
    void aSavedIndexKeepsItsJars(@TempDir Path dir, @TempDir Path repo, @TempDir Path cache) throws IOException {
        Path file = dir.resolve("App.java");
        Files.writeString(file, "class App {}");
        Path jar = widgets(repo);
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(file), List.of(jar));
        Path saved = cache.resolve("index.bin");
        live.save(saved);

        ProjectIndex loaded = ProjectIndex.load(saved, dir).orElseThrow();
        assertEquals(live.index(), loaded.index());
        assertEquals(List.of(jar), loaded.archives(), "a jar outside the project is kept by its absolute path");
        assertEquals(entriesOf(live).keySet(), entriesOf(loaded).keySet());
    }

    private static Map<String, ProjectIndex.Entry> entriesOf(ProjectIndex index) {
        Map<String, ProjectIndex.Entry> out = new LinkedHashMap<>();
        for (ProjectIndex.Archive archive : index.archiveEntries()) {
            for (ProjectIndex.Entry entry : archive.entries()) {
                out.put(((Source.InArchive) entry.source()).entry(), entry);
            }
        }
        return out;
    }
}
