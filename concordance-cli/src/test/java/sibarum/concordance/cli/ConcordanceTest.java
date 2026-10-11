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
                <project><artifactId>demo</artifactId></project>
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
        Run result = run(project(dir), "names", "area", "--sources", jar.toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(result.out().contains(jar.toAbsolutePath() + "!/lib/Polygon.java:1"), result.out());
        assertTrue(result.out().contains("Shape.java"), "the project still answers: " + result.out());
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
