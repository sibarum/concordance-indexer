package sibarum.concordance.drafter;

import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.Map;

/**
 * Maps Java types onto Cardinal {@link Sort}s.
 *
 * <p><b>The sort name is load-bearing, not cosmetic.</b> Cardinal routes obligation discharge by domain, and it
 * decides the domain by name: a sort named {@code "Decimal"} goes to dense-valid reasoning, and everything else
 * goes to the integer engine, which legitimately uses integer-strict cuts — {@code > c} implies {@code >= c+1}.
 * That cut is what makes {@code [Int:@>0] → [Int:@>=1]} provable, and it is false for {@code double}: {@code 0.5}
 * is the counterexample. So a Java {@code double} mapped to a sort named anything other than {@code "Decimal"}
 * would be reasoned about unsoundly, silently. Hence {@link #DECIMAL} and {@link #INT} are the only two names
 * this class is allowed to invent, and every numeric Java type resolves to exactly one of them.
 *
 * <p>Reference types other than the ones named here stay opaque: {@code Sort.of("java.time.LocalDate")} carries
 * no predicate and licenses no arithmetic, which is the honest position for a type we know nothing about.
 *
 * <p><b>Nullability is not modelled.</b> {@code SymExpr} has no null, so a boxed {@code Integer} maps to
 * {@link #INT} on the strength of its <em>value</em> domain, and nothing in these sorts says whether a reference
 * can be absent. A consumer must not read non-nullness out of a sort. Null obligations are a separate slice.
 */
public final class JavaSorts {

    /** The discrete domain. Integer-strict cuts apply. */
    public static final String INT_NAME = "Int";
    /** The dense domain. Integer-strict cuts must not apply. */
    public static final String DECIMAL_NAME = "Decimal";
    /** Booleans, used for guards and predicates rather than arithmetic. */
    public static final String BOOL_NAME = "Bool";

    public static final Sort INT = Sort.of(INT_NAME);
    public static final Sort DECIMAL = Sort.of(DECIMAL_NAME);
    public static final Sort BOOL = Sort.of(BOOL_NAME);
    public static final Sort STRING = Sort.of("String");
    /** A method that returns nothing still needs a result var for the graph's shape; it carries this. */
    public static final Sort VOID = Sort.of("Void");

    /** Java types whose values are discrete, and so belong to the integer domain. */
    private static final Map<String, Sort> DISCRETE = Map.ofEntries(
            Map.entry("byte", INT),
            Map.entry("short", INT),
            Map.entry("int", INT),
            Map.entry("long", INT),
            Map.entry("char", INT),
            Map.entry("java.lang.Byte", INT),
            Map.entry("java.lang.Short", INT),
            Map.entry("java.lang.Integer", INT),
            Map.entry("java.lang.Long", INT),
            Map.entry("java.lang.Character", INT),
            Map.entry("java.math.BigInteger", INT));

    /** Java types whose values are dense, and so must never see an integer-strict cut. */
    private static final Map<String, Sort> DENSE = Map.of(
            "float", DECIMAL,
            "double", DECIMAL,
            "java.lang.Float", DECIMAL,
            "java.lang.Double", DECIMAL,
            "java.math.BigDecimal", DECIMAL);

    private JavaSorts() {}

    /**
     * The sort for a Java type, named as the drafter resolved it — primitives by keyword, reference types by
     * fully-qualified name. An unrecognised name yields an opaque sort of that name, never a guess at its domain.
     */
    public static Sort of(String javaTypeName) {
        if (javaTypeName == null || javaTypeName.isEmpty()) {
            throw new IllegalArgumentException("java type name must be non-empty");
        }
        Sort discrete = DISCRETE.get(javaTypeName);
        if (discrete != null) return discrete;
        Sort dense = DENSE.get(javaTypeName);
        if (dense != null) return dense;
        return switch (javaTypeName) {
            case "boolean", "java.lang.Boolean" -> BOOL;
            case "java.lang.String" -> STRING;
            case "void" -> VOID;
            default -> Sort.of(javaTypeName);
        };
    }

    /** The same mapping, carrying a refinement predicate the drafter transcribed from source. */
    public static Sort refined(String javaTypeName, SymExpr predicate) {
        if (predicate == null) {
            return of(javaTypeName);
        }
        return Sort.refined(of(javaTypeName).name(), predicate);
    }

    /**
     * Whether a sort will be reasoned about densely. Mirrors Cardinal's own routing predicate, which is
     * package-private to its receipts module; a drafter needs the same answer when deciding whether a
     * transcription is legitimate in the target domain.
     */
    public static boolean isDense(Sort sort) {
        return sort != null && DECIMAL_NAME.equals(sort.name());
    }

    /** Whether a sort belongs to a numeric domain at all, dense or discrete. */
    public static boolean isNumeric(Sort sort) {
        return sort != null && (INT_NAME.equals(sort.name()) || DECIMAL_NAME.equals(sort.name()));
    }
}
