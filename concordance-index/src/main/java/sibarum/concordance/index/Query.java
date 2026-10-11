package sibarum.concordance.index;

import java.util.HashSet;
import java.util.Set;

/**
 * A question a staged {@link Search} can ask one source at a time.
 *
 * <p>Most questions are local — whether a name matches is a fact about that declaration alone — and answer slice
 * by slice. A few are not: whether a type implements an interface may depend on a supertype declared in a
 * library searched three stages later. Those look at the whole {@link Corpus} once, in {@link #prepare}, and then
 * report what they found slice by slice like the rest, so their answers still arrive nearest first. Preparing is
 * cheap for them, because the whole-corpus part is over type declarations only.
 */
@FunctionalInterface
public interface Query<T> {

    /** Receives a query's matches in one slice. */
    @FunctionalInterface
    interface Emit<T> {
        void hit(T item, int rank);
    }

    /** Matches one slice. Called on the search's thread, one slice at a time. */
    @FunctionalInterface
    interface Matcher<T> {
        void match(Corpus.Slice slice, Emit<T> emit);
    }

    /** Readies the query for one search over {@code corpus}. */
    Matcher<T> prepare(Corpus corpus);

    // --- the questions an editor asks ---------------------------------------------------------

    /** Declarations whose simple name answers {@code fragment}, at every tier; see {@link Index#namesContaining}. */
    static Query<Symbol> names(String fragment) {
        return corpus -> (slice, emit) -> {
            for (Symbol s : slice.symbols()) {
                int rank = Names.rank(s.name(), fragment);
                if (rank != Names.NO_MATCH) emit.hit(s, rank);
            }
        };
    }

    /**
     * Uses of the simple name {@code name}, at every tier. {@link Index#usagesOf(String)} keeps only the best tier;
     * here, {@link Hit#bestTier} does that over what has arrived so far.
     */
    static Query<Reference> usages(String name) {
        return corpus -> (slice, emit) -> {
            for (Reference r : slice.references()) {
                int rank = Names.rank(r.name(), name);
                if (rank != Names.NO_MATCH) emit.hit(r, rank);
            }
        };
    }

    /** Uses of one declaration, as {@link Index#usagesOf(Symbol)} finds them. Every hit is exact. */
    static Query<Reference> usagesOf(Symbol symbol) {
        return corpus -> (slice, emit) -> {
            for (Reference r : slice.references()) {
                if (Index.isUseOf(r, symbol)) emit.hit(r, 0);
            }
        };
    }

    /**
     * What a type declares — its methods, constructors, fields and member types — which for a library type is
     * exactly what it offers the project. A qualified name must match exactly; a simple one matches any type of
     * that name, exactly (rank 0) or but for case (rank 1).
     */
    static Query<Symbol> membersOf(String typeName) {
        boolean qualified = typeName.contains(".");
        return corpus -> (slice, emit) -> {
            for (Symbol s : slice.symbols()) {
                if (qualified) {
                    if (s.owner().equals(typeName)) emit.hit(s, 0);
                    continue;
                }
                int dot = s.owner().lastIndexOf('.');
                int rank = Names.rank(dot < 0 ? s.owner() : s.owner().substring(dot + 1), typeName);
                if (rank <= 1) emit.hit(s, rank);
            }
        };
    }

    /** Types extending or implementing {@code typeName}, transitively, wherever they or their supertypes live. */
    static Query<Symbol> implementationsOf(String typeName) {
        return corpus -> found(corpus.index().implementationsOf(typeName));
    }

    /** Methods implementing or overriding {@code methodName} of {@code ownerTypeName}, wherever they live. */
    static Query<Symbol> implementationsOfMethod(String ownerTypeName, String methodName) {
        return corpus -> found(corpus.index().implementationsOfMethod(ownerTypeName, methodName));
    }

    private static Matcher<Symbol> found(Iterable<Symbol> answers) {
        Set<Symbol> set = new HashSet<>();
        answers.forEach(set::add);
        return (slice, emit) -> {
            for (Symbol s : slice.symbols()) {
                if (set.contains(s)) emit.hit(s, 0);
            }
        };
    }
}
