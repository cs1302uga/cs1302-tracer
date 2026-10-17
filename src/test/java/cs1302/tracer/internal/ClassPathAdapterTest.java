package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Caller paths share per-job dependencies without changing submitted-source boundaries. */
class ClassPathAdapterTest {

    private static final String SOURCE = """
            import dependency.Helper;
            public class Main {
                public static void main(String[] args) {
                    int value = Helper.value();
                    System.out.print(value);
                }
            }
            """;

    @Test
    void javaOverloadsTraceDirectoryAndJarDependencies(@TempDir Path root) throws Exception {
        Path classes = helper(root.resolve("first"), 11);
        Path archive = archive(root.resolve("library with spaces.jar"), classes);
        for (TraceFormat format : TraceFormat.values()) {
            TraceRequest request = TraceRequest.builder(SOURCE).format(format).chronological(true).build();
            TraceOutcome directory = Tracer.trace(request, TraceClassPath.builder().addPath(classes).build());
            assertThat(directory.complete()).as(directory.diagnostics().toString()).isTrue();
            assertThat(directory.stdout()).isEqualTo("11");
            TraceOutcome jar = Tracer.trace(request, TraceClassPath.builder().addPath(archive).copy(true).build(), true);
            assertThat(jar.complete()).as(jar.diagnostics().toString()).isTrue();
            assertThat(jar.stdout()).isEqualTo("11");
            assertThat(jar.traceJson()).contains("\"bits\"");
        }
        TraceOutcome threaded = Tracer.trace(TraceRequest.builder(SOURCE).format(TraceFormat.MODERN)
                .multithread(true).build(), TraceClassPath.builder().addPath(archive).build());
        assertThat(threaded.complete()).as(threaded.diagnostics().toString()).isTrue();
        assertThat(threaded.stdout()).isEqualTo("11");
        assertThat(TraceRequest.class.getRecordComponents()).hasSize(13);
        assertThat(Files.exists(classes.resolve("dependency/Helper.class"))).isTrue();
        assertThat(Files.exists(archive)).isTrue();
    }

