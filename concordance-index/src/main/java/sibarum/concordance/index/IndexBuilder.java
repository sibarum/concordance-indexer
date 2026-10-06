package sibarum.concordance.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import sibarum.concordance.project.MavenProject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds an {@link Index} by parsing every source file of a project.
 *
 * <p>Parses at {@link ParserConfiguration.LanguageLevel#RAW}: no validators run, so nothing reflects and the
 * newest syntax still parses. See {@code docs/javaparser-evaluation.md}.
 *
 * <p>Names are syntactic. A type written {@code List<String>} is recorded as a use of {@code List}, and a call
 * {@code close()} as a use of {@code close}, without asking what either resolves to. That makes the index an
 * over-approximation of usage and an exact record of declaration.
 */
public final class IndexBuilder {

    /**
     * What this builder extracts, as a version. Bump it whenever {@link #collect} would record anything
     * differently — a new reference kind, a changed qualified-name scheme, a JavaParser upgrade that parses
     * something differently — because an index saved by an older builder is then not one this builder would
     * produce, and {@link ProjectIndex#load} must refuse it rather than mix the two.
     */
    public static final int VERSION = 1;

    private IndexBuilder() {}

    static JavaParser parser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.RAW));
    }

    /** Indexes every main source file of every module, in reactor order. */
    public static Index build(MavenProject project) throws IOException {
        return buildFiles(project.javaFiles());
    }

    public static Index buildFiles(List<Path> files) throws IOException {
        JavaParser parser = parser();
        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        for (Path file : files) {
            parser.parse(file).getResult().ifPresent(cu -> collect(cu, file, symbols, references));
        }
        return new Index(symbols, references);
    }

    /** Indexes source text directly — what tests and the editor's unsaved buffers use. */
    public static Index buildSource(Path attributedTo, String source) {
        List<Symbol> symbols = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        parser().parse(source).getResult()
                .ifPresent(cu -> collect(cu, attributedTo, symbols, references));
        return new Index(symbols, references);
    }

    /**
     * Indexes one file's bytes, decoded as {@link #buildFiles} decodes the file itself — so a file indexed from
     * the bytes already read to hash it is indistinguishable from one indexed from disk.
     */
    static void collectBytes(JavaParser parser, Path file, byte[] source,
                             List<Symbol> symbols, List<Reference> references) {
        parser.parse(new ByteArrayInputStream(source)).getResult()
                .ifPresent(cu -> collect(cu, file, symbols, references));
    }

    // ------------------------------------------------------------------------------------------

    private static void collect(CompilationUnit cu, Path file,
                                List<Symbol> symbols, List<Reference> references) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String qualified = qualifiedTypeName(type, pkg);
            List<String> supers = supersOf(type);
            symbols.add(new Symbol(kindOf(type), type.getNameAsString(),
                    enclosing(type, pkg), qualified, at(file, type), supers));

            // extends / implements are references to the named types, and are how implementations are found.
            if (type instanceof ClassOrInterfaceDeclaration decl) {
                for (ClassOrInterfaceType parent : decl.getExtendedTypes()) {
                    references.add(new Reference(Reference.Kind.EXTENDS, parent.getNameAsString(),
                            parent.getNameAsString(), qualified, at(file, parent)));
                }
                for (ClassOrInterfaceType parent : decl.getImplementedTypes()) {
                    references.add(new Reference(Reference.Kind.IMPLEMENTS, parent.getNameAsString(),
                            parent.getNameAsString(), qualified, at(file, parent)));
                }
            }

            for (MethodDeclaration method : type.getMethods()) {
                symbols.add(new Symbol(Symbol.Kind.METHOD, method.getNameAsString(), qualified,
                        qualified + "#" + method.getNameAsString() + params(method.getParameters()),
                        at(file, method), List.of()));
            }
            for (ConstructorDeclaration ctor : type.getConstructors()) {
                symbols.add(new Symbol(Symbol.Kind.CONSTRUCTOR, ctor.getNameAsString(), qualified,
                        qualified + "#<init>" + params(ctor.getParameters()),
                        at(file, ctor), List.of()));
            }
            for (FieldDeclaration field : type.getFields()) {
                for (VariableDeclarator variable : field.getVariables()) {
                    symbols.add(new Symbol(Symbol.Kind.FIELD, variable.getNameAsString(), qualified,
                            qualified + "#" + variable.getNameAsString(),
                            at(file, variable), List.of()));
                }
            }
            // A record's components are fields as far as an index is concerned: they are named, they are
            // declared here, and someone looking for the name expects to land on them.
            if (type instanceof RecordDeclaration record) {
                for (Parameter component : record.getParameters()) {
                    symbols.add(new Symbol(Symbol.Kind.FIELD, component.getNameAsString(), qualified,
                            qualified + "#" + component.getNameAsString(),
                            at(file, component), List.of()));
                }
            }
        }

        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            references.add(new Reference(Reference.Kind.CALL,
                    Reference.callTarget(call.getNameAsString(), call.getArguments().size()),
                    call.getNameAsString(), enclosingDeclaration(call, pkg), at(file, call)));
        }
        for (ObjectCreationExpr created : cu.findAll(ObjectCreationExpr.class)) {
            String name = created.getType().getNameAsString();
            references.add(new Reference(Reference.Kind.CONSTRUCT,
                    Reference.callTarget(name, created.getArguments().size()),
                    name, enclosingDeclaration(created, pkg), at(file, created)));
        }
        for (ClassOrInterfaceType used : cu.findAll(ClassOrInterfaceType.class)) {
            // The scope half of a qualified name is a package, not a type; skip it as the drafter does.
            boolean isScopeOfAnother = used.getParentNode()
                    .filter(p -> p instanceof ClassOrInterfaceType c
                            && c.getScope().map(s -> s == used).orElse(false))
                    .isPresent();
            if (isScopeOfAnother) continue;
            // `new Circle(2)` is one use at one place. Its type node would otherwise be recorded a second time,
            // and the reader would be shown the same line twice with nothing to distinguish the entries.
            boolean alreadyRecorded = used.getParentNode()
                    .filter(p -> p instanceof ObjectCreationExpr
                            || p instanceof ClassOrInterfaceDeclaration)
                    .isPresent();
            if (alreadyRecorded) continue;
            references.add(new Reference(Reference.Kind.TYPE_USE, used.getNameAsString(),
                    used.getNameAsString(), enclosingDeclaration(used, pkg), at(file, used)));
        }
        for (FieldAccessExpr access : cu.findAll(FieldAccessExpr.class)) {
            references.add(new Reference(Reference.Kind.READ, access.getNameAsString(),
                    access.getNameAsString(), enclosingDeclaration(access, pkg), at(file, access)));
        }
    }

    // --- naming ---------------------------------------------------------------------------------

    private static Symbol.Kind kindOf(TypeDeclaration<?> type) {
        if (type instanceof RecordDeclaration) return Symbol.Kind.RECORD;
        if (type instanceof EnumDeclaration) return Symbol.Kind.ENUM;
        if (type instanceof AnnotationDeclaration) return Symbol.Kind.ANNOTATION;
        if (type instanceof ClassOrInterfaceDeclaration decl && decl.isInterface()) return Symbol.Kind.INTERFACE;
        return Symbol.Kind.CLASS;
    }

    private static List<String> supersOf(TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration decl)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        decl.getExtendedTypes().forEach(t -> out.add(t.getNameAsString()));
        decl.getImplementedTypes().forEach(t -> out.add(t.getNameAsString()));
        return out;
    }

    /** {@code a.b.Outer.Inner} — package plus every enclosing type. */
    private static String qualifiedTypeName(TypeDeclaration<?> type, String pkg) {
        List<String> parts = new ArrayList<>();
        parts.add(type.getNameAsString());
        Optional<Node> parent = type.getParentNode();
        while (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> outer) {
            parts.add(0, outer.getNameAsString());
            parent = outer.getParentNode();
        }
        String nested = String.join(".", parts);
        return pkg.isEmpty() ? nested : pkg + "." + nested;
    }

    private static String enclosing(TypeDeclaration<?> type, String pkg) {
        Optional<Node> parent = type.getParentNode();
        if (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> outer) {
            return qualifiedTypeName(outer, pkg);
        }
        return pkg;
    }

    /** The qualified name of the declaration a use sits inside, for "who refers to this". */
    private static String enclosingDeclaration(Node node, String pkg) {
        Node current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration method) {
                Optional<Node> owner = method.getParentNode();
                String type = owner.isPresent() && owner.get() instanceof TypeDeclaration<?> t
                        ? qualifiedTypeName(t, pkg) : pkg;
                return type + "#" + method.getNameAsString() + params(method.getParameters());
            }
            if (current instanceof TypeDeclaration<?> type) {
                return qualifiedTypeName(type, pkg);
            }
            current = current.getParentNode().orElse(null);
        }
        return pkg;
    }

    private static String params(List<Parameter> parameters) {
        List<String> names = parameters.stream().map(p -> p.getType().asString()).toList();
        return "(" + String.join(",", names) + ")";
    }

    private static SourceRef at(Path file, Node node) {
        return node.getBegin()
                .map(p -> new SourceRef(file, p.line, p.column))
                .orElse(new SourceRef(file, 1, 1));
    }

    /** The name a declaration node carries, when it has one. */
    static String nameOf(Node node) {
        return node instanceof NodeWithSimpleName<?> named ? named.getNameAsString() : "";
    }
}
