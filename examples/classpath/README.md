# Trace with a binary dependency

Run these commands from the repository root with a full JDK after packaging the tracer:

```sh
mkdir -p target/classpath-example/classes target/classpath-example/lib/nested
javac --release 21 -proc:none -d target/classpath-example/classes \
  examples/classpath/library/example/library/Answer.java
jar --create --file target/classpath-example/lib/nested/answer.jar \
  -C target/classpath-example/classes .
java -jar target/code-tracer.jar trace \
  --input examples/classpath/Main.java --format modern --all-breakpoints \
  --class-path 'target/classpath-example/lib/**/*.jar' --copy-class-path
```

The resulting trace contains stdout `42\n` and source-line stops in `Main.java`.
`Answer` is available to compilation and execution but its precompiled method
does not receive source-line breakpoints. The recursive pattern includes JARs
directly in `lib` as well as deeper directories; matched paths are sorted in place.

For a binary directory instead, replace the class-path argument with:

```sh
--class-path target/classpath-example/classes
```

Use the same dependency options with `list-breakpoints`. Repeated class-path options
append, and `-cp`/`-classpath` are aliases. Native path lists use `:` on Linux/macOS
and `;` on Windows. Quote patterns so the tracer, rather than the shell, expands them.

Copying is optional and defaults to false. Borrowed dependencies must stay available
and unchanged until the job finishes. Copy mode captures available local manifest
dependencies before compilation, preserves archive bytes, and removes owned copies
afterward. Source limits do not bound binary dependency sizes. See the
[CLI reference](../../docs/reference/cli.md#binary-dependencies) and
[Java API](../../docs/reference/java-api.md#binary-dependencies) for manifest, link,
ordering, and per-job batch rules.
