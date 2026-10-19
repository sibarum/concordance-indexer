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

    private ProjectIndex project(String name, JarCache cache) throws IOException {
        return ProjectIndex.empty(Files.createDirectories(dir.resolve(name))).useJarCache(cache);
    }

    private long cached() throws IOException {
        Path c = dir.resolve("cache");
        if (!Files.isDirectory(c)) return 0;
        try (Stream<Path> files = Files.list(c)) {
            return files.filter(JarCacheTest::isEntry).count();
        }
    }

    /** A jar index, as opposed to the cache's bookkeeping beside them. */
    private static boolean isEntry(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".sources") || name.endsWith(".classes");
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
            Path entry = files.filter(JarCacheTest::isEntry).findFirst().orElseThrow();
            Files.write(entry, "not an index".getBytes(StandardCharsets.UTF_8));
        }
        ProjectIndex second = project("two", cache());
        assertEquals(0, second.synchronize(List.of(), List.of(jar)).fromCache());
        assertEquals(first.index(), second.index());
        assertEquals(1, project("three", cache()).synchronize(List.of(), List.of(jar)).fromCache(), "replaced");
    }

    @Test
    void anEntryInTheOldFormatIsAMissAndIsReplaced() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        ProjectIndex first = project("one", cache());
        first.synchronize(List.of(), List.of(jar));
        try (Stream<Path> files = Files.list(dir.resolve("cache"))) {
            Path entry = files.filter(JarCacheTest::isEntry).findFirst().orElseThrow();
            // What 0.1.0 wrote: "CNCJ", its fingerprint, then an empty string table, problems and entries, "/CNC".
            byte[] fingerprint = JarCache.fingerprint(Limits.DEFAULT).getBytes(StandardCharsets.UTF_8);
            try (var out = new java.io.DataOutputStream(Files.newOutputStream(entry))) {
                out.writeInt(0x434E434A);
                out.writeInt(fingerprint.length);
                out.write(fingerprint);
                out.writeInt(0);
                out.writeInt(0);
                out.writeInt(0);
                out.writeInt(0x2F434E43);
            }
        }
        ProjectIndex second = project("two", cache());
        assertEquals(0, second.synchronize(List.of(), List.of(jar)).fromCache());
        assertEquals(first.index(), second.index());
        assertEquals(1, project("three", cache()).synchronize(List.of(), List.of(jar)).fromCache(), "replaced");
    }

    @Test
    void anEntryOfTheOtherKindIsAMiss() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        project("one", cache()).synchronize(List.of(), List.of(jar));
        try (Stream<Path> files = Files.list(dir.resolve("cache"))) {
            Path entry = files.filter(JarCacheTest::isEntry).findFirst().orElseThrow();
            String name = entry.getFileName().toString();
            Files.move(entry, entry.resolveSibling(name.replace(".sources", ".classes")));
        }
        assertEquals(0, project("two", cache()).synchronize(List.of(), List.of(), List.of(jar)).fromCache(),
                "an index of .java entries cannot stand for the jar's classes");
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

    // --- the stamp shortcut --------------------------------------------------------------------

    /** Pushes a file's modification time safely into the past, so a stamp of it is trusted. */
    private static void settle(Path file) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(60)));
    }

    @Test
    void aJarWhoseStatMatchesItsStampIsNotEvenRead() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        settle(jar);
        ProjectIndex first = project("one", cache());
        first.synchronize(List.of(), List.of(jar));

        // Same size, same modification time, different bytes: only a reader would notice.
        FileTime stamp = Files.getLastModifiedTime(jar);
        Files.write(jar, new byte[(int) Files.size(jar)]);
        Files.setLastModifiedTime(jar, stamp);

        ProjectIndex second = project("two", cache());
        ProjectIndex.Changes changes = second.synchronize(List.of(), List.of(jar));
        assertEquals(1, changes.fromCache(), changes.toString());
        assertEquals(1, changes.unhashed(), "taken by its stamp: " + changes);
        assertEquals(first.index().symbols().size(), second.index().symbols().size());
        assertTrue(second.problems().isEmpty(), "the garbage was never read: " + second.problems());
    }

    @Test
    void aJarWrittenTooRecentlyIsNotStampedSoItIsHashed() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));   // written just now: within the clock's resolution
        project("one", cache()).synchronize(List.of(), List.of(jar));
        ProjectIndex.Changes changes = project("two", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, changes.fromCache(), "found by its hash instead");
        assertEquals(0, changes.unhashed());
    }

    @Test
    void aJarWhoseStatMovedIsHashedAndFoundByItsBytes() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        settle(jar);
        project("one", cache()).synchronize(List.of(), List.of(jar));
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().minusSeconds(120)));   // touched, not changed

        ProjectIndex.Changes changes = project("two", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, changes.fromCache());
        assertEquals(0, changes.unhashed(), "a stat that moved is not trusted; the bytes are");
        ProjectIndex.Changes again = project("three", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, again.unhashed(), "and the new stat is stamped for next time");
    }

    @Test
    void aStampIsForAPathNotForBytes() throws IOException {
        Path original = widgets(dir.resolve("repo-a/widgets.jar"));
        settle(original);
        Path copy = dir.resolve("repo-b/widgets.jar");
        Files.createDirectories(copy.getParent());
        Files.copy(original, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        project("one", cache()).synchronize(List.of(), List.of(original));

        ProjectIndex.Changes changes = project("two", cache()).synchronize(List.of(), List.of(copy));
        assertEquals(1, changes.fromCache(), "the copy is the same entry, found by its hash");
        assertEquals(0, changes.unhashed(), "but another path's stamp says nothing about this file");
    }

    @Test
    void aStampWhoseEntryWasPrunedLeadsBackToTheJarAndPruningDropsIt() throws IOException {
        Path jar = widgets(dir.resolve("repo/widgets.jar"));
        settle(jar);
        project("one", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, cache().prune(0));
        try (Stream<Path> stamps = Files.list(dir.resolve("cache/stamps"))) {
            assertEquals(0, stamps.count(), "a stamp for an entry that is gone is gone too");
        }
        ProjectIndex second = project("two", cache());
        ProjectIndex.Changes changes = second.synchronize(List.of(), List.of(jar));
        assertEquals(0, changes.fromCache());
        assertFalse(second.index().namesContaining("Widget").isEmpty(), "read from the jar again");
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

    // --- rot -----------------------------------------------------------------------------------

    /** A version of an artifact where a Maven repository keeps it, settled so it is stamped. */
    private Path release(String version) throws IOException {
        Path jar = jar(dir.resolve("repo/org/acme/gears/" + version + "/gears-" + version + ".jar"),
                "lib/Gear.java", "package lib; public class Gear { public int v" + version.replace('.', '_') + "; }");
        settle(jar);
        return jar;
    }

    private JarCache.Indexed indexOf(Path jar) throws IOException {
        return cache().indexes().stream().filter(i -> i.jars().contains(jar)).findFirst().orElseThrow();
    }

    private boolean isCached(Path jar) throws IOException {
        return cache().indexes().stream().anyMatch(i -> i.jars().contains(jar));
    }

    /** Makes the index of {@code jar} look last built or loaded {@code days} ago. */
    private void idle(Path jar, int days) throws IOException {
        JarCache.Indexed index = indexOf(jar);
        Files.setLastModifiedTime(dir.resolve("cache").resolve(index.digest() + ".sources"),
                FileTime.from(Instant.now().minus(java.time.Duration.ofDays(days))));
    }

    @Test
    void aNewBuildOfTheSameVersionDropsTheOldIndex() throws IOException {
        Path jar = jar(dir.resolve("repo/org/acme/gears/1.0-SNAPSHOT/gears-1.0-SNAPSHOT.jar"),
                "lib/Gear.java", "package lib; public class Gear {}");
        settle(jar);
        project("one", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, cached());

        jar(jar, "lib/Gear.java", "package lib; public class Gear { public int teeth; }");   // installed again
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().minusSeconds(30)));
        project("one", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, cached(), "the old build's index went when the new one came");
        assertEquals(1, project("two", cache()).synchronize(List.of(), List.of(jar)).fromCache(), "the new one is cached");
    }

    @Test
    void anOldBuildStillHeldAtAnotherPathIsKept() throws IOException {
        Path jar = jar(dir.resolve("repo/org/acme/gears/1.0-SNAPSHOT/gears-1.0-SNAPSHOT.jar"),
                "lib/Gear.java", "package lib; public class Gear {}");
        settle(jar);
        Path copy = dir.resolve("elsewhere/gears.jar");
        Files.createDirectories(copy.getParent());
        Files.copy(jar, copy, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
        project("one", cache()).synchronize(List.of(), List.of(jar, copy));

        jar(jar, "lib/Gear.java", "package lib; public class Gear { public int teeth; }");
        Files.setLastModifiedTime(jar, FileTime.from(Instant.now().minusSeconds(30)));
        project("one", cache()).synchronize(List.of(), List.of(jar, copy));
        assertEquals(2, cached(), "the copy still holds the old bytes");
    }

    @Test
    void anUnusedOutdatedIdleVersionIsSweptAndTheNewestNeverIs() throws IOException {
        Path old = release("1.0");
        Path current = release("2.0");
        project("one", cache()).synchronize(List.of(), List.of(old));
        project("one", cache()).synchronize(List.of(), List.of(current));   // moved on: nothing uses 1.0 now
        idle(old, 4);
        idle(current, 400);

        assertTrue(indexOf(old).stale(Instant.now()));
        assertFalse(indexOf(current).stale(Instant.now()), "nothing newer than 2.0 is cached");
        assertEquals(1, cache().sweep(Instant.now()));
        assertFalse(isCached(old));
        assertTrue(isCached(current));
    }

    @Test
    void anOutdatedVersionAProjectUsesIsKept() throws IOException {
        Path old = release("1.0");
        Path current = release("2.0");
        project("one", cache()).synchronize(List.of(), List.of(old));
        project("two", cache()).synchronize(List.of(), List.of(current));
        idle(old, 30);

        assertEquals(1, indexOf(old).projects());
        assertTrue(indexOf(old).outdated());
        assertEquals(0, cache().sweep(Instant.now()));
    }

    @Test
    void anOutdatedVersionUsedRecentlyIsKept() throws IOException {
        Path old = release("1.0");
        Path current = release("2.0");
        project("one", cache()).synchronize(List.of(), List.of(old));
        project("one", cache()).synchronize(List.of(), List.of(current));
        idle(old, 2);
        assertEquals(0, cache().sweep(Instant.now()), "idle 2 days, not 3");
        assertEquals(1, cache().sweep(Instant.now().plus(java.time.Duration.ofDays(2))));
    }

    @Test
    void aJarOutsideARepositoryHasNoVersionAndIsNeverSwept() throws IOException {
        Path loose = widgets(dir.resolve("lib/widgets.jar"));
        settle(loose);
        project("one", cache()).synchronize(List.of(), List.of(loose));
        project("one", cache()).synchronize(List.of(), List.of());
        idle(loose, 400);
        assertFalse(indexOf(loose).outdated());
        assertEquals(0, cache().sweep(Instant.now()));
    }

    @Test
    void aSynchronizeSweepsWhenASweepIsDue() throws IOException {
        Path old = release("1.0");
        Path current = release("2.0");
        project("one", cache()).synchronize(List.of(), List.of(old));
        project("one", cache()).synchronize(List.of(), List.of(current));
        idle(old, 4);
        project("one", cache()).synchronize(List.of(), List.of(current));
        assertTrue(isCached(old), "swept within the day already");

        Files.setLastModifiedTime(dir.resolve("cache/swept"), FileTime.from(Instant.now().minus(java.time.Duration.ofDays(2))));
        project("one", cache()).synchronize(List.of(), List.of(current));
        assertFalse(isCached(old));
    }

    @Test
    void anArtifactIsReadFromItsPlaceInARepository() {
        Path base = Path.of("m2", "org", "acme", "gears");
        var release = JarCache.artifactOf(base.resolve("1.0/gears-1.0.jar")).orElseThrow();
        var sources = JarCache.artifactOf(base.resolve("1.0/gears-1.0-sources.jar")).orElseThrow();
        var newer = JarCache.artifactOf(base.resolve("1.10/gears-1.10.jar")).orElseThrow();
        var snapshot = JarCache.artifactOf(base.resolve("2.0-SNAPSHOT/gears-2.0-20261011.120000-3.jar")).orElseThrow();
        assertTrue(release.olderThan(newer), "1.0 < 1.10, by Maven's ordering");
        assertFalse(sources.olderThan(newer), "a sources jar is not a version of the main jar");
        assertTrue(newer.olderThan(snapshot));
        assertEquals("2.0-SNAPSHOT", snapshot.version());
        assertTrue(JarCache.artifactOf(base.resolve("1.0/other-1.0.jar")).isEmpty());
        assertTrue(JarCache.artifactOf(Path.of("lib", "gears.jar")).isEmpty());
    }

    @Test
    void purgingEmptiesTheCache() throws IOException {
        Path jar = release("1.0");
        project("one", cache()).synchronize(List.of(), List.of(jar));
        assertEquals(1, cache().purge());
        assertEquals(0, cached());
        assertEquals(0, cache().overheadBytes(), "stamps and project records went too");
        assertEquals(0, project("two", cache()).synchronize(List.of(), List.of(jar)).fromCache());
    }
}
