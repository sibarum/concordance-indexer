package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
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

/**
 * Inputs built to hurt: each must cost only itself. The rest of the index is built as usual, the synchronize
 * returns, and the input's problem says what went wrong.
 */
class HostileInputTest {

    @TempDir Path dir;

    private static final String FINE = "package p; public class Fine { public void ok() {} }";

    private static String nested(int depth) {
        return "package p; public class Deep { public int f() { return " + "(".repeat(depth) + "1"
                + ")".repeat(depth) + "; } }";
    }

    private Path jar(String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return file.toAbsolutePath().normalize();
    }

    private static Map<String, byte[]> entries(String... nameThenSource) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < nameThenSource.length; i += 2) {
            out.put(nameThenSource[i], nameThenSource[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static List<String> names(ProjectIndex live) {
        return live.index().symbols().stream().map(Symbol::qualified).toList();
    }

    private static Limits small(long maxSource, long maxArchive, int maxEntries) {
        return new Limits(maxSource, maxArchive, maxEntries, Limits.DEFAULT.parseStackBytes());
    }

    // --- parsing -------------------------------------------------------------------------------

    @Test
    void nestingThatOverflowsAnOrdinaryStackParsesOnTheParseThread() throws IOException {
        // About 400 levels overflow a default thread stack inside JavaParser's own parser.
        Path jar = jar("deep.jar", entries("p/Deep.java", nested(3_000), "p/Fine.java", FINE));
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(), List.of(jar));
        assertTrue(live.problems().isEmpty(), live.problems().toString());
        assertTrue(names(live).contains("p.Deep#f()"), names(live).toString());
    }

    @Test
    void nestingPastEvenTheParseStackCostsOnlyThatFile() throws IOException {
        Path jar = jar("deep.jar", entries("p/Deep.java", nested(20_000), "p/Fine.java", FINE));
        Limits tight = new Limits(Limits.DEFAULT.maxSourceBytes(), Limits.DEFAULT.maxArchiveSourceBytes(),
                Limits.DEFAULT.maxArchiveEntries(), 1L << 20);
        ProjectIndex live = ProjectIndex.empty(dir, tight);

        live.synchronize(List.of(), List.of(jar));
        assertEquals(List.of("p.Fine", "p.Fine#ok()"), names(live), "the next file is parsed as if nothing happened");
        assertEquals(1, live.problems().size(), live.problems().toString());
        assertTrue(live.problems().getFirst().contains("p/Deep.java") && live.problems().getFirst().contains("nested"),
                live.problems().getFirst());
    }

    @Test
    void aProjectFileTooDeepToParseIsStampedAndNotRetriedUntilItChanges() throws IOException {
        Path deep = dir.resolve("Deep.java");
        Files.writeString(deep, nested(20_000));
        Files.setLastModifiedTime(deep, FileTime.from(Instant.now().minusSeconds(60)));
        Limits tight = new Limits(Limits.DEFAULT.maxSourceBytes(), Limits.DEFAULT.maxArchiveSourceBytes(),
                Limits.DEFAULT.maxArchiveEntries(), 1L << 20);
        ProjectIndex live = ProjectIndex.empty(dir, tight);

        assertEquals(1, live.synchronize(List.of(deep)).added().size());
        assertEquals(1, live.problems().size());
        ProjectIndex.Changes again = live.synchronize(List.of(deep));
        assertTrue(again.isEmpty() && again.unchanged() == 1, again.toString());
        assertEquals(1, live.problems().size(), "the problem stands while the file does");
    }

    // --- size ----------------------------------------------------------------------------------

    @Test
    void aZipBombEntryIsCutOffAtTheLimitNotExpanded() throws IOException {
        Map<String, byte[]> entries = entries("p/Fine.java", FINE);
        entries.put("p/Bomb.java", new byte[8 << 20]);   // 8 MB of zeros, a few kilobytes compressed
        Path jar = jar("bomb.jar", entries);
        assertTrue(Files.size(jar) < 64 << 10, "the jar itself is small: " + Files.size(jar));

        ProjectIndex live = ProjectIndex.empty(dir, small(1 << 20, 1L << 30, 1000));
        live.synchronize(List.of(), List.of(jar));
        assertEquals(List.of("p.Fine", "p.Fine#ok()"), names(live));
        assertEquals(1, live.problems().size());
        assertTrue(live.problems().getFirst().contains("p/Bomb.java")
                && live.problems().getFirst().contains("limit"), live.problems().getFirst());
    }

    @Test
    void aJarOverItsTotalBudgetStopsWhereTheBudgetRunsOut() throws IOException {
        Path jar = jar("big.jar", entries("p/A.java", "package p; public class A {}" + " ".repeat(600),
                "p/B.java", "package p; public class B {}" + " ".repeat(600),
                "p/C.java", "package p; public class C {}"));
        ProjectIndex live = ProjectIndex.empty(dir, small(1 << 20, 1_000, 1000));
        live.synchronize(List.of(), List.of(jar));

        assertEquals(List.of("p.A"), names(live));
        assertEquals(1, live.problems().size(), live.problems().toString());
        assertTrue(live.problems().getFirst().contains("entries from p/B.java on are not indexed"),
                live.problems().getFirst());
    }

    @Test
    void aJarWithTooManyEntriesIndexesTheFirstByName() throws IOException {
        Path jar = jar("many.jar", entries("p/C.java", "package p; public class C {}",
                "p/A.java", "package p; public class A {}", "p/B.java", "package p; public class B {}"));
        ProjectIndex live = ProjectIndex.empty(dir, small(1 << 20, 1L << 30, 2));
        live.synchronize(List.of(), List.of(jar));

        assertEquals(List.of("p.A", "p.B"), names(live));
        assertTrue(live.problems().getFirst().contains("3 Java entries"), live.problems().toString());
    }

    @Test
    void aProjectFileOverTheLimitIsNotRead() throws IOException {
        Path big = dir.resolve("Big.java");
        Files.writeString(big, "class Big {}" + " ".repeat(5_000));
        Path fine = dir.resolve("Fine.java");
        Files.writeString(fine, FINE);
        ProjectIndex live = ProjectIndex.empty(dir, small(1_000, 1L << 30, 1000));
        live.synchronize(List.of(big, fine));

        assertEquals(List.of("p.Fine", "p.Fine#ok()"), names(live));
        assertTrue(live.problems().getFirst().contains("Big.java"), live.problems().toString());
    }

    @Test
    void readingBackAnEntryOverTheLimitRefusesRatherThanExpanding() throws IOException {
        Map<String, byte[]> entries = entries("p/Fine.java", FINE);
        entries.put("p/Bomb.java", new byte[(int) Limits.DEFAULT.maxSourceBytes() + 1]);
        Path jar = jar("bomb.jar", entries);
        assertThrows(IOException.class, () -> new Source.InArchive(jar, "p/Bomb.java").read());
        assertEquals(FINE, new Source.InArchive(jar, "p/Fine.java").text());
    }

    // --- not a jar at all ----------------------------------------------------------------------

    @Test
    void aJarThatIsNotAZipIsAProblemNotAFailure() throws IOException {
        Path garbage = dir.resolve("garbage.jar");
        Files.write(garbage, "PK\u0003\u0004 this is not a zip at all".getBytes(StandardCharsets.UTF_8));
        Path good = jar("good.jar", entries("p/Fine.java", FINE));
        ProjectIndex live = ProjectIndex.empty(dir);

        live.synchronize(List.of(), List.of(garbage, good));
        assertEquals(List.of("p.Fine", "p.Fine#ok()"), names(live));
        assertEquals(1, live.problems().size());
        assertTrue(live.problems().getFirst().contains("garbage.jar")
                && live.problems().getFirst().contains("not a readable zip"), live.problems().getFirst());
    }

    // --- the saved index -----------------------------------------------------------------------

    @Test
    void problemsAreKeptWithASavedIndex() throws IOException {
        Path garbage = dir.resolve("garbage.jar");
        Files.writeString(garbage, "not a zip");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(), List.of(garbage));
        Path saved = dir.resolve("index.bin");
        live.save(saved);
        assertEquals(live.problems(), ProjectIndex.load(saved, dir).orElseThrow().problems());
    }

    @Test
    void aSavedIndexClaimingAHugeCountIsRefusedWithoutAllocatingIt() throws IOException {
        Path saved = dir.resolve("index.bin");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(saved))) {
            out.writeInt(0x434E4344);
            byte[] fingerprint = IndexCodec.fingerprint().getBytes(StandardCharsets.UTF_8);
            out.writeInt(fingerprint.length);
            out.write(fingerprint);
            out.writeInt(Integer.MAX_VALUE);   // a string table of two billion entries, in a file of a few bytes
        }
        assertTrue(ProjectIndex.load(saved, dir).isEmpty());
    }
}
