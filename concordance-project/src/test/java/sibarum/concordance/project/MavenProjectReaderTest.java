package sibarum.concordance.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MavenProjectReaderTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static String aggregator(String artifactId, String... modules) {
        StringBuilder sb = new StringBuilder("""
                <project>
                  <artifactId>%s</artifactId>
                  <packaging>pom</packaging>
                  <modules>
                """.formatted(artifactId));
        for (String m : modules) sb.append("    <module>").append(m).append("</module>\n");
        return sb.append("  </modules>\n</project>\n").toString();
    }

    private static String leaf(String artifactId) {
        return """
                <project>
                  <artifactId>%s</artifactId>
                </project>
                """.formatted(artifactId);
    }

    /** A two-level reactor with code in the leaves, which is the shape of every project we care about. */
    private static Path reactor(Path dir) throws IOException {
        write(dir.resolve("pom.xml"), aggregator("demo", "demo-core", "demo-app"));
        write(dir.resolve("demo-core/pom.xml"), leaf("demo-core"));
        write(dir.resolve("demo-app/pom.xml"), leaf("demo-app"));
        write(dir.resolve("demo-core/src/main/java/a/Alpha.java"), "package a; class Alpha {}\n");
        write(dir.resolve("demo-core/src/main/java/a/Beta.java"), "package a; class Beta {}\n");
        write(dir.resolve("demo-app/src/main/java/b/Main.java"), "package b; class Main {}\n");
        // test sources are not indexed as main source, and must not leak in
        write(dir.resolve("demo-core/src/test/java/a/AlphaTest.java"), "package a; class AlphaTest {}\n");
        return dir;
    }

    @Test
    void readsTheReactorDepthFirstInDeclarationOrder(@TempDir Path dir) throws IOException {
        MavenProject project = MavenProjectReader.read(reactor(dir));

        assertEquals(List.of("demo", "demo-core", "demo-app"),
                project.modules().stream().map(MavenModule::artifactId).toList(),
                "declaration order, not alphabetical: a graph that reorders fails a skeleton check for nothing");
        assertTrue(project.root().isAggregator(), "the root declares packaging pom");
        assertTrue(project.root().sourceRoots().isEmpty(), "an aggregator with no src/main/java has no roots");
        assertEquals(2, project.codeModules().size());
    }

    @Test
    void findsMainJavaAndLeavesTestJavaAlone(@TempDir Path dir) throws IOException {
        MavenProject project = MavenProjectReader.read(reactor(dir));

        List<String> names = project.javaFiles().stream()
                .map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("Alpha.java", "Beta.java", "Main.java"), names,
                "reactor order, then file order within a module");
        assertFalse(names.contains("AlphaTest.java"), "src/test/java is not a main source root");
    }

    @Test
    void attributesAFileBackToItsModule(@TempDir Path dir) throws IOException {
        MavenProject project = MavenProjectReader.read(reactor(dir));
        Path main = dir.resolve("demo-app/src/main/java/b/Main.java");

        assertEquals("demo-app", project.moduleOf(main).orElseThrow().artifactId());
        assertTrue(project.moduleOf(dir.resolve("elsewhere/X.java")).isEmpty(),
                "a file outside every source root belongs to no module");
    }

    @Test
    void aModuleMayInheritItsArtifactIdAndOverrideItsSourceDirectory(@TempDir Path dir) throws IOException {
        write(dir.resolve("pom.xml"), """
                <project>
                  <artifactId>weird</artifactId>
                  <packaging>pom</packaging>
                  <properties><src.dir>sources/java</src.dir></properties>
                  <modules><module>child</module></modules>
                </project>
                """);
        // no artifactId of its own, and a non-default source directory built from an inherited property
        write(dir.resolve("child/pom.xml"), """
                <project>
                  <parent><artifactId>weird</artifactId></parent>
                  <build><sourceDirectory>${src.dir}</sourceDirectory></build>
                </project>
                """);
        write(dir.resolve("child/sources/java/c/C.java"), "package c; class C {}\n");

        MavenProject project = MavenProjectReader.read(dir);
        MavenModule child = project.modules().get(1);

        assertEquals("weird", child.artifactId(), "artifactId falls back to the parent's");
        assertEquals(List.of(dir.resolve("child/sources/java").normalize()), child.sourceRoots(),
                "the property was inherited down the reactor and interpolated");
        assertEquals(List.of("C.java"),
                project.javaFiles().stream().map(p -> p.getFileName().toString()).toList());
    }

    @Test
    void aNamedModuleThatIsNotThereFailsLoudly(@TempDir Path dir) throws IOException {
        write(dir.resolve("pom.xml"), aggregator("demo", "missing"));

        MavenProjectReader.BrokenReactorException e = assertThrows(
                MavenProjectReader.BrokenReactorException.class,
                () -> MavenProjectReader.read(dir));
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
    }

    @Test
    void aPomReachableTwiceIsReadOnce(@TempDir Path dir) throws IOException {
        // two aggregators both naming the same child; the child must not appear twice
        write(dir.resolve("pom.xml"), aggregator("root", "a", "b"));
        write(dir.resolve("a/pom.xml"), aggregator("a", "../shared"));
        write(dir.resolve("b/pom.xml"), aggregator("b", "../shared"));
        write(dir.resolve("shared/pom.xml"), leaf("shared"));
        write(dir.resolve("shared/src/main/java/s/S.java"), "package s; class S {}\n");

        MavenProject project = MavenProjectReader.read(dir);
        assertEquals(1, project.modules().stream().filter(m -> m.artifactId().equals("shared")).count(),
                "a module reachable by two paths is one module");
        assertEquals(1, project.javaFiles().size(), "and its files are not indexed twice");
    }

    @Test
    void anUnknownPropertyIsLeftVisibleRatherThanBlanked() {
        assertEquals("a/${nope}/b", MavenProjectReader.interpolate("a/${nope}/b", Map.of()));
        assertEquals("a/x/b", MavenProjectReader.interpolate("a/${p}/b", Map.of("p", "x")));
        assertEquals("x-y", MavenProjectReader.interpolate("${a}-${b}", Map.of("a", "x", "b", "y")));
        assertEquals("no placeholders", MavenProjectReader.interpolate("no placeholders", Map.of()));
        assertEquals("${unclosed", MavenProjectReader.interpolate("${unclosed", Map.of()));
    }

    @Test
    void readsARealReactorOffDisk() throws IOException {
        // This project, read by itself. Two modules today, both with code.
        Path here = Path.of("").toAbsolutePath();
        Path root = here.getFileName().toString().startsWith("concordance-") ? here.getParent() : here;
        MavenProject project = MavenProjectReader.read(root);

        assertEquals("concordance-indexer", project.root().artifactId());
        assertTrue(project.root().isAggregator());
        assertTrue(project.modules().size() >= 2, "the reactor has modules: " + project.modules());
        assertFalse(project.javaFiles().isEmpty(), "and they contain Java");
        assertTrue(project.javaFiles().stream().allMatch(p -> p.toString().endsWith(".java")));
    }
}
