# Choosing the Java front end

Status: **decided**, ahead of any code. Measured 2026-08-26 against JavaParser 3.28.2 (released 2026-05-31, the
latest on Maven Central) on Oracle GraalVM 25.0.3+9.1 and Maven 3.9.16.

The decision, in one line: **take `javaparser-core` for parsing and rewriting; do not build the index's
resolution layer on `javaparser-symbol-solver-core`.** Not because the solver is inaccurate — it resolves 99.5%
of a real 9.4k-line corpus — but because it cannot cross the GraalVM native-image boundary, and resolution must
not behave differently in the editor than it does in the CLI.

## Why the question was worth measuring rather than assuming

Two claims about JavaParser are widely repeated and both are now false: that it trails the current JDK by a
release or two, and that its symbol solver is too weak to lean on. The first is stale; the second is roughly the
opposite of true. What is actually disqualifying is a third thing nobody mentions, because almost nobody runs
JavaParser under native-image.

So everything below is measured, not recalled. The probes live in the scratchpad listed at the end.

## Parsing covers Java 25 completely

All twelve constructs probed parse at `LanguageLevel.JAVA_25`, and all twelve round-trip **byte-exact** through
`LexicalPreservingPrinter` — which is the property refactoring depends on, more than parsing itself.

The three headline Java 25 language JEPs are not merely accepted, they are modelled:

| Feature | How it lands in the AST |
| --- | --- |
| JEP 511 module import declarations | `ImportDeclaration.isModule()` is `true`, cleanly distinct from `import java.base;` |
| JEP 512 compact source files + instance `main` | Members wrapped in a synthetic `ClassOrInterfaceDeclaration` named `$COMPACT_CLASS` |
| JEP 513 flexible constructor bodies | Prologue preserved — `super(y)` stays at `stmt[2]`, behind the `if` and the `int y`, neither hoisted nor dropped |

Also passing: JEP 467 markdown doc comments (a dedicated `MarkdownComment`, `isJavadoc()` true), record patterns,
the unnamed pattern `_`, `case null, default`, `yield` inside a switch expression, multi-`exports` `module-info`,
and a text block ending in an escaped backslash. `LanguageLevel.CURRENT` is already `JAVA_26`, so 25 is not the
frontier.

Zero parse failures across 74 real Java 25 files drawn from `vexelray-gui` and `text-editor-vexel-demo`.

Two things to know before trusting the level setting:

- **Level gating is uneven, so JavaParser is not a version-conformance checker.** At `JAVA_21` it correctly
  rejects module imports and flexible constructor bodies, with genuinely helpful diagnostics — but silently
  *accepts* compact source files, markdown comments, and primitive-type patterns. If "this file uses a Java 25
  feature" is to be an index fact, derive it from AST node types. Never from parse failure at a lower level.
- **There is no `JAVA_25_PREVIEW`.** Preview constants stop at `JAVA_17_PREVIEW`. JEP 507 primitive types in
  patterns — preview in 25 — parses at plain `JAVA_25`, so preview and final syntax cannot be told apart through
  configuration.

## Resolution on the JVM is far better than its reputation

Configured correctly, on real code with real dependency jars:

| Corpus | Attempts | Solved | Solver defects |
| --- | --- | --- | --- |
| `vexelray-gui-core` — 65 files, 9.4k lines, 6 sibling source roots, 30 jars | 12,699 | **99.5%** | 4 (0.03%) |
| `text-editor-vexel-demo` — 9 files, 38 jars | 5,939 | **97.5%** | 10 (0.17%) |

Method calls alone — what "find all callers" rests on — resolved at **99.9%** and **99.6%**. Every one of the 68
dependency jars across both classpaths was readable by `JarTypeSolver`.

Resolution reaches *through* Java 25 constructs, not just past them. `import module java.base;` followed by
`List.of("a").size()` resolves to `java.util.List.size()`. Sibling calls inside a compact source file resolve.
So do calls in a flexible-constructor prologue, record-pattern bindings, and members carrying markdown javadoc.
The one wart: a compact file's synthetic type surfaces in qualified signatures as `$COMPACT_CLASS.twice(int)`,
which has to be normalised before it becomes an index key.

Throughput is ~5,000–6,100 resolutions/sec single-threaded (12,699 in 2.1s). A 100k-line project extrapolates to
roughly 20–30s for a cold full resolve: acceptable for a background index build, nowhere near per-keystroke.

