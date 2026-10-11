package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A library with no sources: declarations from its class files, text from Vineflower, and the two agreeing. */
class CompiledJarTest {

    @TempDir Path dir;

    private static final Map<String, String> LIBRARY = new LinkedHashMap<>();
    static {
        LIBRARY.put("lib/api/Widget.java", """
                package lib.api;

                import java.util.List;

                public class Widget {
                    public static final int SIZE = 3;
                    protected int count;
                    int hidden;

                    public Widget() {}
                    Widget(int n) {}

                    public void show(String... names) {}
                    public <T> List<T> wrap(List<? extends T> items, int[] counts) { return null; }
                    protected void layout() {}
                    private void helper() { Runnable r = () -> {}; new Object() {}; class Local {} }

                    public class Part {
                        public Part(String label) {}
                        public void fit() {}
                    }
                    public static class Gear { public void turn() {} }
                    protected interface Hook { void fire(); }
                    private static class Hidden { public void no() {} }
                }

                class Helper { public void assist() {} }
                """);
        LIBRARY.put("lib/api/Shade.java", """
                package lib.api;
                public enum Shade { LIGHT, DARK; public boolean light() { return this == LIGHT; } }
                """);
        LIBRARY.put("lib/api/Point.java", """
                package lib.api;
                public record Point(int x, int y) { public double norm() { return Math.hypot(x, y); } }
                """);
        LIBRARY.put("lib/api/Shape.java", """
                package lib.api;
                public interface Shape { double area(); default String label() { return "shape"; } }
                """);
        LIBRARY.put("lib/api/Marker.java", """
                package lib.api;
                public @interface Marker { String value(); }
                """);
    }

