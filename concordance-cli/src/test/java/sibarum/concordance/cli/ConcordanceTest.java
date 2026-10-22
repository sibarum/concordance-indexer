package sibarum.concordance.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConcordanceTest {

    /** A tiny project on disk, because the CLI's job is to start from a directory. */
    private static Path project(Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version></project>
                """);
        Path src = dir.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Shape.java"), """
                package demo;
                interface Shape { double area(); }
                class Circle implements Shape { public double area() { return 1; } }
                class Square implements Shape { public double area() { return 2; } }
                class Scene { void go() { new Circle().area(); } }
                """);
        return dir;
    }

    private record Run(int code, String out, String err) {}

    private static Run run(Path project, String... query) {
        String[] args = new String[query.length + 1];
        args[0] = project.toString();
        System.arraycopy(query, 0, args, 1, query.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Concordance.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void summaryReportsWhatWasIndexed(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "summary");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("types"), result.out());
        assertTrue(result.out().contains("1 modules"), result.out());
    }

    @Test
    void namesFindsDeclarationsBySubstring(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "names", "are");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("area"), result.out());
        assertTrue(result.out().contains("Square"), result.out());
        assertTrue(result.out().contains("Shape.java:"), "every line points somewhere: " + result.out());
    }

    @Test
    void implsFindsImplementors(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "impls", "Shape");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("Circle"), result.out());
        assertTrue(result.out().contains("Square"), result.out());
        assertFalse(result.out().contains("Scene"), "Scene only uses Shape: " + result.out());
    }

    @Test
    void implsCanNarrowToAMethod(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "impls", "Shape", "area");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("METHOD"), result.out());
        assertTrue(result.out().contains("demo.Circle"), result.out());
    }

    @Test
    void usagesFindsWhereANameIsUsed(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "usages", "area");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("CALL"), result.out());
        assertTrue(result.out().contains("Scene#go"), "attributed to the method it sits in: " + result.out());
    }

    @Test
    void anUnknownQueryFailsWithUsageRatherThanSilence(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "wat");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("no such query"), result.err());
        assertTrue(result.err().contains("usages"), "and says what there is: " + result.err());
    }

    @Test
    void aMissingArgumentSaysWhichOne(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "names");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("<fragment>"), result.err());
    }

    @Test
    void aSourcesJarAnswersAlongsideTheProject(@TempDir Path dir, @TempDir Path repo) throws IOException {
        Path jar = repo.resolve("shapes-sources.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("lib/Polygon.java"));
            zip.write("package lib; public class Polygon { public double area() { return 0; } }"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Run result = run(project(dir), "names", "area", "--sources", jar.toString(), "--no-jar-cache");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains(jar.toAbsolutePath() + "!/lib/Polygon.java:1"), result.out());
        assertTrue(result.out().contains("Shape.java"), "the project still answers: " + result.out());
    }

    @Test
    void searchPrintsStageByStageOpenFilesFirst(@TempDir Path dir, @TempDir Path repo) throws IOException {
        Path jar = repo.resolve("shapes-sources.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("lib/Polygon.java"));
            zip.write("package lib; public class Polygon { public double area() { return 0; } }"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path shape = project(dir).resolve("src/main/java/demo/Shape.java");
        Run result = run(dir, "search", "area", "--sources", jar.toString(), "--no-jar-cache", "--recent", shape.toString());
        assertEquals(0, result.code(), result.err());
        int open = result.out().indexOf("== open (1 sources)");
        int libraries = result.out().indexOf("== other libraries (1 sources)");
        assertTrue(open >= 0 && libraries > open, result.out());
        assertTrue(result.out().indexOf("Polygon.java") > libraries, result.out());
        assertFalse(result.out().contains("== project"), "an empty stage is left out: " + result.out());
        assertTrue(result.out().contains("area  (3: 3 METHOD)"), "one name, its declarations under it: " + result.out());
        assertTrue(result.out().indexOf("area  (+1 of 4: 1 METHOD)") > libraries,
                "the group grows in a later stage, which shows only what it added: " + result.out());
        assertTrue(result.out().contains("-- 1 names (4 declarations) answering area, in 2 stages"), result.out());
    }

    // --- the jar cache -------------------------------------------------------------------------

    /** The CLI with these exact arguments, no project in front: what {@code concordance cache ...} is. */
    private static Run bare(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Concordance.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Path polygonJar(Path repo) throws IOException {
        Path jar = repo.resolve("shapes-sources.jar");
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("lib/Polygon.java"));
            zip.write("package lib; public class Polygon { public double area() { return 0; } }"
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    void cacheSaysWhereItIsAndWhatItHolds(@TempDir Path cache) {
        Run result = bare("cache", "--jar-cache", cache.toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains(cache.toAbsolutePath().normalize() + ": 0 jars, 0 bytes"), result.out());
    }

    @Test
    void cachePruneTrimsWhatIndexingStored(@TempDir Path dir, @TempDir Path repo, @TempDir Path cache)
            throws IOException {
        Path jar = polygonJar(repo);
        Run indexed = run(project(dir), "summary", "--sources", jar.toString(), "--jar-cache", cache.toString());
        assertEquals(0, indexed.code(), indexed.err());
        assertTrue(bare("cache", "--jar-cache", cache.toString()).out().contains(": 1 jars, "));
        assertTrue(run(dir, "summary", "--sources", jar.toString(), "--jar-cache", cache.toString())
                .out().contains("(1 from the jar cache)"), "the second index loads it");

        Run pruned = bare("cache", "prune", "0", "--jar-cache", cache.toString());
        assertEquals(0, pruned.code(), pruned.err());
        assertTrue(pruned.out().startsWith("pruned 1 of 1 jars, "), pruned.out());
        assertTrue(bare("cache", "--jar-cache", cache.toString()).out().contains(": 0 jars, 0 bytes"));
    }

    @Test
    void cacheListShowsEachIndexAndPurgeEmptiesIt(@TempDir Path dir, @TempDir Path repo, @TempDir Path cache)
            throws IOException {
        Path jar = polygonJar(repo);
        run(project(dir), "summary", "--sources", jar.toString(), "--jar-cache", cache.toString());

        Run listed = bare("cache", "list", "--jar-cache", cache.toString());
        assertEquals(0, listed.code(), listed.err());
        assertTrue(listed.out().contains(" sources  1 project "), listed.out());
        assertTrue(listed.out().contains("-- 1 jars, "), listed.out());

        Run purged = bare("cache", "purge", "--jar-cache", cache.toString());
        assertEquals(0, purged.code(), purged.err());
        assertTrue(purged.out().startsWith("purged 1 of 1 jars, "), purged.out());
        assertTrue(bare("cache", "--jar-cache", cache.toString()).out().trim()
                .endsWith(": 0 jars, 0 bytes"), "stamps and project records went too");
    }

    @Test
    void cachePruneToARoomySizeKeepsEverything(@TempDir Path dir, @TempDir Path repo, @TempDir Path cache)
            throws IOException {
        run(project(dir), "summary", "--sources", polygonJar(repo).toString(), "--jar-cache", cache.toString());
        Run pruned = bare("cache", "prune", "1G", "--jar-cache", cache.toString());
        assertTrue(pruned.out().startsWith("pruned 0 of 1 jars, "), pruned.out());
    }

    @Test
    void cachePruneNeedsASizeItCanRead(@TempDir Path cache) {
        Run none = bare("cache", "prune", "--jar-cache", cache.toString());
        assertEquals(2, none.code());
        assertTrue(none.err().contains("<size>"), none.err());
        Run nonsense = bare("cache", "prune", "lots", "--jar-cache", cache.toString());
        assertEquals(2, nonsense.code());
        assertTrue(nonsense.err().contains("not a size: lots"), nonsense.err());
    }

    @Test
    void sizesReadAsPeopleWriteThem() {
        assertEquals(500L << 20, Concordance.parseSize("500M"));
        assertEquals(2L << 30, Concordance.parseSize("2g"));
        assertEquals(64L << 10, Concordance.parseSize("64KB"));
        assertEquals(0, Concordance.parseSize("0"));
        assertEquals(1234, Concordance.parseSize("1234"));
        assertThrows(IllegalArgumentException.class, () -> Concordance.parseSize("-1M"));
        assertThrows(IllegalArgumentException.class, () -> Concordance.parseSize("9999999999G"));
    }

    /** {@code lib:<artifact>:1} in a local repository: a pom with these dependencies, and a sources jar. */
    private static void artifact(Path repo, String artifact, String type, String dependencies) throws IOException {
        Path at = repo.resolve("lib/" + artifact + "/1");
        Files.createDirectories(at);
        Files.writeString(at.resolve(artifact + "-1.pom"), """
                <project><groupId>lib</groupId><artifactId>%s</artifactId><version>1</version>
                <dependencies>%s</dependencies></project>
                """.formatted(artifact, dependencies));
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(at.resolve(artifact + "-1-sources.jar")))) {
            zip.putNextEntry(new java.util.zip.ZipEntry(type.replace('.', '/') + ".java"));
            int dot = type.lastIndexOf('.');
            zip.write(("package " + type.substring(0, dot) + "; public class " + type.substring(dot + 1) + " {}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.writeString(at.resolve(artifact + "-1.jar"), "jar");
    }

    @Test
    void effectiveSetsWhatTheCodeLinksToAgainstWhatThePomDeclares(@TempDir Path dir, @TempDir Path repo,
                                                                  @TempDir Path cache) throws IOException {
        String dependency = "<dependency><groupId>lib</groupId><artifactId>%s</artifactId><version>1</version></dependency>";
        artifact(repo, "shapes", "lib.shapes.Polygon", dependency.formatted("geometry"));
        artifact(repo, "geometry", "lib.geometry.Point", "");
        artifact(repo, "spare", "lib.spare.Spare", "");
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>demo</groupId><artifactId>demo</artifactId><version>1</version>
                <dependencies>%s%s</dependencies></project>
                """.formatted(dependency.formatted("shapes"), dependency.formatted("spare")));
        Path src = dir.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Scene.java"), """
                package demo;
                import lib.shapes.Polygon;
                import lib.geometry.Point;
                class Scene { Polygon p; Point origin; }
                """);

        String before = System.getProperty("maven.repo.local");
        System.setProperty("maven.repo.local", repo.toString());
        try {
            Run result = run(dir, "effective", "--jar-cache", cache.toString());
            assertEquals(0, result.code(), result.err());
            assertTrue(result.out().contains("== demo (1 files)"), result.out());
            assertTrue(result.out().matches("(?s).*used +lib:shapes:1 +1 file\\R.*"), result.out());
            assertTrue(result.out().matches("(?s).*undeclared +lib:geometry:1 +1 file, through lib:shapes:1\\R.*"),
                    result.out());
            assertTrue(result.out().matches("(?s).*unused +lib:spare:1 +nothing links to it\\R.*"), result.out());
            assertTrue(result.out().contains("-- 1 used, 1 used but undeclared, 1 unused, 0 unknown, 0 only for"),
                    result.out());
        } finally {
            if (before == null) System.clearProperty("maven.repo.local");
            else System.setProperty("maven.repo.local", before);
        }
    }

    @Test
    void depsListsTheLibrariesAndSaysWhereFrom(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "deps");
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains("-- 0 libraries, 0 with sources, 0 problems, from "), result.out());
    }

    @Test
    void aSourcesFlagWithoutAJarSaysSo(@TempDir Path dir) throws IOException {
        Run result = run(project(dir), "summary", "--sources");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("<jar>"), result.err());
    }

    @Test
    void aDirectoryWithNoPomFailsCleanly(@TempDir Path dir) {
        Run result = run(dir, "summary");
        assertEquals(1, result.code());
        assertTrue(result.err().contains("cannot index"), result.err());
    }
}
