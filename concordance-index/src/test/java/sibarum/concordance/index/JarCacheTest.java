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
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** One jar indexed once per machine: found again by its bytes, wherever they are, by whichever project. */
class JarCacheTest {

    @TempDir Path dir;

    private Path jar(Path file, String... nameThenSource) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (int i = 0; i < nameThenSource.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenSource[i]));
                zip.write(nameThenSource[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file.toAbsolutePath().normalize();
    }

    private Path widgets(Path file) throws IOException {
        return jar(file, "lib/Widget.java", "package lib; public class Widget { public void show() {} }",
                "lib/Gear.java", "package lib; public class Gear { public int teeth; }");
    }

    private JarCache cache() {
        return JarCache.at(dir.resolve("cache"));
    }

    private ProjectIndex project(String name, JarCache cache) {
        return ProjectIndex.empty(dir.resolve(name)).useJarCache(cache);
    }

    private long cached() throws IOException {
        Path c = dir.resolve("cache");
        if (!Files.isDirectory(c)) return 0;
        try (Stream<Path> files = Files.list(c)) {
            return files.count();
        }
    }

    // --- shared between projects ---------------------------------------------------------------

    @Test
    void aSecondProjectLoadsTheJarTheFirstIndexed() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        ProjectIndex first = project("one", cache());
        assertEquals(0, first.synchronize(List.of(), List.of(jar)).fromCache());
        assertEquals(1, cached());

        ProjectIndex second = project("two", cache());
        ProjectIndex.Changes changes = second.synchronize(List.of(), List.of(jar));
        assertEquals(1, changes.fromCache(), changes.toString());
        assertEquals(List.of(jar), changes.added(), "loaded from the cache is still added to this index");
        assertEquals(first.index(), second.index());
    }

    @Test
    void theSameBytesElsewhereAreTheSameEntryPlacedInTheirOwnJar() throws IOException {
        Path original = widgets(dir.resolve("repo-a/widgets.jar"));
        Path copy = dir.resolve("repo-b/widgets.jar");
        Files.createDirectories(copy.getParent());
        Files.copy(original, copy);
        project("one", cache()).synchronize(List.of(), List.of(original));

        ProjectIndex second = project("two", cache());
        assertEquals(1, second.synchronize(List.of(), List.of(copy)).fromCache());
        Symbol show = second.index().namesContaining("show").getFirst();
        assertEquals(new Source.InArchive(copy.toAbsolutePath().normalize(), "lib/Widget.java"), show.source(),
                "a cached index has no path of its own; it takes the jar's it is loaded for");
        assertTrue(show.source().text().contains("class Widget"));
    }

    @Test
    void aCompiledJarIsCachedAndItsClassesDecompileFromTheJarItIsLoadedFor() throws Exception {
        Path classes = dir.resolve("compiled");
        Path src = dir.resolve("src/lib/Widget.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package lib; public class Widget { public void show() {} }");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-d", classes.toString(), src.toString()));
        Path jar = dir.resolve("repo/widgets.jar");
        Files.createDirectories(jar.getParent());
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("lib/Widget.class"));
            zip.write(Files.readAllBytes(classes.resolve("lib/Widget.class")));
            zip.closeEntry();
        }
        project("one", cache()).synchronize(List.of(), List.of(), List.of(jar));

        ProjectIndex second = project("two", cache());
        assertEquals(1, second.synchronize(List.of(), List.of(), List.of(jar)).fromCache());
        Symbol show = second.index().namesContaining("show").getFirst();
        assertEquals(new Source.Decompiled(jar.toAbsolutePath().normalize(), "lib/Widget.class"), show.source());
        assertTrue(show.source().locate(show).line() > 1);
    }

    @Test
    void readForSourcesAndReadForClassesAreDifferentEntries() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        project("one", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(0, project("two", cache()).synchronize(List.of(), List.of(), List.of(jar)).fromCache());
        assertEquals(2, cached());
    }

    // --- when it misses ------------------------------------------------------------------------

    @Test
    void aJarThatChangedIsAMiss() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        project("one", cache()).synchronize(List.of(), List.of(jar));
        jar(jar, "lib/Other.java", "package lib; public class Other {}");
        ProjectIndex second = project("two", cache());
        assertEquals(0, second.synchronize(List.of(), List.of(jar)).fromCache());
        assertFalse(second.index().namesContaining("Other").isEmpty());
    }

    @Test
    void differentLimitsAreAMissAndTheEntryIsRewritten() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        project("one", cache()).synchronize(List.of(), List.of(jar));
        Limits other = new Limits(1 << 20, Limits.DEFAULT.maxArchiveSourceBytes(), Limits.DEFAULT.maxArchiveEntries(),
                Limits.DEFAULT.parseStackBytes(), Limits.DEFAULT.decompileMillis());
        ProjectIndex strict = ProjectIndex.empty(dir.resolve("two"), other).useJarCache(cache());
        assertEquals(0, strict.synchronize(List.of(), List.of(jar)).fromCache(),
                "an index built within other limits may say other things");
        ProjectIndex again = ProjectIndex.empty(dir.resolve("three"), other).useJarCache(cache());
        assertEquals(1, again.synchronize(List.of(), List.of(jar)).fromCache());
    }

    @Test
    void aCorruptEntryIsAMissAndIsReplaced() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        ProjectIndex first = project("one", cache());
        first.synchronize(List.of(), List.of(jar));
        try (Stream<Path> files = Files.list(dir.resolve("cache"))) {
            Path entry = files.findFirst().orElseThrow();
            Files.write(entry, "not an index".getBytes(StandardCharsets.UTF_8));
        }
        ProjectIndex second = project("two", cache());
        assertEquals(0, second.synchronize(List.of(), List.of(jar)).fromCache());
        assertEquals(first.index(), second.index());
        assertEquals(1, project("three", cache()).synchronize(List.of(), List.of(jar)).fromCache(), "replaced");
    }

    @Test
    void aCacheThatCannotBeWrittenIsACacheThatMissed() throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "a file where the cache directory would go");
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        ProjectIndex live = ProjectIndex.empty(dir.resolve("one")).useJarCache(JarCache.at(blocked));
        ProjectIndex.Changes changes = live.synchronize(List.of(), List.of(jar));
        assertEquals(List.of(jar), changes.added());
        assertFalse(live.index().namesContaining("Widget").isEmpty());
    }

    @Test
    void whatWentWrongWithAJarIsCachedWithIt() throws IOException {
        Path garbage = dir.resolve("repo/garbage.jar");
        Files.createDirectories(garbage.getParent());
        Files.writeString(garbage, "not a zip");
        ProjectIndex first = project("one", cache());
        first.synchronize(List.of(), List.of(garbage));
        ProjectIndex second = project("two", cache());
        assertEquals(1, second.synchronize(List.of(), List.of(garbage)).fromCache());
        assertEquals(first.problems().size(), second.problems().size());
        assertTrue(second.problems().getFirst().contains("not a readable zip"));
    }

    @Test
    void anInterruptedReadIsNotKeptAsTheJarsAnswer() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        ProjectIndex.Archive interrupted = new ProjectIndex.Archive(jar, 1, 1, new byte[32], List.of(),
                List.of("interrupted while parsing"), false);
        cache().store(interrupted, Limits.DEFAULT);
        assertEquals(0, cached());
    }

    // --- size ----------------------------------------------------------------------------------

    @Test
    void pruningDropsTheLeastRecentlyUsedFirst() throws IOException {
        Path old = widgets(dir.resolve("repo/old.jar"));
        Path recent = jar(dir.resolve("repo/recent.jar"), "lib/R.java", "package lib; public class R {}");
        project("one", cache()).synchronize(List.of(), List.of(old, recent));
        try (Stream<Path> files = Files.list(dir.resolve("cache"))) {
            for (Path f : files.toList()) Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(3600)));
        }
        project("two", cache()).synchronize(List.of(), List.of(recent));   // a hit marks it used

        long total = cache().sizeBytes();
        assertEquals(1, cache().prune(total - 1));
        assertEquals(0, project("three", cache()).synchronize(List.of(), List.of(old)).fromCache(), "old went");
        assertEquals(1, project("four", cache()).synchronize(List.of(), List.of(recent)).fromCache(), "recent stayed");
    }
}
