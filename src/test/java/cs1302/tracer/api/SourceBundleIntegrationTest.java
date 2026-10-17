package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Exercises source bundles through the existing Java, CLI, and NDJSON boundaries. */
@Timeout(60)
class SourceBundleIntegrationTest {

    private static final String HELPER_PATH = "bundlecheck/Helper.java";
    private static final String MAIN_PATH = "bundlecheck/Main.java";
    private static final String HELPER = """
            package bundlecheck;

            class Helper {

                static int answer() {
                    return 42;
                }

            }""";
    private static final String MAIN = """
            package bundlecheck;

            public class Main {

                public static void main(String[] args) {
                    System.out.print("café " + Helper.answer());
                }

            }
            """;

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void javaTracePreservesBundledSourcesAndFileRelativeLines(TraceFormat format, @TempDir Path directory)
            throws Exception {
        SourceBundle bundle = filesBundle(directory, false);
        TraceOutcome outcome = Tracer.trace(TraceRequest.builder(bundle.source())
                .format(format).chronological(true).breakpoints(List.of(HELPER_PATH + ":6"))
                .inspection(InspectionPolicy.FIELDS).build());

        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("café 42");
        assertThat(outcome.stderr()).isEmpty();
        assertSourcesAndHelperLine(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format, bundle);
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void cliAcceptsBundleAsUtf8StandardInput(TraceFormat format, @TempDir Path directory) throws Exception {
        SourceBundle bundle = filesBundle(directory.resolve("input"), true);
        Path output = directory.resolve("output.json");
        Path errors = directory.resolve("errors.txt");
        List<String> command = tracerCommand();
        command.addAll(List.of("cs1302.tracer.App", "trace", "--result-envelope", "--inspection", "FIELDS",
                "--format", format.toString(), "--all-breakpoints", "--breakpoints", HELPER_PATH + ":6",
                "--timeout-ms", "10000", "--max-snapshots", "200", "--max-output-bytes", "65536"));
        Process process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(errors.toFile())
                .start();
        try {
            try (InputStream input = bundle.openStream(); OutputStream guestSource = process.getOutputStream()) {
                input.transferTo(guestSource);
            }
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("CLI must finish within its outer deadline").isTrue();
            assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
            JsonObject result = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            assertThat(result.get("stdout").getAsString()).isEqualTo("café 42");
            assertThat(result.get("stderr").getAsString()).isEmpty();
            assertSourcesAndHelperLine(result.getAsJsonObject("trace"), format, bundle);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void consecutiveBatchJobsKeepTheirOwnBundleAndSourceMetadata(@TempDir Path directory) throws Exception {
        SourceBundle first = filesBundle(directory.resolve("first"), false);
        Path secondRoot = directory.resolve("second");
        Files.createDirectories(secondRoot);
        Files.writeString(secondRoot.resolve("0-helper.txt"), HELPER.replace("return 42", "return 7"));
        Files.writeString(secondRoot.resolve("1-main.txt"), MAIN.replace("café ", "second "));
        SourceBundle second = SourceBundle.builder().addInferredFiles(secondRoot, "*.txt").build();
        List<SourceBundle> bundles = List.of(first, second);
        List<TraceFormat> formats = List.of(TraceFormat.PYTUTOR, TraceFormat.MODERN);
        Gson gson = new Gson();
        StringBuilder input = new StringBuilder();
        for (int index = 0; index < bundles.size(); index++) {
            input.append(gson.toJson(Map.of("id", "job-" + index, "source", bundles.get(index).source(),
                    "format", formats.get(index).toString(), "allBreakpoints", true,
                    "inspection", "FIELDS", "limits", TraceLimits.instructorDefaults()))).append('\n');
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = BatchTracer.builder().workers(1).maxJobsPerWorker(10).maxInFlight(2).build()) {
            batch.processStream(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output);
        }

        List<String> responses = output.toString().lines().toList();
        assertThat(responses).hasSize(2);
        for (int index = 0; index < responses.size(); index++) {
            JsonObject response = JsonParser.parseString(responses.get(index)).getAsJsonObject();
            assertThat(response.get("id").getAsString()).isEqualTo("job-" + index);
            JsonObject result = response.getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            assertThat(result.get("stdout").getAsString()).isEqualTo(index == 0 ? "café 42" : "second 7");
            assertThat(result.get("stderr").getAsString()).isEmpty();
            JsonObject trace = result.getAsJsonObject("trace");
            assertThat(trace.get("code").getAsString()).isEqualTo(bundles.get(index).source());
            assertThat(trace.get("entryFile").getAsString()).isEqualTo(MAIN_PATH);
            assertThat(trace.getAsJsonObject("sources").keySet()).containsExactlyInAnyOrder(HELPER_PATH, MAIN_PATH);
            JsonObject last = steps(trace, formats.get(index)).asList().getLast().getAsJsonObject();
            assertThat(last.get("stdout").getAsString()).isEqualTo(index == 0 ? "café 42" : "second 7");
            assertThat(last.get("stdinConsumed").getAsString()).isEmpty();
            assertThat(last.get("stdinOffset").getAsInt()).isZero();
        }
    }

    @Test
    void inferredSinglePackagePrivateTypeRemainsAValidEntryPoint() {
        String source = """
                package inferred;

                class HiddenMain {

                    public static void main(String[] args) {
                        System.out.print("package-private");
                    }

                }
                """;
        SourceBundle bundle = SourceBundle.builder().addInferredSource(source).build();
        TraceOutcome outcome = Tracer.trace(TraceRequest.builder(bundle.source()).format(TraceFormat.MODERN).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("package-private");
        JsonObject trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        assertThat(trace.get("entryFile").getAsString()).isEqualTo("inferred/HiddenMain.java");
        assertThat(trace.getAsJsonObject("sources").get("inferred/HiddenMain.java").getAsString()).isEqualTo(source);
    }

    @Test
    void modernMultithreadCaptureKeepsBundledWorkerSource() {
        String main = """
                package threadedbundle;

                public class Main {

                    public static void main(String[] args) throws InterruptedException {
                        Thread worker = new Thread(Worker::run, "bundle-worker");
                        worker.start();
                        worker.join();
                    }

                }
                """;
        String worker = """
                package threadedbundle;

                class Worker {

                    static void run() {
                        System.out.print("worker");
                    }

                }
                """;
        SourceBundle bundle = SourceBundle.builder().addInferredSource(worker)
                .addSource("threadedbundle/Main.java", main).build();
        TraceOutcome outcome = Tracer.trace(TraceRequest.builder(bundle.source()).format(TraceFormat.MODERN)
                .multithread(true).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("worker");
        JsonObject trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        assertThat(trace.get("entryFile").getAsString()).isEqualTo("threadedbundle/Main.java");
        assertThat(trace.getAsJsonObject("sources").get("threadedbundle/Worker.java").getAsString()).isEqualTo(worker);
        List<JsonObject> snapshots = steps(trace, TraceFormat.MODERN).asList().stream()
                .map(JsonElement::getAsJsonObject).toList();
        assertThat(snapshots).allSatisfy(snapshot -> assertThat(snapshot.getAsJsonArray("threads")).isNotNull());
        assertThat(snapshots).anySatisfy(snapshot -> {
            assertThat(snapshot.get("file")).isEqualTo(new JsonPrimitive("threadedbundle/Worker.java"));
            assertThat(snapshot.get("line").getAsInt()).isEqualTo(6);
            assertThat(snapshot.getAsJsonArray("threads").asList()).anySatisfy(thread ->
                    assertThat(thread.getAsJsonObject().get("name").getAsString()).isEqualTo("bundle-worker"));
        });
    }

    @Test
    void explicitInvalidJavaRemainsACompilerDiagnostic() {
        SourceBundle bundle = SourceBundle.builder().addSource("Broken.java", "public class Broken { broken }")
                .build();
        TraceOutcome outcome = Tracer.trace(new TraceRequest(bundle.source()));
        assertThat(outcome.complete()).isFalse();
        assertThat(outcome.status()).isEqualTo("failed");
        assertThat(outcome.stopReason()).isEqualTo("compile_error");
        assertThat(outcome.phase()).isEqualTo("compile");
        assertThat(outcome.diagnostics()).isNotEmpty();
        assertThat(outcome.traceJson()).isEqualTo("null");
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void inferredGroupUsesEncounteredPathOrderForMainSelection(TraceFormat format, @TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve("a.txt"),
                "public class Z { public static void main(String[] args) { System.out.print(\"first\"); } }");
        Files.writeString(root.resolve("z.txt"),
                "public class A { public static void main(String[] args) { System.out.print(\"second\"); } }");
        SourceBundle bundle = SourceBundle.builder().addInferredFiles(root, "*.txt").build();
        TraceOutcome outcome = Tracer.trace(TraceRequest.builder(bundle.source()).format(format).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        assertThat(outcome.stdout()).isEqualTo("first");
        JsonObject trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        assertThat(trace.get("entryFile").getAsString()).isEqualTo("Z.java");
        assertThat(new ArrayList<>(trace.getAsJsonObject("sources").keySet())).containsExactly("Z.java", "A.java");
    }

    private static SourceBundle filesBundle(Path root, boolean infer) throws Exception {
        assertThat(HELPER).doesNotEndWith("\n");
        Files.createDirectories(root.resolve("bundlecheck"));
        Files.writeString(root.resolve(HELPER_PATH), HELPER);
        Files.writeString(root.resolve(MAIN_PATH), MAIN);
        SourceBundle.Builder builder = SourceBundle.builder();
        if (infer) {
            builder.addInferredFiles(root, "bundlecheck/**/*.java");
        } else {
            builder.addFiles(root, "bundlecheck/**/*.java");
        }
        return builder.build().toBuilder().build();
    }

    private static void assertSourcesAndHelperLine(JsonObject trace, TraceFormat format, SourceBundle bundle) {
        assertThat(trace.get("code").getAsString()).isEqualTo(bundle.source());
        assertThat(trace.get("entryFile").getAsString()).isEqualTo(MAIN_PATH);
        JsonObject sources = trace.getAsJsonObject("sources");
        assertThat(sources.keySet()).containsExactlyInAnyOrder(HELPER_PATH, MAIN_PATH);
        assertThat(sources.get(HELPER_PATH).getAsString()).isEqualTo(HELPER + "\n");
        assertThat(sources.get(MAIN_PATH).getAsString()).isEqualTo(MAIN);
        assertThat(steps(trace, format).asList()).anySatisfy(element -> {
            JsonObject step = element.getAsJsonObject();
            assertThat(step.get("file")).isEqualTo(new JsonPrimitive(HELPER_PATH));
            assertThat(step.get("line").getAsInt()).isEqualTo(6);
        });
    }

    private static JsonArray steps(JsonObject trace, TraceFormat format) {
        return trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
    }

    private static List<String> tracerCommand() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
                .forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path")));
        return command;
    }

}
