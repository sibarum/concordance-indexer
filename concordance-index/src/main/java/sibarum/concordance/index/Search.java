package sibarum.concordance.index;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * A query running over a corpus in the background, stage by stage, nearest answers first — and pausable and
 * cancellable while it does.
 *
 * <p>An index that holds a project's libraries cannot answer "everything named like this" instantly, and should
 * not make anyone wait for the slowest part before showing the fastest. So a search walks its {@link Plan}: the
 * open files, the project, the libraries it links to, the rest. It reports hits as it goes, in batches, and the caller
 * shows them as they arrive. When the question changes — another keystroke — the caller cancels this search and
 * starts the next; when the editor needs the CPU for something else, it pauses it.
 *
 * <pre>{@code
 * Search<Symbol> search = Search.start(live.corpus(), Plan.of(recent, classpath), Query.names(typed),
 *         new Search.Listener<>() {
 *             public void hits(Plan.Stage stage, List<Hit<Symbol>> hits) { ui.later(() -> view.add(hits)); }
 *         });
 * ...
 * search.cancel();   // the user typed another letter
 * }</pre>
 *
 * <p><b>Granularity.</b> A search checks for pause and cancel between sources, so it stops within the time one
 * file takes to match — microseconds for a typical one. {@link #pause} and {@link #cancel} return at once; the
 * search acts on them at its next check, and says so through {@link Listener#paused} and {@link Listener#ended}.
 *
 * <p><b>Threads.</b> Listener calls arrive on the search's own thread, in order, never two at once. A listener
 * that touches a UI hands the work to the UI thread itself. A listener that throws ends the search as
 * {@link State#FAILED}.
 */
public final class Search<T> {

    /** How often, at most, a running search hands over a batch of hits within a stage. */
    public static final Duration DEFAULT_BATCH = Duration.ofMillis(50);

    public enum State { RUNNING, PAUSED, DONE, CANCELLED, FAILED }

    /** What a search tells its caller as it goes. Every method has a default that ignores it. */
    public interface Listener<T> {
        /** A stage is starting. */
        default void stage(Plan.Stage stage) {}

        /** Hits found in {@code stage} since the last call. Never empty. */
        default void hits(Plan.Stage stage, List<Hit<T>> hits) {}

        /** The search has stopped, at a point between sources in {@code stage}, until {@link #resume}. */
        default void paused(Plan.Stage stage) {}

        /** The search is over: {@link State#DONE}, {@link State#CANCELLED} or {@link State#FAILED}. */
        default void ended(State state) {}
    }

    private final Corpus corpus;
    private final List<Plan.Stage> stages;
    private final Query<T> query;
    private final Listener<T> listener;
    private final long batchNanos;
    private final CompletableFuture<List<Hit<T>>> done = new CompletableFuture<>();
    private final List<Hit<T>> found = new ArrayList<>();
    private final Object lock = new Object();

    private State state = State.RUNNING;
    private boolean pauseRequested;
    private boolean cancelRequested;

    private Search(Corpus corpus, Plan plan, Query<T> query, Listener<T> listener, Duration batch) {
        this.corpus = corpus;
        this.stages = plan.stages(corpus);
        this.query = query;
        this.listener = listener;
        this.batchNanos = batch.toNanos();
    }

    /** Starts {@code query} over {@code corpus} on a virtual thread of its own, in the order {@code plan} gives. */
    public static <T> Search<T> start(Corpus corpus, Plan plan, Query<T> query, Listener<T> listener) {
        return start(corpus, plan, query, listener, task -> Thread.ofVirtual().name("concordance-search").start(task),
                DEFAULT_BATCH);
    }

    /**
     * Starts {@code query} on {@code executor}, handing over hits at most every {@code batch} within a stage —
     * {@link Duration#ZERO} for after every source that has any — and always at the end of a stage.
     */
    public static <T> Search<T> start(Corpus corpus, Plan plan, Query<T> query, Listener<T> listener,
                                      Executor executor, Duration batch) {
        Search<T> search = new Search<>(corpus, plan, query, listener, batch);
        executor.execute(search::run);
        return search;
    }

    /** The stages this search walks, in order. */
    public List<Plan.Stage> stages() {
        return stages;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Every hit so far, in the order found: stage by stage, and nearest first within a stage. */
    public List<Hit<T>> hits() {
        synchronized (lock) {
            return List.copyOf(found);
        }
    }

    /**
     * Completes with every hit when the search finishes; is cancelled when the search is; completes exceptionally
     * with what the listener or query threw when it fails.
     */
    public CompletableFuture<List<Hit<T>>> done() {
        return done;
    }

    /** Asks a running search to stop between two sources until {@link #resume}. Does nothing to a finished one. */
    public void pause() {
        synchronized (lock) {
            if (state == State.RUNNING) {
                pauseRequested = true;
                state = State.PAUSED;
            }
        }
    }

    public void resume() {
        synchronized (lock) {
            if (state == State.PAUSED) {
                pauseRequested = false;
                state = State.RUNNING;
                lock.notifyAll();
            }
        }
    }

    /** Asks the search to stop for good, paused or not. Hits already reported stay reported. */
    public void cancel() {
        synchronized (lock) {
            cancelRequested = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Thrown inside the search's own thread to unwind it when it is cancelled. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    private void run() {
        try {
            Query.Matcher<T> matcher = query.prepare(corpus);
            for (Plan.Stage stage : stages) {
                listener.stage(stage);
                List<Hit<T>> batch = new ArrayList<>();
                long lastHandover = System.nanoTime();
                for (Source source : stage.sources()) {
                    checkpoint(stage);
                    corpus.slice(source).ifPresent(slice ->
                            matcher.match(slice, (item, rank) -> batch.add(new Hit<>(item, rank, stage))));
                    if (!batch.isEmpty() && System.nanoTime() - lastHandover >= batchNanos) {
                        handOver(stage, batch);
                        lastHandover = System.nanoTime();
                    }
                }
                if (!batch.isEmpty()) handOver(stage, batch);
            }
            finish(State.DONE, null);
        } catch (Cancelled c) {
            finish(State.CANCELLED, null);
        } catch (RuntimeException | Error e) {
            finish(State.FAILED, e);
        }
    }

    private void handOver(Plan.Stage stage, List<Hit<T>> batch) {
        List<Hit<T>> hits = List.copyOf(batch);
        batch.clear();
        synchronized (lock) {
            found.addAll(hits);
        }
        listener.hits(stage, hits);
    }

    /** Where a search may stop: unwinds if cancelled, waits if paused, and returns at once otherwise. */
    private void checkpoint(Plan.Stage stage) {
        synchronized (lock) {
            if (cancelRequested) throw new Cancelled();
            if (!pauseRequested) return;
        }
        listener.paused(stage);
        synchronized (lock) {
            while (pauseRequested && !cancelRequested) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelRequested = true;
                }
            }
            if (cancelRequested) throw new Cancelled();
        }
    }

    private void finish(State end, Throwable failure) {
        List<Hit<T>> all;
        synchronized (lock) {
            state = end;
            all = List.copyOf(found);
        }
        try {
            listener.ended(end);
        } finally {
            switch (end) {
                case DONE -> done.complete(all);
                case CANCELLED -> done.cancel(false);
                default -> done.completeExceptionally(failure);
            }
        }
    }
}