    /** The library compiled to class files, in a jar of its own. */
    private Path classJar(Map<String, byte[]> extra) throws IOException {
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

        Path jar = dir.resolve("lib-1.0.jar");
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(raw);
             Stream<Path> classes = Files.walk(out)) {
            for (Path c : classes.filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(out.relativize(c).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(c));
                zip.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return jar.toAbsolutePath().normalize();
    }

    private Path sourcesJar() throws IOException {
        Path jar = dir.resolve("lib-1.0-sources.jar");
        try (OutputStream raw = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (Map.Entry<String, String> e : LIBRARY.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar.toAbsolutePath().normalize();
    }

    private ProjectIndex compiled(Path jar) throws IOException {
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(), List.of(), List.of(jar));
        return live;
    }

    private static Set<String> normalized(ProjectIndex live) {
        return live.index().symbols().stream().map(s -> ClassFiles.normalized(s.qualified()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Symbol named(ProjectIndex live, String qualified) {
        return live.index().symbols().stream().filter(s -> s.qualified().equals(qualified)).findFirst()
                .orElseThrow(() -> new AssertionError(qualified + " is not indexed"));
    }

    // --- what the class files say --------------------------------------------------------------

    @Test
    void classFilesExportWhatTheSourceExportsAndTheCompilersImplicitMembers() throws IOException {
        Set<String> fromClasses = normalized(compiled(classJar(Map.of())));
        ProjectIndex fromSources = ProjectIndex.empty(dir);
        fromSources.synchronize(List.of(), List.of(sourcesJar()));
        Set<String> fromSource = normalized(fromSources);

        Set<String> missing = new TreeSet<>(fromSource);
        missing.removeAll(fromClasses);
        assertEquals(Set.of(), missing, "everything the source exports, the class files export too");

        Set<String> extra = new TreeSet<>(fromClasses);
        extra.removeAll(fromSource);
        assertEquals(new TreeSet<>(Set.of(
                        "lib.api.Point#<init>(int,int)", "lib.api.Point#equals(Object)", "lib.api.Point#hashCode()",
                        "lib.api.Point#toString()", "lib.api.Shade#values()", "lib.api.Shade#valueOf(String)",
                        "lib.api.Marker#value()", "lib.api.Widget.Gear#<init>()")), extra,
                "and beyond that only what the compiler declares for you, which a caller can call all the same");
    }

    @Test
    void whatTheCompilerMadeUpIsLeftOut() throws IOException {
        ProjectIndex live = compiled(classJar(Map.of()));
        List<String> all = live.index().symbols().stream().map(Symbol::qualified).toList();
        assertTrue(all.contains("lib.api.Widget.Part#<init>(String)"),
                "an inner class's constructor without its hidden outer-instance parameter: " + all);
        assertTrue(all.contains("lib.api.Widget#show(String)"), "varargs as source writes it");
        assertTrue(all.stream().noneMatch(q -> q.contains("lambda$") || q.contains("$1") || q.contains("Local")
                || q.contains("Hidden") || q.contains("Helper") || q.contains("helper")), all.toString());
        assertTrue(all.stream().noneMatch(q -> q.equals("lib.api.Point#x()")), "a record accessor is its component");
        assertEquals(List.of("lib.api.Widget.Part", "lib.api.Widget.Gear", "lib.api.Widget.Hook").stream().sorted().toList(),
                live.index().symbols().stream().filter(s -> s.kind().isType() && s.owner().equals("lib.api.Widget"))
                        .map(Symbol::qualified).sorted().toList());
        assertEquals(Symbol.Kind.ENUM_CONSTANT, named(live, "lib.api.Shade#LIGHT").kind());
        assertEquals(Symbol.Kind.ANNOTATION, named(live, "lib.api.Marker").kind());
    }

    @Test
    void nestedClassesBelongToTheirTopLevelClassAndStartUnplaced() throws IOException {
        Path jar = classJar(Map.of());
        Symbol fit = named(compiled(jar), "lib.api.Widget.Part#fit()");
        assertEquals(new Source.Decompiled(jar, "lib/api/Widget.class"), fit.source());
        assertEquals(0, fit.line(), "not placed until the class is decompiled");
        assertTrue(fit.source().readOnly());
    }

    @Test
    void aModuleExportsOnlyThePackagesItNamesToEveryone() throws IOException {
        byte[] moduleInfo = ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of("lib"),
                m -> m.exports(PackageDesc.of("lib.other"), 0)));
        ProjectIndex live = compiled(classJar(Map.of("module-info.class", moduleInfo)));
        assertTrue(live.index().symbols().isEmpty(), "lib.api is not exported, so nothing in it is");
    }

    @Test
    void aClassFileTheReaderRefusesCostsOnlyItself() throws IOException {
        ProjectIndex live = compiled(classJar(Map.of("lib/api/Broken.class",
                new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65, 1, 2, 3})));
        assertFalse(live.index().symbols().isEmpty());
        assertEquals(1, live.problems().size(), live.problems().toString());
        assertTrue(live.problems().getFirst().contains("lib/api/Broken.class"), live.problems().getFirst());
    }

    // --- what Vineflower shows -----------------------------------------------------------------

    @Test
    void aClassIsShownDecompiledWithItsNestedClasses() throws IOException {
        Path jar = classJar(Map.of());
        String text = new Source.Decompiled(jar, "lib/api/Widget.class").text();
        assertTrue(text.contains("public class Widget"), text);
        assertTrue(text.contains("public class Part"), "nested classes are in their outer class's text");
        assertEquals(text, new Source.Decompiled(jar, "lib/api/Widget.class").text(), "and the same text every time");
    }

    @Test
    void locatingPlacesAMemberOnItsLineInTheDecompiledText() throws IOException {
        Path jar = classJar(Map.of());
        ProjectIndex live = compiled(jar);
        for (String qualified : List.of("lib.api.Widget#show(String)", "lib.api.Widget#wrap(List,int[])",
                "lib.api.Widget.Part#<init>(String)", "lib.api.Shade#LIGHT", "lib.api.Widget.Gear")) {
            Symbol symbol = named(live, qualified);
            SourceRef at = symbol.source().locate(symbol);
            assertTrue(at.line() > 1, qualified + " placed at " + at);
            String line = symbol.source().text().lines().toList().get(at.line() - 1);
            assertTrue(line.contains(symbol.name()), qualified + " on its line: " + line);
        }
    }

    @Test
    void aSourceOtherThanDecompiledLocatesAtItsOwnPosition() throws IOException {
        Symbol symbol = IndexBuilder.buildSource(Path.of("A.java"), "class A {}").symbols().getFirst();
        assertSame(symbol.at(), symbol.source().locate(symbol));
    }

    @Test
    void decompilingPastTheTimeBudgetIsAbandoned() throws IOException {
        Path jar = classJar(Map.of());
        Limits impatient = new Limits(Limits.DEFAULT.maxSourceBytes(), Limits.DEFAULT.maxArchiveSourceBytes(),
                Limits.DEFAULT.maxArchiveEntries(), Limits.DEFAULT.parseStackBytes(), 1);
        IOException slow = assertThrows(IOException.class, () -> Decompilation.of(jar, "lib/api/Widget.class", impatient));
        assertTrue(slow.getMessage().contains("abandoned"), slow.getMessage());
    }

    @Test
    void aClassThatIsNotThereSaysSo() throws IOException {
        Path jar = classJar(Map.of());
        IOException absent = assertThrows(IOException.class, () -> new Source.Decompiled(jar, "lib/api/Nope.class").read());
        assertTrue(absent.getMessage().contains("not there"), absent.getMessage());
    }

    // --- with everything else ------------------------------------------------------------------

    @Test
    void aCompiledJarIsSavedLoadedAndSearchedLikeAnyOther() throws Exception {
        Path jar = classJar(Map.of());
        Path app = dir.resolve("app/App.java");
        Files.createDirectories(app.getParent());
        Files.writeString(app, "package app; import lib.api.Widget; class App { Widget w; }");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(app), List.of(), List.of(jar));

        Path saved = dir.resolve("index.bin");
        live.save(saved);
        ProjectIndex loaded = ProjectIndex.load(saved, dir).orElseThrow();
        assertEquals(live.index(), loaded.index());

        List<Plan.Stage> stages = Plan.everything().stages(loaded.corpus());
        assertEquals(List.of(Plan.PROJECT, Plan.LINKED_LIBRARIES, Plan.OTHER_LIBRARIES),
                stages.stream().map(Plan.Stage::name).toList());
        assertEquals(List.of(new Source.Decompiled(jar, "lib/api/Widget.class")), stages.get(1).sources(),
                "the import links the decompiled class like any library source");

        List<Hit<Symbol>> members = Search.start(loaded.corpus(), Plan.everything(), Query.membersOf("lib.api.Widget"),
                new Search.Listener<>() {}, Runnable::run, Duration.ZERO).done().get();
        assertTrue(members.stream().map(h -> h.item().name()).toList().containsAll(List.of("show", "wrap", "SIZE")));
    }
}
