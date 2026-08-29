package sibarum.concordance.drafter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;
import sibarum.cardinal.receipts.*;
import sibarum.concordance.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Transcribes Java into a Cardinal receipt-graph. The second drafter; {@link Drafter} is the first.
 *
 * <p>Like the first, this one does no reasoning. Every fact it emits is visible in the source: a body equation, a
 * restated guard, a call. Anything it cannot transcribe faithfully it declines to transcribe at all, so an
 * absent equation means "the source said something this drafter cannot say", never "assume nothing happened".
 *
 * <h2>What Java has that Pontif IR does not</h2>
 *
 * <ul>
 *   <li><b>Guard clauses.</b> {@code if (n < 0) throw ...} is a path that leaves without a result, so it becomes
 *       a {@link Branch.Completion#THROW} branch, and its negation joins the guard of every path that survives
 *       it. That is how a Java precondition reaches the graph.
 *   <li><b>Loops.</b> A loop has no callee, so it lowers to a synthetic node over a {@link LoopFrame} — the
 *       loop's whole scope as one struct, iterated by a function {@code T -> T}. See that class.
 *   <li><b>Statements.</b> Assignment is threaded symbolically: each variable's current term is tracked, so
 *       {@code i = i + 1} rebinds {@code i} rather than asserting anything, and a later equation mentions the
 *       term rather than the name.
 * </ul>
 *
 * <h2>The honest limit</h2>
 *
 * <p><b>Java declares no refinements, so the cycles carry no inductive hypothesis yet.</b> In Pontif, a recursive
 * call's result variable takes the callee's declared return refinement, and that is what the back-edge carries
 * forward. A Java method declares {@code int}, not {@code [Int:@>=1]}, so the same result variable carries no
 * predicate and the hypothesis is empty. The graph is structurally complete — the cycle is there, the paths are
 * there, the equations are there — and it will support induction as soon as refinements arrive, whether from
 * annotations, from a separate specification, or as an issuer's conjecture. Nothing here invents one.
 */
public final class JavaDrafter {

    private JavaDrafter() {}

    /** Parses at {@link ParserConfiguration.LanguageLevel#RAW}: no validators, so nothing reflects. */
    private static JavaParser parser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.RAW));
    }

    /** Drafts every main source file of every module in the reactor, in reactor order. */
    public static ReceiptGraph draft(MavenProject project) throws IOException {
        return draftFiles(project.javaFiles());
    }

    /** Drafts the given files, in the order given. */
    public static ReceiptGraph draftFiles(List<Path> files) throws IOException {
        JavaParser parser = parser();
        List<Node> nodes = new ArrayList<>();
        for (Path file : files) {
            ParseResult<CompilationUnit> result = parser.parse(file);
            result.getResult().ifPresent(cu -> nodes.addAll(draftUnit(cu)));
        }
        return new ReceiptGraph(nodes);
    }

    /** Drafts source text directly — the entry point tests use. */
    public static ReceiptGraph draftSource(String... sources) {
        JavaParser parser = parser();
        List<Node> nodes = new ArrayList<>();
        for (String source : sources) {
            parser.parse(source).getResult().ifPresent(cu -> nodes.addAll(draftUnit(cu)));
        }
        return new ReceiptGraph(nodes);
    }

    /** Every node a compilation unit contributes: one per method with a body, plus synthesised loop nodes. */
    static List<Node> draftUnit(CompilationUnit cu) {
        List<Node> nodes = new ArrayList<>();
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            for (MethodDeclaration method : type.getMethods()) {
                if (method.getBody().isEmpty()) {
                    continue;   // abstract or native: nothing to transcribe
                }
                nodes.addAll(new MethodPass(type, method).draft());
            }
        }
        return nodes;
    }

    // ------------------------------------------------------------------------------------------

    /** One method's transcription. Holds the counters that keep synthesised names unique within it. */
    private static final class MethodPass {

        private final TypeDeclaration<?> owner;
        private final MethodDeclaration method;
        private final MethodKey key;
        private final Var resultVar;
        private final List<Branch> branches = new ArrayList<>();
        private final List<Node> synthetic = new ArrayList<>();
        private final Map<String, String> declaredTypes = new LinkedHashMap<>();
        private int callCounter = 1;
        private int loopCounter = 0;

        MethodPass(TypeDeclaration<?> owner, MethodDeclaration method) {
            this.owner = owner;
            this.method = method;
            this.key = keyOf(owner, method);
            // T | Throwable, because any Java method may complete abruptly. A throwing path then produces a
            // result in the other arm rather than producing none, and the node is total.
            this.resultVar = new Var("r_0", JavaSorts.resultOf(TypeNames.of(method.getType())));
        }

        List<Node> draft() {
            List<Param> params = new ArrayList<>();
            Map<String, SymExpr> bindings = new LinkedHashMap<>();
            for (Parameter p : method.getParameters()) {
                String typeName = TypeNames.of(p.getType());
                params.add(new Param(p.getNameAsString(), JavaSorts.of(typeName)));
                declaredTypes.put(p.getNameAsString(), typeName);
                bindings.put(p.getNameAsString(), SymExpr.var(p.getNameAsString()));
            }

            walk(method.getBody().orElseThrow().getStatements(), 0, List.of(), bindings, new ArrayList<>());

            if (branches.isEmpty()) {
                // A body this drafter could make nothing of still gets one unconditional branch, so the node is
                // well-formed and says only that the method exists.
                branches.add(Branch.of(Optional.empty(), List.of(), List.of()));
            }

            List<Node> out = new ArrayList<>();
            out.add(Node.identified(key.canonical(), key.methodName(), params, resultVar, branches));
            out.addAll(synthetic);
            return out;
        }

        /**
         * Walks statements from {@code index}, emitting a branch wherever a path leaves the method.
         *
         * @param guards   path conditions accumulated so far
         * @param bindings each in-scope name's current term
         * @param calls    calls hoisted on this path so far
         */
        private void walk(List<Statement> statements, int index,
                          List<SymExpr> guards, Map<String, SymExpr> bindings, List<CallRef> calls) {
            if (index >= statements.size()) {
                return;   // fell off the end: a void method's implicit return carries no equation
            }
            Statement statement = statements.get(index);
            List<Statement> rest = statements;

            switch (statement) {
                case BlockStmt block -> {
                    List<Statement> merged = new ArrayList<>(block.getStatements());
                    merged.addAll(rest.subList(index + 1, rest.size()));
                    walk(merged, 0, guards, bindings, calls);
                }
                case ReturnStmt ret -> emitReturn(ret, guards, bindings, calls);
                case ThrowStmt ignored -> branches.add(new Branch(
                        conjunction(guards), Branch.Completion.THROW, List.of(), List.copyOf(calls)));
                case IfStmt ifStmt -> walkIf(ifStmt, rest, index, guards, bindings, calls);
                case WhileStmt loop -> {
                    Map<String, SymExpr> after = lowerLoop(
                            loop.getCondition(), loop.getBody(), List.of(), bindings, calls);
                    walk(rest, index + 1, guards, after, calls);
                }
                case ForStmt loop -> {
                    // for (init; cond; update) body  is  init; while (cond) { body; update; }
                    Map<String, SymExpr> entry = new LinkedHashMap<>(bindings);
                    for (Expression init : loop.getInitialization()) {
                        applyExpression(init, entry, calls);
                    }
                    Expression condition = loop.getCompare().orElse(new BooleanLiteralExpr(true));
                    Map<String, SymExpr> after =
                            lowerLoop(condition, loop.getBody(), loop.getUpdate(), entry, calls);
                    walk(rest, index + 1, guards, after, calls);
                }
                case ExpressionStmt expressionStmt -> {
                    Map<String, SymExpr> next = new LinkedHashMap<>(bindings);
                    applyExpression(expressionStmt.getExpression(), next, calls);
                    walk(rest, index + 1, guards, next, calls);
                }
                // Statements this slice does not model: try, switch, synchronized, labelled, do-while,
                // for-each, break, continue. A path through one of them simply produces no branch, which is
                // visible as a method whose node has fewer branches than its source has returns.
                default -> walk(rest, index + 1, guards, bindings, calls);
            }
        }

        private void walkIf(IfStmt ifStmt, List<Statement> rest, int index,
                            List<SymExpr> guards, Map<String, SymExpr> bindings, List<CallRef> calls) {
            Optional<SymExpr> condition = Exprs.of(ifStmt.getCondition(), bindings);

            // A guard clause: `if (cond) throw ...` with no else. The throw is its own path, and every path
            // after it holds the negation.
            boolean thenThrows = isThrow(ifStmt.getThenStmt());
            if (thenThrows && ifStmt.getElseStmt().isEmpty()) {
                condition.ifPresentOrElse(
                        c -> branches.add(new Branch(
                                conjunction(append(guards, c)), Branch.Completion.THROW,
                                List.of(), List.copyOf(calls))),
                        () -> branches.add(new Branch(
                                conjunction(guards), Branch.Completion.THROW, List.of(), List.copyOf(calls))));
                List<SymExpr> surviving = condition.flatMap(Exprs::negate)
                        .map(n -> append(guards, n)).orElse(guards);
                walk(rest, index + 1, surviving, bindings, calls);
                return;
            }

            List<SymExpr> thenGuards = condition.map(c -> append(guards, c)).orElse(guards);
            List<Statement> thenPath = new ArrayList<>();
            thenPath.add(ifStmt.getThenStmt());
            thenPath.addAll(rest.subList(index + 1, rest.size()));
            walk(thenPath, 0, thenGuards, new LinkedHashMap<>(bindings), new ArrayList<>(calls));

            List<SymExpr> elseGuards = condition.flatMap(Exprs::negate)
                    .map(n -> append(guards, n)).orElse(guards);
            List<Statement> elsePath = new ArrayList<>();
            ifStmt.getElseStmt().ifPresent(elsePath::add);
            elsePath.addAll(rest.subList(index + 1, rest.size()));
            walk(elsePath, 0, elseGuards, new LinkedHashMap<>(bindings), new ArrayList<>(calls));
        }

        private void emitReturn(ReturnStmt ret, List<SymExpr> guards,
                                Map<String, SymExpr> bindings, List<CallRef> calls) {
            List<CallRef> pathCalls = new ArrayList<>(calls);
            List<InitialReceipt> receipts = new ArrayList<>();
            ret.getExpression().ifPresent(expression -> {
                Expression hoisted = hoistCalls(expression, bindings, pathCalls);
                Exprs.of(hoisted, bindings).ifPresent(term -> receipts.add(new InitialReceipt(
                        SymExpr.cmp(SymExpr.var(resultVar.name()), SymExpr.CmpOp.EQ, term))));
            });
            branches.add(new Branch(
                    conjunction(guards), Branch.Completion.RETURN, receipts, List.copyOf(pathCalls)));
        }

        /** Applies a statement-level expression: a declaration, an assignment, or a bare call. */
        private void applyExpression(Expression expression, Map<String, SymExpr> bindings, List<CallRef> calls) {
            switch (expression) {
                case VariableDeclarationExpr declaration -> {
                    for (var variable : declaration.getVariables()) {
                        String name = variable.getNameAsString();
                        declaredTypes.put(name, TypeNames.of(variable.getType()));
                        Optional<SymExpr> value = variable.getInitializer()
                                .map(init -> hoistCalls(init, bindings, calls))
                                .flatMap(init -> Exprs.of(init, bindings));
                        bindings.put(name, value.orElse(SymExpr.var(name)));
                    }
                }
                case AssignExpr assign -> {
                    if (assign.getTarget() instanceof NameExpr target) {
                        Expression value = assign.getOperator() == AssignExpr.Operator.ASSIGN
                                ? assign.getValue()
                                : compound(assign);
                        Expression hoisted = hoistCalls(value, bindings, calls);
                        Exprs.of(hoisted, bindings).ifPresentOrElse(
                                term -> bindings.put(target.getNameAsString(), term),
                                // An untranscribable assignment must not leave the old term in place, or a later
                                // equation would state something the source stopped saying. Forget it instead.
                                () -> bindings.put(target.getNameAsString(),
                                        SymExpr.var(target.getNameAsString())));
                    }
                }
                case UnaryExpr unary when unary.getExpression() instanceof NameExpr target -> {
                    SymExpr current = Exprs.resolve(target.getNameAsString(), bindings);
                    switch (unary.getOperator()) {
                        case POSTFIX_INCREMENT, PREFIX_INCREMENT ->
                                bindings.put(target.getNameAsString(), SymExpr.add(current, SymExpr.lit(1)));
                        case POSTFIX_DECREMENT, PREFIX_DECREMENT ->
                                bindings.put(target.getNameAsString(), SymExpr.add(current, SymExpr.lit(-1)));
                        default -> { }
                    }
                }
                case MethodCallExpr call -> hoistCalls(call, bindings, calls);
                default -> { }
            }
        }

        /** {@code x += e} as {@code x = x + e}, so one path handles both. */
        private static Expression compound(AssignExpr assign) {
            BinaryExpr.Operator op = switch (assign.getOperator()) {
                case PLUS -> BinaryExpr.Operator.PLUS;
                case MINUS -> BinaryExpr.Operator.MINUS;
                case MULTIPLY -> BinaryExpr.Operator.MULTIPLY;
                case DIVIDE -> BinaryExpr.Operator.DIVIDE;
                default -> null;
            };
            return op == null
                    ? assign.getValue()
                    : new BinaryExpr(assign.getTarget().clone(), assign.getValue().clone(), op);
        }

        // --- loops -------------------------------------------------------------------------------

        /**
         * Lowers a loop to a synthetic node over its scope, and returns the bindings that hold after it.
         *
         * <p>The frame is every variable currently in scope, not the subset this drafter believes the loop
         * touches. That is what makes one shape cover a map, a fold and a mutation-heavy {@code for} alike.
         */
        private Map<String, SymExpr> lowerLoop(Expression condition, Statement body, List<Expression> update,
                                               Map<String, SymExpr> bindings, List<CallRef> outerCalls) {
            Map<String, Sort> fields = new LinkedHashMap<>();
            for (String name : bindings.keySet()) {
                fields.put(name, JavaSorts.of(declaredTypes.getOrDefault(name, "java.lang.Object")));
            }
            if (fields.isEmpty()) {
                return bindings;   // nothing in scope to thread; the loop states nothing this drafter can use
            }

            int index = loopCounter++;
            String loopId = key.canonical() + "$loop$" + index;
            String loopName = key.methodName() + "$loop$" + index;
            LoopFrame frame = new LoopFrame(loopId + "$frame", fields);

            // Inside the node, the frame is a parameter and every variable is a field of it.
            Var frameParam = new Var("s_0", frame.sort());
            Map<String, SymExpr> inside = frame.readsFrom(SymExpr.var(frameParam.name()));
            Var loopResult = new Var("f_" + index, frame.sort());

            Optional<SymExpr> guard = Exprs.of(condition, inside);

            // The iterating arm: run the body, then the update, then call ourselves with the new frame.
            Map<String, SymExpr> iterated = new LinkedHashMap<>(inside);
            List<CallRef> bodyCalls = new ArrayList<>();
            for (Statement statement : body instanceof BlockStmt block ? block.getStatements() : List.of(body)) {
                if (statement instanceof ExpressionStmt expressionStmt) {
                    applyExpression(expressionStmt.getExpression(), iterated, bodyCalls);
                }
                // Control flow inside a loop body — nested ifs, breaks, returns — is not modelled in this slice.
            }
            for (Expression u : update) {
                applyExpression(u, iterated, bodyCalls);
            }

            Var nextFrame = new Var("f_" + index + "_next", frame.sort());
            List<CallRef> iterCalls = new ArrayList<>(bodyCalls);
            iterCalls.add(new CallRef(loopId, List.of(frame.build(iterated, inside)), nextFrame));
            Branch iterating = new Branch(
                    guard, Branch.Completion.RETURN,
                    List.of(new InitialReceipt(SymExpr.cmp(
                            SymExpr.var(loopResult.name()), SymExpr.CmpOp.EQ, SymExpr.var(nextFrame.name())))),
                    iterCalls);

            // The exit arm: the loop is over, so its result is the frame it was handed.
            Branch exiting = new Branch(
                    guard.flatMap(Exprs::negate), Branch.Completion.RETURN,
                    List.of(new InitialReceipt(SymExpr.cmp(
                            SymExpr.var(loopResult.name()), SymExpr.CmpOp.EQ,
                            SymExpr.var(frameParam.name())))),
                    List.of());

            synthetic.add(Node.synthetic(loopId, loopName,
                    List.of(new Param(frameParam.name(), frame.sort())),
                    loopResult, List.of(iterating, exiting)));

            // In the enclosing method: call the loop once with the entry frame, then read the fields back off it.
            Var handle = new Var("f_" + index + "_out", frame.sort());
            outerCalls.add(new CallRef(loopId, List.of(frame.build(bindings, bindings)), handle));
            Map<String, SymExpr> after = new LinkedHashMap<>(bindings);
            after.putAll(frame.readsFrom(SymExpr.var(handle.name())));
            return after;
        }

        // --- calls -------------------------------------------------------------------------------

        /**
         * Replaces every call in {@code expression} with the variable naming its result, innermost first, and
         * appends a {@link CallRef} for each. Returns a rewritten clone; the original is untouched.
         */
        private Expression hoistCalls(Expression expression, Map<String, SymExpr> bindings, List<CallRef> calls) {
            Expression working = expression.clone();
            while (true) {
                Optional<MethodCallExpr> innermost = working.findAll(MethodCallExpr.class).stream()
                        .filter(c -> c.findAll(MethodCallExpr.class).size() == 1)
                        .findFirst();
                if (innermost.isEmpty()) {
                    return working;
                }
                MethodCallExpr call = innermost.get();
                String varName = "r_" + callCounter++;
                List<SymExpr> args = new ArrayList<>();
                for (Expression argument : call.getArguments()) {
                    Exprs.of(argument, bindings).ifPresent(args::add);
                }
                if (args.size() != call.getArguments().size()) {
                    args = List.of();   // partial argument bindings would misdescribe the call; claim none
                }
                Optional<MethodDeclaration> target = resolve(call);
                Sort resultSort = target
                        .map(m -> JavaSorts.of(TypeNames.of(m.getType())))
                        .orElse(Sort.of("_"));
                calls.add(new CallRef(targetId(call, target), args, new Var(varName, resultSort)));

                if (call == working) {
                    return new NameExpr(varName);
                }
                call.replace(new NameExpr(varName));
            }
        }

        /**
         * The method a call names, when the enclosing type declares exactly one candidate.
         *
         * <p>No resolver yet, so this only sees the type being drafted: an unqualified call to a method declared
         * here, with a matching arity. Anything else is left unresolved rather than guessed at, and an unresolved
         * call is a legitimate opaque value in the graph.
         */
        private Optional<MethodDeclaration> resolve(MethodCallExpr call) {
            if (call.getScope().isPresent()) {
                return Optional.empty();
            }
            List<MethodDeclaration> candidates = owner.getMethodsByName(call.getNameAsString()).stream()
                    .filter(m -> m.getParameters().size() == call.getArguments().size())
                    .toList();
            return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
        }

        private String targetId(MethodCallExpr call, Optional<MethodDeclaration> target) {
            return target
                    .map(m -> keyOf(owner, m).canonical())
                    // Honest marker rather than a plausible-looking key: nothing in the graph will match it, so
                    // the call stays an opaque value instead of becoming a wrong edge.
                    .orElseGet(() -> "?" + call.getNameAsString() + "/" + call.getArguments().size());
        }

        private static MethodKey keyOf(TypeDeclaration<?> owner, MethodDeclaration method) {
            List<String> paramTypes = method.getParameters().stream()
                    .map(p -> TypeNames.of(p.getType()))
                    .toList();
            return new MethodKey(TypeNames.declaringType(owner), method.getNameAsString(), paramTypes);
        }

        // --- small helpers ------------------------------------------------------------------------

        private static boolean isThrow(Statement statement) {
            return statement instanceof ThrowStmt
                    || (statement instanceof BlockStmt block
                        && block.getStatements().size() == 1
                        && block.getStatement(0) instanceof ThrowStmt);
        }

        private static List<SymExpr> append(List<SymExpr> guards, SymExpr guard) {
            List<SymExpr> out = new ArrayList<>(guards);
            out.add(guard);
            return out;
        }

        private static Optional<SymExpr> conjunction(List<SymExpr> guards) {
            if (guards.isEmpty()) {
                return Optional.empty();
            }
            SymExpr all = guards.get(0);
            for (int i = 1; i < guards.size(); i++) {
                all = SymExpr.and(all, guards.get(i));
            }
            return Optional.of(all);
        }
    }
}
