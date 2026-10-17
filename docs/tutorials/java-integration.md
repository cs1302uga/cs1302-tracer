# Embed the tracer in Java

This tutorial runs one trusted source program through the supported
`cs1302.tracer.api` interface. Use a full **JDK 21 or later** and the `code-tracer.jar`
from [installation](../guides/installation.md). Work beside that JAR, or enter
`target` after a source build.

## Run a complete example

For this standalone demonstration, the bundled executable JAR supplies the tracer
and its dependencies on the classpath. Save this as `TraceExample.java`:

<!-- java-source:start -->
```java
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;

public class TraceExample {
    public static void main(String[] args) {
        String source = """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("hello");
                }
            }
            """;
        TraceOutcome outcome = Tracer.trace(new TraceRequest(source));
        if (!outcome.complete() || !"completed".equals(outcome.status())) {
            throw new IllegalStateException(outcome.json());
        }
        System.out.println(outcome.json());
    }
}
```
<!-- java-source:end -->

Compile on any supported platform:

<!-- java-compile:start -->
```sh
javac -cp code-tracer.jar TraceExample.java
```
<!-- java-compile:end -->

Run on Linux or macOS:

<!-- java-run:start -->
```sh
java -cp "code-tracer.jar:." TraceExample
```
<!-- java-run:end -->

On Windows, use a semicolon as the classpath separator:

```powershell
java -cp "code-tracer.jar;." TraceExample
```

The program prints a completed result envelope. Its `stdout` contains `hello\n`;
its `trace` contains the Python Tutor payload. The convenience request uses finite
instructor limits, trusted inspection, and main-exit capture. A compilation or
execution failure normally appears in the outcome; do not assume every returned
outcome is complete. This demonstration throws on failure so it has a clear
successful exit. A service should handle statuses and partial results explicitly.

`Tracer.trace` releases job resources before returning. The detached outcome
remains usable afterward. `json()` returns the envelope; `traceJson()` returns the
payload or the JSON literal `null`. No close operation is needed for this one-shot
API. A `BatchTracer`, by contrast, owns workers and must be closed.

## Add the library to a Maven application

For a maintained application, use the ordinary library artifact and let **Maven**
resolve runtime dependencies. To try this development checkout locally, run
`./mvnw -B -ntp install` at the tracer repository root, then add this dependency
to your application's POM:

```xml
<dependency>
  <groupId>cs1302uga</groupId>
  <artifactId>cs1302-tracer</artifactId>
  <version>4.2.0-SNAPSHOT</version>
</dependency>
```

That version belongs to this development documentation. Use the artifact version
matching your chosen release when integrating a release. A local install avoids
needing package-registry credentials for this exercise. The regular JAR does not
bundle dependencies; copying it alone is insufficient for runtime execution.
API Guardian is also needed on a manual compile classpath. See
[compile-time dependencies](../reference/java-api.md#stability-and-compile-time-dependencies).

Continue with the [Java reference](../reference/java-api.md) for request builders,
source bundles, binary dependencies, inspection, and ownership. Existing 3.x
consumers should read the [migration guide](../guides/java-api-migration.md).
Guest execution still requires [external isolation](../RUNNER_CONTRACT.md) for
untrusted submissions.
