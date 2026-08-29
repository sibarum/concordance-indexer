package sibarum.concordance.index;

/**
 * A place where a name is used, and how it is used there.
 *
 * <p><b>References are matched by name, not by resolution.</b> Concordance has no resolver yet, so a call to
 * {@code close()} is recorded as a use of the name {@code close} with one argument, and "find usages of
 * {@code close}" returns every one of them regardless of receiver type. That is an over-approximation: it finds
 * everything real and some things besides.
 *
 * <p>The alternative — dropping uses that cannot be resolved — would under-approximate, and an index that
 * silently omits a usage is worse than one that offers an extra. When the resolver lands, {@link #target} gains
 * a qualified form and the extras disappear.
 *
 * @param kind   how the name is being used
 * @param target the name used, plus arity for a call: {@code close/0}
 * @param name   the simple name alone
 * @param from   the qualified name of the declaration this use sits inside
 * @param at     where the use is
 */
public record Reference(Kind kind, String target, String name, String from, SourceRef at) {

    public enum Kind {
        /** A method call. */
        CALL,
        /** A constructor call — {@code new Foo(…)}. */
        CONSTRUCT,
        /** A type named in a declaration, parameter, or cast. */
        TYPE_USE,
        /** A field or a bare name read. */
        READ,
        /** A supertype named in {@code extends}. */
        EXTENDS,
        /** A supertype named in {@code implements}. */
        IMPLEMENTS
    }

    public Reference {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Reference name must be non-empty");
        }
    }

    /** A call to {@code name} taking {@code arity} arguments, as a target key. */
    public static String callTarget(String name, int arity) {
        return name + "/" + arity;
    }
}