### Three ways to measure this wrong

A first pass scored 88.2% before any of these were fixed. All three are harness faults, not solver faults, and
anyone re-running this will hit them:

1. **`JavaParserTypeSolver` must be handed the resolver-bearing `ParserConfiguration`.** Otherwise it re-parses
   dependency *sources* with its own default config, and every type reached through source rather than through a
   jar arrives with no resolver attached — surfacing as `IllegalStateException: Symbol resolution not
   configured`. The wiring is circular and has to be deferred: build the empty `CombinedTypeSolver`, build the
   config naming a `JavaSymbolSolver` over it, *then* add `new JavaParserTypeSolver(root, cfg)`.
2. **Package qualifiers are not types.** `java.util.function.Consumer` nests as
   `ClassOrInterfaceType(Consumer, scope=ClassOrInterfaceType(function, …))`. Counting those inner scope nodes as
   unresolvable types invents failures. Skip any `ClassOrInterfaceType` that is the scope of another.
3. **A name in value position is often a type name.** JavaParser models `Signature.Effect` as a
   `FieldAccessExpr` over `NameExpr(Signature)`; resolving that as a *value* correctly fails. Fall back to
   `JavaParserFactory.getContext(node, solver).solveType(…)`, trying the qualified text before the simple name,
   which is what any real indexer would do anyway.

### The generic-inference gap is one bug, not a class of them

15 of 17 targeted generic/lambda cases resolve. Streams (`map`, `filter`, `flatMap`, `sorted`, `collect`,
`groupingBy`), `Optional` chains, `merge`, `forEach`, `removeIf`, `var` off a generic call, and our own generic
methods taking a `Function` — all fine.

Both failures are the same shape: **chaining a call onto the result of `computeIfAbsent(k, lambda)`.**

```java
m.computeIfAbsent("k", k -> new ArrayList<>()).add(1);   // .add(1) does not resolve
m.computeIfAbsent("k", k -> new ArrayList<>());          // the call itself resolves fine
```

That is also exactly what the real corpora missed — `InputDispatcher.java:235` and `GuiApp.java:287`, both
`computeIfAbsent` chains. A recognisable, narrow, workaroundable defect.

## Native image is where the symbol solver dies

The same binary, the same corpus, the same code: **JVM 8/9, native image 4/9.** Three distinct failures, only
one of which is fixable with configuration.

| Component | Under native-image |
| --- | --- |
| `javaparser-core` (parsing) | **Works**, and needs no configuration at all once the validators are off — see below |
| `JavaParserTypeSolver` | Works, and is useless alone: every signature mentions a JDK type, so source-only resolution fails on `String` |
| `JarTypeSolver` | **Broken outright.** javassist cannot read a jar inside a native image — `ClassPool.appendClassPath(jar); pool.get(fqn)` throws `NotFoundException`, while byte-identical code succeeds on the JVM |
| `ReflectionTypeSolver` | **Fundamentally closed-world.** It enumerates `ModuleLayer.boot().modules()` at *runtime*, so only pre-registered types resolve. `String`, `List`, `Stream` passed; `TreeMap.firstKey()`, `LocalDate.plusDays()`, `Pattern.matcher()` all failed — those three were deliberately absent from the agent trace |

No reachability configuration fixes this at an acceptable price. `ReflectionTypeSolver` would need the JDK's
entire API surface registered up front; `JarTypeSolver` needs javassist replaced outright. And note the shape of
the failure: it is *silent*. Both degrade into ordinary `UnsolvedSymbolException`, indistinguishable from "this
symbol genuinely does not exist" — an indexer built on them would quietly return incomplete answers in the native
binary and complete ones on the JVM.

**No javaparser jar ships GraalVM reachability metadata** — neither core, nor symbol-solver, nor javassist.
Successful builds took 17–26s for a 19.8 MB binary.

### Why the parser reflects at all, and how to stop it

Out of the box, parsing in a native image dies at `NoSuchFieldError: variables` before reaching any of the above.
It is worth knowing exactly where that comes from, because the obvious reading — "JavaParser reflects over its
AST, so it needs the whole `ast` package registered" — is wrong, and the real answer is cheaper.

The stack is unambiguous:

