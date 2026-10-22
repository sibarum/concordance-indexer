package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.concordance.index.EffectiveDependencies.Status;
import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Coordinates;
import sibarum.concordance.project.Dependency;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The libraries a project's code links to, set against the ones its poms declare. */
class EffectiveDependenciesTest {

    @TempDir Path dir;

    private static final Coordinates FIGURES = new Coordinates("lib", "figures", "1");
    private static final Coordinates BOM = new Coordinates("lib", "bundle", "1");

    private static Path java(Path file, String source) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toAbsolutePath().normalize();
    }

    private static Path jar(Path file, String entry, String source) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream raw = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file.toAbsolutePath().normalize();
    }

    private static Dependency declared(String artifact, Path sources) {
        return new Dependency(new Coordinates("lib", artifact, "1"), "jar", "", "compile", 1, null, sources);
    }

    private static Dependency through(String artifact, Path sources, Coordinates... via) {
        return new Dependency(new Coordinates("lib", artifact, "1"), "jar", "", "compile", via.length + 1, null,
                sources, List.of(via));
    }

    private static Map<String, Status> standings(EffectiveDependencies effective) {
        Map<String, Status> out = new LinkedHashMap<>();
        effective.uses().forEach(u -> out.put(u.dependency().coordinates().artifactId(), u.status()));
        return out;
    }

    /**
     * Circle extends a Figure from {@code figures}, declared; Easel imports a Drawable from {@code drawing}, which
     * only {@code figures} brings. {@code spare} is declared and nobody names it, {@code absent} is declared and
     * not in the repository, and {@code quiet} comes with {@code figures} and nobody names it either.
     */
    @Test
    void eachLibraryStandsByWhetherItIsLinkedToAndWhetherItIsDeclared() throws IOException {
        Path circle = java(dir.resolve("src/app/Circle.java"),
                "package app; class Circle extends lib.figures.Figure { public void draw() {} }");
        Path easel = java(dir.resolve("src/app/Easel.java"),
                "package app; import lib.drawing.Drawable; class Easel implements Drawable { public void draw() {} }");
        Path figures = jar(dir.resolve("repo/figures-sources.jar"), "lib/figures/Figure.java",
                "package lib.figures; public abstract class Figure implements lib.drawing.Drawable {}");
        Path drawing = jar(dir.resolve("repo/drawing-sources.jar"), "lib/drawing/Drawable.java",
                "package lib.drawing; public interface Drawable { void draw(); }");
        Path spare = jar(dir.resolve("repo/spare-sources.jar"), "lib/spare/Spare.java",
                "package lib.spare; public class Spare {}");
        Path quiet = jar(dir.resolve("repo/quiet-sources.jar"), "lib/quiet/Quiet.java",
                "package lib.quiet; public class Quiet {}");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(circle, easel), List.of(figures, drawing, spare, quiet));

        Classpath classpath = new Classpath(List.of(declared("figures", figures), declared("spare", spare),
                declared("absent", null), through("drawing", drawing, FIGURES), through("quiet", quiet, FIGURES)),
                List.of());
        EffectiveDependencies effective = EffectiveDependencies.of(live.corpus(), classpath, List.of(circle, easel));

        assertEquals(Map.of("figures", Status.USED, "spare", Status.UNUSED, "absent", Status.UNKNOWN,
                "drawing", Status.UNDECLARED, "quiet", Status.TRANSITIVE), standings(effective));
        assertEquals(List.of(circle), effective.with(Status.USED).getFirst().from());
        assertEquals(List.of(easel), effective.with(Status.UNDECLARED).getFirst().from());
        assertEquals(List.of(FIGURES), effective.with(Status.UNDECLARED).getFirst().dependency().via());
    }

    @Test
    void onlyTheFilesGivenCountSoEachModuleAnswersForItself() throws IOException {
        Path mine = java(dir.resolve("a/src/A.java"), "package a; class A {}");
        Path theirs = java(dir.resolve("b/src/B.java"), "package b; import lib.spare.Spare; class B { Spare s; }");
        Path spare = jar(dir.resolve("repo/spare-sources.jar"), "lib/spare/Spare.java",
                "package lib.spare; public class Spare {}");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(mine, theirs), List.of(spare));
        Classpath classpath = new Classpath(List.of(declared("spare", spare)), List.of());

        assertEquals(Status.UNUSED, standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(mine)))
                .get("spare"));
        assertEquals(Status.USED, standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(theirs)))
                .get("spare"));
    }

    @Test
    void aSimpleNameAnotherLibraryDeclaresIsNotALinkWithoutAnImport() throws IOException {
        Path canvas = java(dir.resolve("src/app/Canvas.java"), "package app; class Canvas { Spare spare; }");
        Path spare = jar(dir.resolve("repo/spare-sources.jar"), "lib/spare/Spare.java",
                "package lib.spare; public class Spare {}");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(canvas), List.of(spare));
        Classpath classpath = new Classpath(List.of(declared("spare", spare)), List.of());

        assertEquals(Status.UNUSED, standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(canvas)))
                .get("spare"));
    }

    @Test
    void aDeclaredPomIsUsedWhenSomethingItBringsIsLinkedTo() throws IOException {
        Path app = java(dir.resolve("src/app/App.java"), "package app; import lib.drawing.Drawable; class App {}");
        Path drawing = jar(dir.resolve("repo/drawing-sources.jar"), "lib/drawing/Drawable.java",
                "package lib.drawing; public interface Drawable {}");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(app), List.of(drawing));
        Dependency bundle = new Dependency(BOM, "pom", "", "compile", 1, null, null);
        Dependency idle = new Dependency(new Coordinates("lib", "idle", "1"), "pom", "", "compile", 1, null, null);
        Classpath classpath = new Classpath(List.of(bundle, idle, through("drawing", drawing, BOM)), List.of());

        Map<String, Status> standings = standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(app)));
        assertEquals(Status.USED, standings.get("bundle"));
        assertEquals(Status.UNUSED, standings.get("idle"));
        assertEquals(Status.UNDECLARED, standings.get("drawing"));
    }

    @Test
    void aLibraryOnlyChangesStandingWhenTheIndexDoes() throws IOException {
        Path app = java(dir.resolve("src/app/App.java"), "package app; class App {}");
        Path spare = jar(dir.resolve("repo/spare-sources.jar"), "lib/spare/Spare.java",
                "package lib.spare; public class Spare {}");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(app), List.of(spare));
        Classpath classpath = new Classpath(List.of(declared("spare", spare)), List.of());
        assertEquals(Status.UNUSED, standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(app)))
                .get("spare"));

        java(dir.resolve("src/app/App.java"), "package app; import lib.spare.Spare; class App { Spare s; }");
        Files.setLastModifiedTime(app, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        live.synchronize(List.of(app), List.of(spare));
        assertEquals(Status.USED, standings(EffectiveDependencies.of(live.corpus(), classpath, List.of(app)))
                .get("spare"));
    }
}
