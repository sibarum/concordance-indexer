package sibarum.concordance.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sibarum.concordance.project.Classpath;
import sibarum.concordance.project.Coordinates;
import sibarum.concordance.project.Dependency;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** A query walking project and libraries stage by stage, and stopping when it is told to. */
class SearchTest {

    @TempDir Path dir;

    private Path app;
    private Path canvas;
    private Path circle;
    private Path direct;
    private Path deep;

    /** Runs a search to the end on the caller's thread, so a test can read the outcome straight after. */
    private static final Executor INLINE = Runnable::run;

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

    /**
     * A project of three files over two libraries: {@code figures} declared directly, and {@code drawing} brought
     * in by it. Circle extends a Figure from the first, which implements a Drawable from the second.
     */
    private ProjectIndex corpus() throws IOException {
        app = java(dir.resolve("src/app/App.java"), "package app; class App { void run() { new Circle().draw(); } }");
        canvas = java(dir.resolve("src/app/Canvas.java"), "package app; class Canvas { Shape shape; }");
        circle = java(dir.resolve("src/app/Circle.java"),
                "package app; class Circle extends lib.figures.Figure { public void draw() {} }");
        direct = jar(dir.resolve("repo/figures-sources.jar"), "lib/figures/Figure.java", """
                package lib.figures;
                public abstract class Figure implements lib.drawing.Drawable {
                    public abstract void draw();
                    public Shape outline() { return null; }
                    void internal() {}
                }
                """);
        deep = jar(dir.resolve("repo/drawing-sources.jar"), "lib/drawing/Drawable.java",
                "package lib.drawing; public interface Drawable { void draw(); }");
        ProjectIndex live = ProjectIndex.empty(dir);
        live.synchronize(List.of(app, canvas, circle), List.of(deep, direct));   // deliberately out of depth order
        return live;
    }

    private Plan plan(Path... recent) {
        Classpath classpath = new Classpath(List.of(
                new Dependency(new Coordinates("lib", "figures", "1"), "jar", "", "compile", 1, null, direct),
                new Dependency(new Coordinates("lib", "drawing", "1"), "jar", "", "compile", 2, null, deep)),
                List.of());
        return Plan.of(java.util.Arrays.stream(recent).map(Source::of).toList(), classpath);
    }

    /** Collects everything a search says, in the order it says it. */
    private static class Log<T> implements Search.Listener<T> {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());

        @Override public void stage(Plan.Stage stage) { events.add("stage " + stage.name()); }

        @Override public void hits(Plan.Stage stage, List<Hit<T>> hits) { events.add("hits " + hits.size()); }

        @Override public void paused(Plan.Stage stage) { events.add("paused in " + stage.name()); }

