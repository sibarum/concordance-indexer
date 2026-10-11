package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexTest {

    private static final FileTime AN_HOUR_AGO = FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS));

    /** Writes a source file and, unless told otherwise, backdates it so its stamp is trusted. */
    private static Path write(Path dir, String name, String source) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Files.setLastModifiedTime(file, AN_HOUR_AGO);
        return file;
    }

    private static List<Path> sources(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    /** The index a from-scratch build would give, which a synchronized one must equal. */
    private static Index fresh(Path dir) throws IOException {
        return IndexBuilder.buildFiles(sources(dir).stream().map(p -> p.toAbsolutePath().normalize()).toList());
    }

    private static Path shapes(Path dir) throws IOException {
        write(dir, "demo/Shape.java", "package demo; interface Shape { double area(); }");
        write(dir, "demo/Circle.java", "package demo; class Circle implements Shape { public double area() { return 1; } }");
        write(dir, "demo/Scene.java", "package demo; class Scene { void go() { new Circle().area(); } }");
        return dir;
    }

    // --- synchronize --------------------------------------------------------------------------

    @Test
    void aFirstSynchronizeIsAFreshBuild(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        ProjectIndex.Changes changes = live.synchronize(sources(dir));

        assertEquals(3, changes.added().size(), changes.toString());
        assertEquals(fresh(dir), live.index());
    }

    @Test
    void anUntouchedTreeChangesNothing(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Index before = live.index();

        ProjectIndex.Changes changes = live.synchronize(sources(dir));
        assertTrue(changes.isEmpty(), changes.toString());
        assertEquals(3, changes.unchanged());
        assertSame(before, live.index(), "nothing changed, so nothing is reassembled");
    }

    @Test
    void anEditReparsesThatFileAlone(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));

        Path circle = write(dir, "demo/Circle.java",
                "package demo; class Circle implements Shape { public double area() { return 1; } void roll() {} }");
        ProjectIndex.Changes changes = live.synchronize(sources(dir));

        assertEquals(List.of(circle.toAbsolutePath().normalize()), changes.changed());
        assertEquals(2, changes.unchanged());
        assertFalse(live.index().namesContaining("roll").isEmpty());
        assertEquals(fresh(dir), live.index(), "an updated index is the index a fresh build would give");
    }

    @Test
    void filesComeAndGo(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));

        Files.delete(dir.resolve("demo/Circle.java"));
        write(dir, "demo/Square.java", "package demo; class Square implements Shape { public double area() { return 2; } }");
        ProjectIndex.Changes changes = live.synchronize(sources(dir));

        assertEquals(1, changes.added().size(), changes.toString());
        assertEquals(1, changes.removed().size(), changes.toString());
        List<String> impls = live.index().implementationsOf("Shape").stream().map(Symbol::name).toList();
        assertEquals(List.of("Square"), impls);
        assertEquals(fresh(dir), live.index());
    }

    @Test
    void aListedFileThatIsGoneIsRemovedNotAnError(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        List<Path> listed = sources(dir);
        live.synchronize(listed);

        Files.delete(dir.resolve("demo/Scene.java"));
        ProjectIndex.Changes changes = live.synchronize(listed);
        assertEquals(1, changes.removed().size(), changes.toString());
        assertTrue(live.index().usagesOf("Circle").isEmpty());
    }

    @Test
    void aTouchedFileWhoseContentIsTheSameIsNotReparsed(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Index before = live.index();

        Files.setLastModifiedTime(dir.resolve("demo/Shape.java"), FileTime.from(Instant.now()));
        ProjectIndex.Changes changes = live.synchronize(sources(dir));

        assertTrue(changes.isEmpty(), "a checkout that rewrites a file identically costs a read, not a parse: "
                + changes);
        assertSame(before, live.index());
    }

    @Test
    void anEditInsideTheClocksResolutionIsStillSeen(@TempDir Path dir) throws IOException {
        // Written just now, so its modification time cannot be trusted yet.
        Path shape = dir.resolve("demo/Shape.java");
        Files.createDirectories(shape.getParent());
        Files.writeString(shape, "package demo; interface Shape { double area(); }");
        FileTime written = Files.getLastModifiedTime(shape);

        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(shape));

        // Same size, same modification time, different content: a stat alone would call it unchanged.
        Files.writeString(shape, "package demo; interface Shape { double aera(); }");
        Files.setLastModifiedTime(shape, written);
        ProjectIndex.Changes changes = live.synchronize(List.of(shape));

        assertEquals(1, changes.changed().size(), changes.toString());
        assertFalse(live.index().namesContaining("aera").isEmpty());
    }

    @Test
    void oneFileSpelledTwoWaysIsOneEntry(@TempDir Path dir) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        Path shape = dir.resolve("demo/Shape.java");
        live.synchronize(List.of(shape, dir.resolve("demo/../demo/Shape.java")));
        assertEquals(1, live.files().size(), live.files().toString());
    }

    // --- save and load ------------------------------------------------------------------------

    @Test
    void aSavedIndexLoadsAsTheSameIndex(@TempDir Path dir, @TempDir Path cache) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Path saved = cache.resolve("index.bin");
        live.save(saved);

        ProjectIndex loaded = ProjectIndex.load(saved, dir).orElseThrow();
        assertEquals(live.index(), loaded.index());
        assertEquals(live.files(), loaded.files());
    }

    @Test
    void aLoadedIndexReparsesOnlyWhatChangedWhileItWasClosed(@TempDir Path dir, @TempDir Path cache)
            throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Path saved = cache.resolve("index.bin");
        live.save(saved);

        write(dir, "demo/Scene.java", "package demo; class Scene { void go() { new Circle().area(); } void stop() {} }");
        ProjectIndex reopened = ProjectIndex.load(saved, dir).orElseThrow();
        ProjectIndex.Changes changes = reopened.synchronize(sources(dir));

        assertEquals(1, changes.changed().size(), changes.toString());
        assertEquals(2, changes.unchanged(), changes.toString());
        assertEquals(fresh(dir), reopened.index());
    }

    @Test
    void aMovedCheckoutKeepsItsIndex(@TempDir Path base) throws IOException {
        Path before = shapes(base.resolve("before"));
        ProjectIndex live = ProjectIndex.empty(before);
        live.synchronize(sources(before));
        Path saved = base.resolve("index.bin");
        live.save(saved);

        Path after = base.resolve("after");
        Files.move(before, after);
        ProjectIndex moved = ProjectIndex.load(saved, after).orElseThrow();

        assertTrue(moved.files().stream().allMatch(f -> f.startsWith(after.toAbsolutePath().normalize())),
                moved.files().toString());
        assertTrue(moved.index().symbols().stream().allMatch(s -> ((Source.OnDisk) s.source()).file().startsWith(after.toAbsolutePath())));
        assertTrue(moved.synchronize(sources(after)).isEmpty(),
                "the files are the same files; only the directory around them moved");
    }

    @Test
    void saveReplacesWhatWasThere(@TempDir Path dir, @TempDir Path cache) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Path saved = cache.resolve("index.bin");
        live.save(saved);

        Files.delete(dir.resolve("demo/Circle.java"));
        live.synchronize(sources(dir));
        live.save(saved);

        assertEquals(2, ProjectIndex.load(saved, dir).orElseThrow().files().size());
        try (Stream<Path> left = Files.list(cache)) {
            assertEquals(List.of(saved), left.toList(), "no partial files left behind");
        }
    }

    // --- invalidation -------------------------------------------------------------------------

    @Test
    void nothingSavedIsNothingLoaded(@TempDir Path dir) throws IOException {
        assertTrue(ProjectIndex.load(dir.resolve("absent.bin"), dir).isEmpty());
    }

    @Test
    void anIndexFromAnotherConcordanceIsRefused(@TempDir Path dir) throws IOException {
        Path saved = dir.resolve("index.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(saved))) {
            out.writeInt(0x434E4344);
            byte[] other = IndexCodec.fingerprint().replace("builder=", "builder=99").getBytes(StandardCharsets.UTF_8);
            out.writeInt(other.length);
            out.write(other);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0x2F434E43);
        }
        assertTrue(ProjectIndex.load(saved, dir).isEmpty(),
                "an index built by different extraction rules would answer differently; rebuild instead");
    }

    @Test
    void aTruncatedIndexIsRefused(@TempDir Path dir, @TempDir Path cache) throws IOException {
        ProjectIndex live = ProjectIndex.empty(shapes(dir));
        live.synchronize(sources(dir));
        Path saved = cache.resolve("index.bin");
        live.save(saved);

        byte[] whole = Files.readAllBytes(saved);
        for (int cut : new int[] {0, 3, 10, whole.length / 2, whole.length - 1}) {
            Files.write(saved, Arrays.copyOf(whole, cut));
            assertTrue(ProjectIndex.load(saved, dir).isEmpty(), "cut at " + cut + " of " + whole.length);
        }
    }

    @Test
    void somethingElseEntirelyIsRefused(@TempDir Path dir) throws IOException {
        Path saved = dir.resolve("index.bin");
        Files.writeString(saved, "<project/>");
        assertTrue(ProjectIndex.load(saved, dir).isEmpty());
    }

    @Test
    void theFingerprintCarriesEveryKind() {
        String fingerprint = IndexCodec.fingerprint();
        for (Symbol.Kind kind : Symbol.Kind.values()) assertTrue(fingerprint.contains(kind.name()), fingerprint);
        for (Reference.Kind kind : Reference.Kind.values()) assertTrue(fingerprint.contains(kind.name()), fingerprint);
    }

    /**
     * The saved format writes these records field by field. If one of them gains, loses, or retypes a component,
     * old indexes no longer describe what this build would produce — so this fails until {@link IndexCodec#FORMAT}
     * is bumped and the expected shapes below are updated with it.
     */
    @Test
    void theRecordsTheFormatWritesHaveNotChangedShapeUnnoticed() {
        String shapes = Stream.of(Symbol.class, Reference.class, SourceRef.class)
                .map(ProjectIndexTest::shape)
                .collect(Collectors.joining("; "));
        assertEquals(3, IndexCodec.FORMAT, "FORMAT moved; update the shapes below to match it");
        assertEquals("Symbol(Kind kind, String name, String owner, String qualified, SourceRef at, List supers); "
                        + "Reference(Kind kind, String target, String name, String from, SourceRef at); "
                        + "SourceRef(Source source, int line, int column)",
                shapes,
                "a record the index format writes has changed shape: bump IndexCodec.FORMAT so saved indexes are "
                        + "refused, write the new component in IndexCodec, and update this expectation");
    }

    private static String shape(Class<? extends Record> type) {
        List<String> components = new ArrayList<>();
        for (RecordComponent c : type.getRecordComponents()) {
            components.add(c.getType().getSimpleName() + " " + c.getName());
        }
        return type.getSimpleName() + "(" + String.join(", ", components) + ")";
    }
}