```
NoSuchFieldError: variables
  at com.github.javaparser.metamodel.PropertyMetaModel.getValue(PropertyMetaModel.java:263)
  at ...validator.language_level_validations.chunks.CommonValidators.lambda$new$7(CommonValidators.java:78)
  at ...validator.TreeVisitorValidator.accept
  at ...ParserConfiguration$2.postProcess(ParserConfiguration.java:368)
  at com.github.javaparser.JavaParser.parse(JavaParser.java:128)
```

It is a **post-parse validator**, not the parser and not the symbol solver. JavaParser ships a generated
*metamodel*: a data description of every AST node type and its properties. One `CommonValidators` rule is written
generically against that description — "for every property the metamodel flags as non-empty, check the value is
not empty" — and to get from a property *name* to the actual field it calls:

```java
for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass())
    for (Field f : c.getDeclaredFields())
        if (f.getName().equals(getName())) { f.setAccessible(true); return f.get(node); }
throw new NoSuchFieldError(getName());
```

So the reflection is a string-keyed bridge from generated metadata back to real fields. `variables` surfaces first
because `VariableDeclarationExpr.variables` is flagged non-empty and most files declare a variable early. This is
avoidable design rather than a necessity — JavaParser already generates visitors, clone support and the metamodel
classes, so it could equally have generated a direct accessor per property. It costs a class-hierarchy walk and a
`getDeclaredFields()` array per checked property on every node of every parse, on the JVM too.

Measured, native image, **zero** reachability metadata:

| Parser configuration | Result | Comments attached? |
| --- | --- | --- |
| `JAVA_25`, default processors | `NoSuchFieldError: variables` | — |
| `JAVA_25` + `getProcessors().clear()` | parses, 43 nodes | **no — all comment attribution lost** |
| `LanguageLevel.RAW` | parses, 44 nodes | yes, javadoc and markdown comments attached |

**`RAW` is the setting we want.** It skips the validators, so nothing reflects and no metadata is needed; it keeps
comment attribution, which clearing the processor list destroys; and it still parses the newest grammar, since
JavaParser's grammar is level-independent and the language level only drives validation. Lexical preservation is
unaffected in all three configurations — round-trip stayed byte-exact and a rename-then-print kept every comment.

What `RAW` gives up is JavaParser's language-level checking, which this document already argues is not worth
having: it is uneven (§ *Parsing covers Java 25 completely*), and feature usage should be derived from AST node
types rather than from parse failures.

## Smart App Control blocks native-image builds

**Resolved** on this machine — Smart App Control was turned off, and five consecutive builds then succeeded in
25–29s each, where the same directory had been running at roughly one success in four. The diagnosis stays on
the record because the symptom is deeply misleading, it will recur on any Windows machine with Smart App Control
enabled, and the two obvious fixes both fail.

This is an environment finding rather than a JavaParser one, but it affects every native binary this project will
ever produce.

Native-image builds failed intermittently — 3 of 3, then 1 of 2, then 0 of 3 in a session — always presenting
as:

```
VMError$HostedError: Unable to run '…\svm-tmp\SVM-…\LocaleDirectives.exe' to compute offsets in C data
structures. Is it possible that your antivirus software interferes and puts the resulting file into quarantine?
```

**That message is a guess, and it is wrong.** Two `Caused by` levels down in the generated
`target/svm_err_b_*.md` report sits the real error:

```
java.io.IOException: CreateProcess error=4551, An Application Control policy has blocked this file
```

Windows confirms it. `HKLM\SYSTEM\CurrentControlSet\Control\CI\Policy` has
`VerifiedAndReputablePolicyState = 1` — **Smart App Control, enforced** — and
`Microsoft-Windows-CodeIntegrity/Operational` logs a matching block for each failure:

```
Event 3118  Smart App Control Block
Event 3077  Code Integrity determined that a process (…\graalvm-jdk-25.0.3+9.1\bin\java.exe) attempted to load
            …\svm-tmp\SVM-…\LocaleDirectives.exe that did not meet the Enterprise signing level requirements
            or violated code integrity policy
```

The mechanism: to compute C struct offsets, native-image generates small C programs, compiles them with `cl.exe`,
and immediately executes them. They are freshly built and unsigned, so Smart App Control judges them on cloud
reputation — which no brand-new binary has. Whether a given one is allowed is effectively a coin flip, and it is a
*different* helper each time (`LocaleDirectives`, `JNIHeaderDirectives`, `WindowsDirectives`,
`RISCV64LibCHelperDirectives`).