        @Override public void ended(Search.State state) { events.add("ended " + state); }
    }

    private static <T> List<T> items(List<Hit<T>> hits) {
        return hits.stream().map(Hit::item).toList();
    }

    private static List<String> names(List<Hit<Symbol>> hits) {
        return hits.stream().map(h -> h.item().name() + "@" + h.stage().name()).toList();
    }

    // --- the plan -------------------------------------------------------------------------------

    @Test
    void openFilesThenTheProjectThenDirectLibrariesThenTheRest() throws IOException {
        Corpus corpus = corpus().corpus();
        List<Plan.Stage> stages = plan(circle).stages(corpus.sources());

        assertEquals(List.of(Plan.OPEN, Plan.PROJECT, Plan.DIRECT_LIBRARIES, Plan.OTHER_LIBRARIES),
                stages.stream().map(Plan.Stage::name).toList());
        assertEquals(List.of(Source.of(circle)), stages.get(0).sources());
        assertEquals(List.of(Source.of(app), Source.of(canvas)), stages.get(1).sources(),
                "an open file is not searched twice");
        assertEquals(List.of(new Source.InArchive(direct, "lib/figures/Figure.java")), stages.get(2).sources());
        assertEquals(List.of(new Source.InArchive(deep, "lib/drawing/Drawable.java")), stages.get(3).sources());
    }

    @Test
    void anEmptyStageIsLeftOutAndAnUnknownJarCountsAsDirect() throws IOException {
        Corpus corpus = corpus().corpus();
        List<Plan.Stage> stages = Plan.everything().stages(corpus.sources());
        assertEquals(List.of(Plan.PROJECT, Plan.DIRECT_LIBRARIES), stages.stream().map(Plan.Stage::name).toList());
        assertEquals(2, stages.get(1).sources().size());
    }

    // --- what it finds --------------------------------------------------------------------------

    @Test
    void hitsArriveNearestFirstAndTheBestTierCanTighten() throws Exception {
        Search<Symbol> search = Search.start(corpus().corpus(), plan(canvas), Query.names("draw"), new Log<>(),
                INLINE, Duration.ZERO);
        List<Hit<Symbol>> hits = search.done().get();

        assertEquals(List.of("draw@project", "draw@direct libraries", "Drawable@other libraries",
                "draw@other libraries"), names(hits));
        assertEquals(List.of(0, 0, 2, 0), hits.stream().map(Hit::rank).toList());
        assertEquals(3, Hit.bestTier(hits).size());
        assertEquals(Search.State.DONE, search.state());
    }

    @Test
    void implementationsAreFoundThroughLibrariesAndReportedNearestFirst() throws Exception {
        List<Hit<Symbol>> hits = Search.start(corpus().corpus(), plan(), Query.implementationsOf("Drawable"),
                new Log<>(), INLINE, Duration.ZERO).done().get();
        assertEquals(List.of("Circle@project", "Figure@direct libraries"), names(hits),
                "Circle reaches Drawable only through Figure, two stages later; it still comes first");
    }

    @Test
    void aLibraryTypesMembersAreWhatItExports() throws Exception {
        List<Hit<Symbol>> hits = Search.start(corpus().corpus(), plan(), Query.membersOf("lib.figures.Figure"),
                new Log<>(), INLINE, Duration.ZERO).done().get();
        assertEquals(List.of("draw", "outline"), hits.stream().map(h -> h.item().name()).toList());

        List<Hit<Symbol>> bySimpleName = Search.start(corpus().corpus(), plan(), Query.membersOf("figure"),
                new Log<>(), INLINE, Duration.ZERO).done().get();
        assertEquals(List.of(1, 1), bySimpleName.stream().map(Hit::rank).toList(), "right but for case");
    }

    @Test
    void usagesOfADeclarationAgreeWithTheIndex() throws Exception {
        ProjectIndex live = corpus();
        Symbol draw = live.index().namesContaining("draw").stream()
                .filter(s -> s.owner().equals("app.Circle")).findFirst().orElseThrow();
        List<Hit<Reference>> hits = Search.start(live.corpus(), plan(), Query.usagesOf(draw), new Log<>(),
                INLINE, Duration.ZERO).done().get();
        assertEquals(live.index().usagesOf(draw), items(hits));
    }

    @Test
    void theListenerHearsStagesBatchesAndTheEnd() throws Exception {
        Log<Reference> log = new Log<>();
        Search.start(corpus().corpus(), plan(), Query.usages("Shape"), log, INLINE, Duration.ofHours(1))
                .done().get();
        assertEquals(List.of("stage project", "hits 1", "stage direct libraries", "stage other libraries",
                "ended DONE"), log.events, "a long batch interval still hands over at the end of each stage");
    }

    @Test
    void theCorpusIsKeptUntilTheIndexChanges() throws IOException {
        ProjectIndex live = corpus();
        Corpus first = live.corpus();
        assertSame(first, live.corpus());
        live.synchronize(List.of(app), List.of());
        assertNotSame(first, live.corpus());
        assertEquals(List.of(Source.of(app)), List.copyOf(live.corpus().sources()));
    }

    // --- pausing, cancelling, failing ----------------------------------------------------------

    /** A search whose thread starts only once the test holds the search, so a listener can steer it. */
    private <T> Search<T> steered(Query<T> query, Search.Listener<T> listener) throws IOException {
        AtomicReference<Runnable> task = new AtomicReference<>();
        Search<T> search = Search.start(corpus().corpus(), plan(), query, listener, task::set, Duration.ZERO);
        Thread.ofVirtual().start(task.get());
        return search;
    }

    @Test
    void aPausedSearchStopsBetweenSourcesAndResumesWhereItWas() throws Exception {
        AtomicReference<Search<Symbol>> self = new AtomicReference<>();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        Log<Symbol> log = new Log<>() {
            @Override public void hits(Plan.Stage stage, List<Hit<Symbol>> hits) {
                super.hits(stage, hits);
                if (stage.name().equals(Plan.PROJECT) && paused.getCount() > 0) {
                    awaitQuietly(held);
                    self.get().pause();
                }
            }

            @Override public void paused(Plan.Stage stage) {
                super.paused(stage);
                paused.countDown();
            }
        };
        Search<Symbol> search = steered(Query.names("draw"), log);
        self.set(search);
        held.countDown();

        assertTrue(paused.await(5, TimeUnit.SECONDS), log.events.toString());
        assertEquals(Search.State.PAUSED, search.state());
        assertEquals(List.of("draw@project"), names(search.hits()));
        assertFalse(search.done().isDone());

        search.resume();
        assertEquals(4, search.done().get(5, TimeUnit.SECONDS).size());
        assertEquals(Search.State.DONE, search.state());
        assertTrue(log.events.contains("paused in " + Plan.DIRECT_LIBRARIES), log.events.toString());
    }

    @Test
    void cancellingAPausedSearchEndsItAndKeepsWhatItFound() throws Exception {
        AtomicReference<Search<Symbol>> self = new AtomicReference<>();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1);
        Log<Symbol> log = new Log<>() {
            @Override public void hits(Plan.Stage stage, List<Hit<Symbol>> hits) {
                super.hits(stage, hits);
                awaitQuietly(held);
                self.get().pause();
            }

            @Override public void paused(Plan.Stage stage) {
                paused.countDown();
            }
        };
        Search<Symbol> search = steered(Query.names("draw"), log);
        self.set(search);
        held.countDown();
        assertTrue(paused.await(5, TimeUnit.SECONDS));

        search.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> search.done().get(5, TimeUnit.SECONDS));
        assertEquals(Search.State.CANCELLED, search.state());
        assertEquals(List.of("draw@project"), names(search.hits()));
        assertFalse(log.events.contains("stage " + Plan.OTHER_LIBRARIES), log.events.toString());
    }

    @Test
    void aListenerThatThrowsFailsTheSearch() throws IOException {
        IllegalStateException boom = new IllegalStateException("boom");
        Search<Symbol> search = Search.start(corpus().corpus(), plan(), Query.names("draw"),
                new Search.Listener<>() {
                    @Override public void hits(Plan.Stage stage, List<Hit<Symbol>> hits) { throw boom; }
                }, INLINE, Duration.ZERO);
        ExecutionException failed = assertThrows(ExecutionException.class, () -> search.done().get());
        assertSame(boom, failed.getCause());
        assertEquals(Search.State.FAILED, search.state());
    }

    @Test
    void pausingOrResumingAFinishedSearchChangesNothing() throws Exception {
        Search<Symbol> search = Search.start(corpus().corpus(), plan(), Query.names("draw"), new Log<>(),
                INLINE, Duration.ZERO);
        search.done().get();
        search.pause();
        search.resume();
        search.cancel();
        assertEquals(Search.State.DONE, search.state());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
