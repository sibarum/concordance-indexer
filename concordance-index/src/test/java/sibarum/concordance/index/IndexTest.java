package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import sibarum.concordance.project.MavenProject;
import sibarum.concordance.project.MavenProjectReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexTest {

    private static final Path FAKE = Path.of("Shapes.java");

    private static final String SHAPES = """
            package demo;

            interface Shape {
                double area();
                String describe();
            }

            abstract class Base implements Shape {
                protected String label;
                public String describe() { return label + " " + area(); }
            }

            class Circle extends Base {
                private final double radius;
                Circle(double radius) { this.radius = radius; }
                public double area() { return radius * radius * 3; }
            }

            class Square extends Base {
                private final double side;
                Square(double side) { this.side = side; }
                public double area() { return side * side; }
            }

            record Point(int x, int y) {}

            class Scene {
                void render() {
                    Shape a = new Circle(2);
                    Shape b = new Square(3);
                    a.area();
                    b.area();
                    a.describe();
                }
            }
            """;

    private static Index shapes() {
        return IndexBuilder.buildSource(FAKE, SHAPES);
    }

    // --- find names with a substring ----------------------------------------------------------

    @Test
    void findsNamesContainingAFragmentCaseInsensitively() {
        List<Symbol> hits = shapes().namesContaining("are");
        assertTrue(hits.stream().anyMatch(s -> s.name().equals("area")), names(hits));
        assertTrue(hits.stream().anyMatch(s -> s.name().equals("Square")), names(hits));
        assertTrue(hits.stream().allMatch(s -> s.name().toLowerCase().contains("are")), names(hits));
    }

    @Test
    void anExactMatchSortsAboveAMerelyContainingOne() {
        List<Symbol> hits = shapes().namesContaining("area");
        assertEquals("area", hits.get(0).name(),
                "typing a whole name must not bury it under what contains it: " + names(hits));
    }

    @Test
    void everyResultHasSomewhereACaretCanGo() {
        for (Symbol symbol : shapes().namesContaining("a")) {
            assertEquals(new Source.OnDisk(FAKE), symbol.at().source());
            assertTrue(symbol.at().line() > 0, symbol + " has no line");
        }
    }

    // --- matching when the caller half-knows the name ------------------------------------------

    @Test
    void findsANameByItsCamelHumps() {
        List<Symbol> hits = shapes().namesContaining("sh");
        assertTrue(hits.stream().anyMatch(s -> s.name().equals("Shape")), names(hits));
    }

    @Test
    void findsALongNameByItsHumpsAlone() {
        Index index = IndexBuilder.buildSource(FAKE, "class MavenProjectReader {}");
        assertTrue(index.namesContaining("MPR").stream().anyMatch(s -> s.name().equals("MavenProjectReader")),
                "MPR is how anyone types MavenProjectReader when they are guessing");
    }

    @Test
    void findsANameByASubsequenceOfIt() {
        List<Symbol> hits = shapes().namesContaining("dscb");
        assertTrue(hits.stream().anyMatch(s -> s.name().equals("describe")),
                "a caller who dropped the vowels still gets there: " + names(hits));
    }

    @Test
    void aLooserMatchSortsBelowATighterOne() {
        List<Symbol> hits = shapes().namesContaining("Shape");
        assertEquals("Shape", hits.get(0).name(),
                "permissiveness must not cost the caller who knows the name: " + names(hits));
    }

    @Test
    void anExactUsageQueryIsNotWidened() {
        List<Reference> exact = shapes().usagesOf("area");
        assertFalse(exact.isEmpty());
        assertTrue(exact.stream().allMatch(r -> r.name().equals("area")),
                "`usages area' must not drag in Square just because it contains the letters: " + exact);
    }

    @Test
    void aUsageQueryWidensOnlyWhenTheExactNameFindsNothing() {
        List<Reference> uses = shapes().usagesOf("AREA");
        assertFalse(uses.isEmpty(), "nothing is named AREA, so the next-best reading is area");
        assertTrue(uses.stream().allMatch(r -> r.name().equals("area")), uses.toString());
    }

    @Test
    void aHalfTypedTypeStillFindsItsImplementations() {
        List<String> found = shapes().implementationsOf("Shap").stream().map(Symbol::name).toList();
        assertTrue(found.contains("Circle"), found.toString());
        assertTrue(found.contains("Base"), found.toString());
    }

    @Test
    void aHalfTypedMethodStillFindsItsImplementations() {
        List<Symbol> impls = shapes().implementationsOfMethod("Shape", "ar");
        assertFalse(impls.isEmpty());
        assertTrue(impls.stream().allMatch(s -> s.name().equals("area")),
                impls.stream().map(Symbol::name).toList().toString());
    }

    @Test
    void aNameNothingAnswersStillAnswersWithNothing() {
        assertTrue(shapes().namesContaining("zzq").isEmpty());
        assertTrue(shapes().usagesOf("zzq").isEmpty());
        assertTrue(shapes().implementationsOf("zzq").isEmpty());
    }

    // --- find implementations -----------------------------------------------------------------

    @Test
    void findsImplementationsTransitivelyRatherThanJustTheDirectChild() {
        List<Symbol> impls = shapes().implementationsOf("Shape");
        List<String> found = impls.stream().map(Symbol::name).toList();

        assertTrue(found.contains("Base"), found.toString());
        assertTrue(found.contains("Circle"),
                "Circle implements Shape through Base; a direct-children answer would miss it: " + found);
        assertTrue(found.contains("Square"), found.toString());
        assertFalse(found.contains("Scene"), "Scene merely uses Shape: " + found);
    }

    @Test
    void findsTheMethodsThatImplementAnInterfaceMethod() {
        List<Symbol> impls = shapes().implementationsOfMethod("Shape", "area");
        List<String> owners = impls.stream().map(Symbol::owner).toList();

        assertTrue(owners.contains("demo.Circle"), owners.toString());
        assertTrue(owners.contains("demo.Square"), owners.toString());
        assertTrue(impls.stream().allMatch(s -> s.kind() == Symbol.Kind.METHOD));
    }

    @Test
    void aTypeNobodyExtendsHasNoImplementations() {
        assertTrue(shapes().implementationsOf("Point").isEmpty());
    }

    // --- find usages --------------------------------------------------------------------------

    @Test
    void findsUsagesOfAMethodByNameAndArity() {
        Index index = shapes();
        Symbol area = index.namesContaining("area").stream()
                .filter(s -> s.kind() == Symbol.Kind.METHOD && s.owner().equals("demo.Shape"))
                .findFirst().orElseThrow();

        List<Reference> usages = index.usagesOf(area);
        assertTrue(usages.size() >= 3,
                "a.area(), b.area(), and the call inside describe(): " + usages);
        assertTrue(usages.stream().allMatch(r -> r.kind() == Reference.Kind.CALL));
        assertTrue(usages.stream().anyMatch(r -> r.from().contains("render")),
                "one usage is inside Scene#render: " + usages);
    }

    @Test
    void findsUsagesOfAType() {
        List<Reference> usages = shapes().usagesOf("Circle");
        assertTrue(usages.stream().anyMatch(r -> r.kind() == Reference.Kind.CONSTRUCT),
                "new Circle(2) is a usage: " + usages);
        assertTrue(usages.stream().anyMatch(r -> r.from().contains("render")),
                "and it is attributed to the method containing it: " + usages);
    }

    @Test
    void anExtendsClauseIsAUsageOfTheSupertype() {
        List<Reference> usages = shapes().usagesOf("Base");
        assertTrue(usages.stream().anyMatch(r -> r.kind() == Reference.Kind.EXTENDS),
                "Circle and Square extend Base, which is how a type hierarchy is navigable: " + usages);
        assertTrue(usages.stream().anyMatch(r -> r.from().equals("demo.Circle")), usages.toString());
    }

    @Test
    void aConstructorCallIsOneUsageNotTwo() {
        List<Reference> usages = shapes().usagesOf("Circle");
        long onTheNewLine = usages.stream()
                .filter(r -> r.at().line() == usages.get(0).at().line())
                .count();
        assertEquals(1, onTheNewLine,
                "new Circle(2) names the type once; showing the same line twice is noise: " + usages);
    }

    @Test
    void usagesComeBackInSourceOrder() {
        List<Reference> usages = shapes().usagesOf("area");
        for (int i = 1; i < usages.size(); i++) {
            assertTrue(usages.get(i - 1).at().line() <= usages.get(i).at().line(),
                    "results must be ordered so a caller can page through them: " + usages);
        }
    }

    @Test
    void aDeclarationIsNotAUsageOfItself() {
        Index index = shapes();
        Symbol describe = index.namesContaining("describe").stream()
                .filter(s -> s.kind() == Symbol.Kind.METHOD && s.owner().equals("demo.Base"))
                .findFirst().orElseThrow();

        assertTrue(index.usagesOf(describe).stream()
                        .noneMatch(r -> r.at().line() == describe.at().line()),
                "the declaration line must not appear among its own usages");
    }

    // --- declarations -------------------------------------------------------------------------

    @Test
    void recordComponentsAreIndexedAsFields() {
        List<Symbol> hits = shapes().namesContaining("x");
        assertTrue(hits.stream().anyMatch(s -> s.kind() == Symbol.Kind.FIELD && s.owner().equals("demo.Point")),
                "a record component is a name someone will search for: " + names(hits));
    }

    @Test
    void kindsAreDistinguished() {
        Index index = shapes();
        assertEquals(Symbol.Kind.INTERFACE, typeNamed(index, "Shape").kind());
        assertEquals(Symbol.Kind.CLASS, typeNamed(index, "Circle").kind());
        assertEquals(Symbol.Kind.RECORD, typeNamed(index, "Point").kind());
    }

    // --- the real thing -----------------------------------------------------------------------

    @Test
    void indexesThisProject() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        Path root = here.getFileName().toString().startsWith("concordance-") ? here.getParent() : here;
        MavenProject project = MavenProjectReader.read(root);

        Index index = IndexBuilder.build(project);
        assertFalse(index.symbols().isEmpty(), index.summary());

        // Queries an editor would actually run against this codebase.
        assertFalse(index.namesContaining("Index").isEmpty());
        assertTrue(index.namesContaining("MavenProjectReader").stream()
                .anyMatch(s -> s.kind() == Symbol.Kind.CLASS));
        assertFalse(index.usagesOf("build").isEmpty(), "IndexBuilder.build is called from tests at least");
    }

    private static Symbol typeNamed(Index index, String name) {
        return index.types().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static String names(List<Symbol> symbols) {
        return symbols.stream().map(Symbol::name).toList().toString();
    }
}