Three things follow that are easy to get wrong:

- **Defender exclusions do not help.** Smart App Control is a separate enforcement path from antivirus real-time
  protection; excluding the project directory has no effect on it. Confirmed here — builds inside the excluded
  tree still failed 3/3 with the same Code Integrity block.
- **`-H:TempDirectory` does not help either**, for the same reason. It relocates the helpers; it does not sign
  them. Observed failing under the system temp and under `target/svm-tmp` alike. It is harmless to keep for
  tidiness, but it is not a fix.
- **The blocked files are not damaged.** Every helper `.exe` survives the failed build and runs fine when invoked
  by hand, exit 0. Anything that looks like quarantine or corruption is a misreading.

What works, in rough order of cost:

1. **Retry, and expect to retry a lot.** Failure is instant and loud — 2–14s, before any real compilation — so
   attempts are cheap. Across roughly 35 builds under enforcement the success rate ran about **1 in 4** under
   `Documents\GitHub` and about **1 in 2** under `AppData\Local\Temp`; too small a sample to call location
   causal, but a loop of ~10 attempts reliably landed a build.

   ```bash
   for i in $(seq 1 10); do mvn -B -Pnative -DskipTests package && break; done
   ```

2. **Build release binaries in CI.** A GitHub Actions Windows runner has no Smart App Control, so it is
   deterministic there. Worth doing regardless, once we ship anything.
3. **Turn Smart App Control off** — what was done here, and it is a complete fix: 5 of 5 builds succeeded
   afterwards where the same directory had been at roughly 1 in 4. Settings → Privacy & security → Windows
   Security → App & browser control → Smart App Control. Note for anyone repeating it: **this is one-way.** Once
   disabled, Windows cannot re-enable it without a clean OS install, so it is a machine-policy decision rather
   than a build setting.

One lead that did **not** pan out, recorded so it is not re-attempted blind. GraalVM can cache the C offsets so
the helpers never run — `-H:+NewCAPCache -H:CAPCacheDir=<dir> -H:+ExitAfterCAPCache` to record, `-H:CAPCacheDir`
to reuse, passed through the `NATIVE_IMAGE_OPTIONS` environment variable since the Maven plugin has no property
for it. In practice one cache-creation run captured only 4 of the ~6 directive sets
(`AMD64LibCHelperDirectives`, `AArch64LibCHelperDirectives` and `JNIHeaderDirectives` were missing), builds still
spawned the uncached ones, and the success rate over 5 trials was 2/5 — indistinguishable from no cache. Making
this work would mean getting a complete cache written, and `-H:+NewCAPCache` erases the existing cache on each
attempt, so it cannot simply be accumulated across lucky runs. Possibly worth another look if native builds ever
move into a tight loop.

## What we build, and what we borrow

- **Borrow the front end.** `javaparser-core` parses Java 25 completely, preserves every byte for rewriting, and
  survives native-image. There is no reason to hand-write a Java parser.
- **Parse at `LanguageLevel.RAW`, on both surfaces.** It is the one configuration that needs no reachability
  metadata, keeps comments attached, and still accepts the newest grammar. We do our own validation anyway, so
  JavaParser's validators are pure cost — and they are the only thing in the parse path that reflects.
- **Build the resolver.** Our resolution layer reads bytes and never reflects: dependency jars through our own
  class-file reader (constant pool plus the method and field tables), and the JDK through the `jrt:` filesystem
  over `$JAVA_HOME/lib/modules`. Both are pure file I/O, which is precisely where JavaParser's two solvers fail
  and where a byte reader does not care what runtime it is on.
- **Keep the symbol solver as a test-only oracle.** On the JVM, in tests, it is a 99.5%-accurate reference to
  differential-test our resolver against on real corpora like `vexelray-gui`. That is real value at zero runtime
  cost, and it is the reason to keep the dependency in the reactor at `test` scope rather than dropping it.
- **Do not let the two surfaces diverge.** The editor runs in-process on a JVM, where the symbol solver would
  work. Using it there and our own resolver in the native CLI would mean two answers to the same query. One
  resolver, ours, everywhere.

## Reproducing