    @Test
    void discoveryCompilesWithDependenciesButNeverScansTheirLines(@TempDir Path root) throws Exception {
        Path classes = helper(root, 11);
        var discovery = Tracer.discoverBreakpoints(SOURCE, TraceClassPath.builder().addPath(classes).build());
        assertThat(discovery.diagnostics()).isEmpty();
        assertThat(discovery.breakpoints()).isNotEmpty().allSatisfy(location ->
                assertThat(location.sourcePath()).isEqualTo("Main.java"));
        assertThat(Tracer.discoverBreakpoints(SOURCE).diagnostics()).isNotEmpty();
        assertThat(Tracer.discoverBreakpoints(SOURCE,
                TraceClassPath.builder().addPath(root.resolve("missing.jar")).build()).diagnostics())
                .anySatisfy(message -> assertThat(message).contains("missing.jar"));
        assertThatThrownBy(() -> Tracer.discoverBreakpoints(SOURCE, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Tracer.trace(new TraceRequest(SOURCE), (TraceClassPath) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Tracer.trace(null, TraceClassPath.empty())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void cliTraceAndDiscoveryAcceptSharedOptions(@TempDir Path root) throws Exception {
        Path classes = helper(root.resolve("compiled"), 11);
        archive(root.resolve("helper.jar"), classes);
        for (String format : List.of("modern", "pytutor")) {
            String ordinary = AppTest.executeCommand(App.Trace::new, SOURCE,
                    "--format", format, "--class-path", classes.toString(), "--all-breakpoints").orElseThrow();
            assertThat(ordinary).contains("11");
            String envelope = AppTest.executeCommand(App.Trace::new, SOURCE,
                    "--format", format, "-classpath", root + "/**/*.jar", "--copy-class-path",
                    "--result-envelope", "--all-breakpoints").orElseThrow();
            JsonObject result = JsonParser.parseString(envelope).getAsJsonObject();
            assertThat(result.get("complete").getAsBoolean()).isTrue();
            assertThat(result.get("stdout").getAsString()).isEqualTo("11");
        }
        String lines = AppTest.executeCommand(App.ListBreakpoints::new, SOURCE,
                "--json", "-cp", root + "/*").orElseThrow();
        assertThat(JsonParser.parseString(lines).getAsJsonArray()).isNotEmpty();
    }

    @Test
    void batchDependenciesAndFailuresRemainJobScoped(@TempDir Path root) throws Exception {
        Path first = helper(root.resolve("one"), 11);
        Path second = helper(root.resolve("two"), 22);
        archive(root.resolve("two").resolve("helper.jar"), second);
        Gson gson = new Gson();
        JsonObject initial = request("first", first.toString());
        JsonObject missing = request("missing", root.resolve("missing.jar").toString());
        JsonObject invalid = request("invalid", first.toString());
        invalid.getAsJsonArray("classPath").set(0, JsonParser.parseString("{\"root\":\".\"}"));
        JsonObject next = request("second", second.toString());
        JsonObject pattern = new JsonObject();
        pattern.addProperty("root", root.resolve("two").toString());
        pattern.addProperty("glob", "**/*.jar");
        next.getAsJsonArray("classPath").set(0, pattern);
        next.addProperty("copyClassPath", true);
        JsonObject repeated = request("repeated", first.toString());
        String ndjson = String.join("\n", List.of(initial, missing, invalid, next, repeated).stream()
                .map(gson::toJson).toList()) + "\n";
        StringWriter output = new StringWriter();
        try (BatchTracer processor = new BatchTracer(1, 20, 1, false)) {
            processor.processStream(new ByteArrayInputStream(ndjson.getBytes(StandardCharsets.UTF_8)), output);
        }
        List<JsonObject> responses = output.toString().lines()
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        assertThat(responses).hasSize(5);
        assertThat(responses.stream().map(response -> response.get("id").getAsString()))
                .containsExactly("first", "missing", "invalid", "second", "repeated");
        for (int index : List.of(0, 3, 4)) {
            JsonObject result = responses.get(index).getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            assertThat(result.get("stdout").getAsString()).isEqualTo(index == 3 ? "22" : "11");
        }
        assertThat(responses.get(1).getAsJsonObject("result").get("phase").getAsString()).isEqualTo("compile");
        assertThat(responses.get(2).getAsJsonObject("result").get("phase").getAsString()).isEqualTo("validation");
    }

    @Test
    void ordinaryCommandsExplainMissingPathsAndPatternsWithoutVerbose(@TempDir Path root) throws Exception {
        Path source = Files.writeString(root.resolve("Main.java"), SOURCE);
        for (java.util.function.Supplier<App.CommandBase> factory :
                List.<java.util.function.Supplier<App.CommandBase>>of(App.Trace::new, App.ListBreakpoints::new)) {
            for (String missing : List.of(root.resolve("absent.jar").toString(), root + "/**/absent*.jar")) {
                App.CommandBase command = factory.get();
                ByteArrayOutputStream errors = new ByteArrayOutputStream();
                PrintStream previous = System.err;
                java.util.concurrent.atomic.AtomicInteger exit = new java.util.concurrent.atomic.AtomicInteger();
                command.exitHandler = exit::set;
                try (PrintStream capture = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
                    System.setErr(capture);
                    new picocli.CommandLine(command).execute("--input", source.toString(), "-cp", missing);
                } finally {
                    System.setErr(previous);
                }
                assertThat(exit.get()).isEqualTo(1);
                assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Unable to", "absent", root.toString());
                if (missing.contains("*")) {
                    assertThat(errors.toString(StandardCharsets.UTF_8)).contains("**/absent*.jar");
                }
            }
        }
    }

    private static JsonObject request(String id, String dependency) {
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("source", SOURCE);
        request.addProperty("format", "modern");
        request.addProperty("allBreakpoints", true);
        com.google.gson.JsonArray paths = new com.google.gson.JsonArray();
        paths.add(dependency);
        request.add("classPath", paths);
        return request;
    }

    private static Path helper(Path root, int initial) throws Exception {
        Path source = Files.createDirectories(root.resolve("source/dependency")).resolve("Helper.java");
        Files.writeString(source, "package dependency; public class Helper { private static int value = "
                + initial + "; public static int value() { return value++; } }");
        Path classes = Files.createDirectories(root.resolve("classes"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-g", "-d",
                classes.toString(), source.toString())).isZero();
        return classes;
    }

    private static Path archive(Path target, Path classes) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return target;
    }

} // ClassPathAdapterTest
