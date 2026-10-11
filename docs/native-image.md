# The native CLI: what crosses the boundary

`mvn -Pnative -DskipTests package -pl concordance-cli -am` builds `concordance-cli/target/concordance.exe` in about
35 s, with no reachability metadata. This records what was checked in the native binary on 2026-10-11, after the
index gained sources jars, dependency resolution, staged search, class-file reading and Vineflower, and the one
thing that needed a fix.

## Every path, run natively

| Path | Native result |
|---|---|
| Pom reading and dependency resolution (JAXP) | Identical to the JVM: vexelray-gui resolves 28 libraries in 23 ms |
| Sources jars, through the 256 MB parse thread | Identical. Indexing vexelray-gui with libraries takes 3.2 s natively, 6.0 s on the JVM |
| Class jars, through `java.lang.classfile` | Identical: 22 jars, 431 types |
| Staged search on virtual threads | Identical stages and hits; 8 ms over 1,178 sources |
| Deep nesting | 200,000 nested parentheses parse (native frames are smaller than the JVM's). At 2,000,000 the file is reported, `nested too deeply to parse within a 256 MB stack`, and the next file is indexed: the thread's stack size is honoured and `StackOverflowError` is contained, as on the JVM |
| Vineflower | Works, once its view of the JDK is fixed (below). Its plugins are not found through `ServiceLoader`, so nothing needs registering |

## The JDK, as a decompiler sees it

Vineflower reads the JDK's own classes to infer generic types. With them it writes `new PriorityQueue<>()`;
without them, the raw `new PriorityQueue()`. On one 900-line class the difference was three lines and a few dozen
raw types; on JOML's `Vector3f`, nothing. By default it opens the running JDK through the `jrt:` file system, and in
a native image that first failed with `ProviderNotFoundException: Provider "jrt" not found`.

`-H:+AllowJRTFileSystem` adds `jrt:` support, and its description says it "requires java.home to be set at run
time". **That is not what it does.** The build records the absolute path of the building JDK's `lib/modules` in the
binary, and `jrt:` opens that path whatever `java.home` or `JAVA_HOME` say. On the build machine everything
appears to work, so this is easy to miss. To see it, a binary was built with a scratch copy of the JDK, and the
copy then renamed:

| Build JDK's path | `java.home` / `JAVA_HOME` | `jrt:` |
|---|---|---|
| present | anything, even `-Djava.home=C:\nowhere` | opens, from the build path |
| gone | unset | `InternalError: NoSuchFileException: <build path>\lib\modules` |
| gone | `JAVA_HOME` names a JDK | same `InternalError` |
| gone | `-Djava.home` names a JDK | same `InternalError` |

Note that the failure is an `InternalError`, not an exception: a check that catches `RuntimeException` would let it
crash class initialization rather than fall back.

So `Decompilation` finds the JDK three ways, in order, and says which (`show` prints it):

1. **`jrt:`**, verified by reading `java/lang/Object.class`. That's always the case on the JVM, and on the machine
   that built the native binary.
2. **A JDK's `jmods`**, found by `java.home` or `JAVA_HOME`. A `.jmod` is a zip with the classes under `classes/`,
   which `java.util.zip.ZipFile` opens as it is. They are handed to Vineflower as a lazy library, so a JDK's tens
   of thousands of classes cost nothing until one is asked for, and each is read within the size limit.
3. **No JDK.** The class is still decompiled, with less generic inference, and labelled as such.

Checked natively with the build JDK's path gone: with `JAVA_HOME` naming a JDK, `show` reports `decompiled with
the JDK at … (jmods)` and writes `new PriorityQueue<>()`; with no JDK, it reports `decompiled with no JDK, so
generic types are inferred less well` and writes `new PriorityQueue()`.

Not every JDK ships `jmods`. JDKs linked without them (JEP 493) and JREs do not, and on such machines the
native CLI takes the third way. The editor runs on the JVM and always takes the first.