Every number above comes from a probe kept in [`probes/`](probes). They are throwaway harnesses, not project
code — no module of ours depends on them, and they can be deleted once this decision stops being questioned.

| File | What it establishes |
| --- | --- |
| [`probes/jp/Probe.java`](probes/jp/Probe.java) | 12 Java 25 constructs parse; `LexicalPreservingPrinter` round-trips byte-exact |
| [`probes/jp/Shape.java`](probes/jp/Shape.java) | AST shape of the Java 25 features, and that level gating is uneven |
| [`probes/jp/Mod.java`](probes/jp/Mod.java) | `isModule()` distinguishes module imports from ordinary ones |
| [`probes/solver/Eval2.java`](probes/solver/Eval2.java) | Corpus-wide resolution rates and the defect/unresolved split |
| [`probes/solver/Java25Res.java`](probes/solver/Java25Res.java) | Resolution *through* Java 25 constructs |
| [`probes/solver/Generics.java`](probes/solver/Generics.java) | The generic-inference gap, isolated to one pattern |
| [`probes/solver/Jars.java`](probes/solver/Jars.java) | `JarTypeSolver` readability across a real classpath |
| [`probes/solver/ProcessorCost.java`](probes/solver/ProcessorCost.java) | What clearing the processor list costs — comment attribution — and that `RAW` keeps it |
| [`probes/native/`](probes/native) | JVM-vs-native comparison; `NativeProbe` for the four solvers, `JarDiag` for the javassist failure, `NoReflect` for the validator reflection and the `RAW` escape |

The two `pom.xml` files exist only to pull the dependencies down and, for `native/`, to run
`native-maven-plugin`. Build a classpath once:

```bash
cd docs/probes/solver && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt
```

then compile and run any of the single-file probes against it:

```bash
javac -cp "$(cat cp.txt)" -d . Generics.java && java -Xss128m -cp "$(cat cp.txt);." Generics
```

`Eval2` takes a label, a source root, a `;`-separated classpath file, and any number of extra source roots:

```bash
java -Xss128m -cp "$(cat cp.txt);." Eval2 "vexelray-gui-core" ../../../../vexelray-gui/vexelray-gui-core/src/main/java cp-gui.txt
```

Build each module's classpath with `mvn dependency:build-classpath` and join the per-module files **with a
separator** — concatenating them directly glues the last path of one file onto the first of the next, which is
what produced a phantom "5 unreadable jars" on the first run.

The native comparison needs its own classpath plus the path of one jar to resolve *into*:

```bash
cd docs/probes/native && mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt && tr ';' '\n' < cp.txt | grep javaparser-core > jpjar.txt
```

Then the point is that these two disagree:

```bash
mvn -q package -DskipTests && java -cp "target/classes;$(cat cp.txt)" probe.NativeProbe corpus "$(cat jpjar.txt)"
```

```bash
mvn -Pnative -DskipTests package && ./target/solver-probe.exe corpus "$(cat jpjar.txt)"
```

`JarDiag` isolates the javassist failure to its layer — jar read, then solver index. It needs both properties, or
it overwrites the binary above:

```bash
mvn -Pnative -DskipTests package -Dapp.mainClass=probe.JarDiag -Dimage.name=jar-diag && ./target/jar-diag.exe "$(cat jpjar.txt)"
```

Two files in `native/` are load-bearing to the method and easy to mistake for incidental:

- **`corpus/UsesUnseenJdk.java`** was written *after* the tracing agent ran, so nothing it touches is
  pre-registered. That is what turns "the closed world is a theoretical concern" into three concrete failures.
- **`src/main/resources/META-INF/native-image/probe/native-solver-probe/reachability-metadata.json`** is the
  agent's output, and `NativeProbe` scores 4/9 only because it is present. Remove it and every solver fails
  identically at construction with `NoSuchFieldError: variables`, for 0/9. Keep the two native failures distinct:
  that one is a missing-config problem, fixable outright by parsing at `RAW` (`NoReflect` demonstrates it), while
  the javassist and closed-world failures are not fixable at any acceptable price.

  **Use `mvn clean` when testing this.** A plain `mvn package` leaves the previous run's copy of the metadata in
  `target/classes`, so removing the source file appears to change nothing — a stale-resource trap that produced a
  false "the metadata is not needed" reading here before clean builds settled it.
