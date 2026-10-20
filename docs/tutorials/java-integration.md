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
        System.err.print(outcome.stderr());
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
instructor limits, trusted inspection, and main-exit capture. This trusted example
throws when tracing records a failure or stop. There is a current limitation:
in single-stack Java calls, an uncaught exception thrown inside a JDK or compiled
dependency method can leave `status: "completed"` and `complete: true`, with its
stack trace only in guest stderr. The example forwards that captured stderr, but
ordinary programs can also write there; it is not a machine-readable success
classifier. See [uncaught exceptions](../reference/batch.md#uncaught-student-exceptions).

A service must handle the [version 1 result envelope](../BOUNDED_TRACING.md#envelope),
[stop reasons](../BOUNDED_TRACING.md#stop-codes), and partial results explicitly.
The [output reference](../reference/output.md) defines the Python Tutor and modern
payloads returned by `traceJson()`.

`Tracer.trace` releases job resources before returning. The detached outcome
remains usable afterward. `json()` returns the envelope; `traceJson()` returns the
payload or the JSON literal `null`. No close operation is needed for this one-shot
API. A `BatchTracer`, by contrast, owns workers and must be closed.

## Add the library to a Maven application

For a maintained application, use the regular library artifact and let
**Maven 3.10 or later** resolve its runtime dependencies. This example installs
the library from the source checkout matching this development documentation;
it does not require access to the project's package registry. See
[source installation](../guides/installation.md#build-from-source) to obtain the
checkout. Use a full JDK 21 or later, as above.

At the tracer repository root, install the development artifact and its POM into
your local Maven repository. In **Bash** on Linux or macOS:

```sh
./mvnw -B -ntp install
```

In **PowerShell** or **Command Prompt** on Windows:

```powershell
.\mvnw.cmd -B -ntp install
```

This must finish with `BUILD SUCCESS` before continuing. It installs
`cs1302uga:cs1302-tracer:4.2.2-SNAPSHOT` locally.

Create a separate directory named `tracer-consumer`. Save the complete
`TraceExample.java` from the first part of this lesson at
`tracer-consumer/src/main/java/TraceExample.java`, and save this as
`tracer-consumer/pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>example</groupId>
  <artifactId>tracer-consumer</artifactId>
  <version>1.0-SNAPSHOT</version>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <dependencies>
    <!-- Use the regular tracer library and its transitive dependencies. -->
    <dependency>
      <groupId>cs1302uga</groupId>
      <artifactId>cs1302-tracer</artifactId>
      <version>4.2.2-SNAPSHOT</version>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <!-- Compile the consumer against the Java 21 API. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.16.0</version>
      </plugin>
      <!-- Copy runtime JARs for the explicit launch classpath. -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-dependency-plugin</artifactId>
        <version>3.11.0</version>
      </plugin>
    </plugins>
  </build>
</project>
```

From `tracer-consumer`, compile the application and copy its runtime dependencies:

```sh
mvn -B -ntp package dependency:copy-dependencies -DincludeScope=runtime
```

After `BUILD SUCCESS`, run on Linux or macOS:

```sh
java -cp "target/classes:target/dependency/*" TraceExample
```

On Windows, use a semicolon separator:

```powershell
java -cp "target/classes;target/dependency/*" TraceExample
```

Check that the process exits successfully and prints the same completed envelope
with guest stdout `hello\n` as the standalone example. The `*` classpath component
loads the copied dependency JARs; the consumer is not packaged as a nested archive.

The POM version above belongs to this development checkout. A release integration
needs that release's artifact and matching dependency metadata installed or
available through an explicitly configured repository. Package-registry access
is not established by this local example. The regular JAR does not bundle its
dependencies; copying it alone is insufficient at runtime. Maven supplies API
Guardian transitively; manual compilation can include it to avoid missing
annotation warnings. See
[compile-time dependencies](../reference/java-api.md#stability-and-compile-time-dependencies).

Continue with the [Java recipes](../guides/java-recipes.md) for budgets, input,
multiple files, outcome handling, and batch processing, and the
[Java reference](../reference/java-api.md) for request builders, source bundles,
binary dependencies, inspection, and ownership. Existing 3.x
consumers should read the [migration guide](../guides/java-api-migration.md).
Guest execution still requires [external isolation](../RUNNER_CONTRACT.md) for
untrusted submissions.
