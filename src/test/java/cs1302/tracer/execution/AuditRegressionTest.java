package cs1302.tracer.execution;

import static org.assertj.core.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditRegressionTest {
    @TempDir Path directory;

    private static final String QUALIFIED_SOURCE = """
            // --- Main.java ---
            public class Main {
              public static void main(String[] args) {
                for (int i=0;i<2;i++) { p.Helper.run(); q.Helper.run(); }
              }
            }
            // --- p/Helper.java ---
            package p;
            public class Helper {
              public static void run() {
                System.out.print("p");
              }
            }
            // --- q/Helper.java ---
            package q;
            public class Helper {
              public static void run() {
                System.out.print("q");
              }
            }
            """;

    @Test void qualifiedBreakpointsRetainIndependentFilesInBothFormatsAndCaptureModes()
            throws Exception {
        for (String format : List.of("modern", "pytutor")) {
            for (String mode : List.of("latest", "accumulate", "chronological")) {
                var options = new ArrayList<>(List.of("--result-envelope", "-f", format,
                        "--breakpoint-at", "p/Helper.java:4", "--breakpoint-at", "q/Helper.java:4"));
                if (mode.equals("accumulate")) options.add("--accumulate-breakpoints");
                if (mode.equals("chronological")) options.add("-a");
                var result = trace(QUALIFIED_SOURCE, options.toArray(String[]::new));
                assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
                assertThat(result.getAsJsonObject("counters").get("snapshotsCaptured").getAsInt())
                        .isEqualTo(4);
                assertThat(result.getAsJsonObject("counters").get("snapshotsRetained").getAsInt())
                        .isEqualTo(mode.equals("latest") ? 2 : 4);
                String payload = result.getAsJsonObject("trace").toString();
                assertThat(payload).contains("p/Helper.java", "q/Helper.java");
                var steps = result.getAsJsonObject("trace")
                        .getAsJsonArray(format.equals("modern") ? "steps" : "trace");
                var files = new ArrayList<String>();
                steps.forEach(step -> files.add(step.getAsJsonObject().get("file").getAsString()));
                assertThat(files).containsExactlyElementsOf(mode.equals("latest")
                        ? List.of("p/Helper.java", "q/Helper.java")
                        : List.of("p/Helper.java", "q/Helper.java", "p/Helper.java", "q/Helper.java"));
                assertThat(result.get("stdout").getAsString()).isEqualTo("pqpq");
            }
        }
        var legacy = trace(QUALIFIED_SOURCE, "--result-envelope", "-b", "4");
        assertThat(legacy.getAsJsonObject("counters").get("snapshotsRetained").getAsInt()).isEqualTo(1);
    }

    @Test void unknownQualifiedLocationsFailBeforeLaunchingTheGuest() throws Exception {
        for (String selector : List.of("Helper.java:4", "p/Helper.java:999")) {
            var result = trace(QUALIFIED_SOURCE, "--result-envelope", "--breakpoint-at", selector);
            assertThat(result.get("phase").getAsString()).isEqualTo("compile");
            assertThat(result.get("trace").isJsonNull()).isTrue();
            assertThat(result.getAsJsonObject("counters").get("snapshotsCaptured").getAsInt()).isZero();
            assertThat(result.get("diagnostics").toString()).contains("No executable breakpoint");
        }
    }

    JsonObject trace(String source, String... options) throws Exception {
        Path file = directory.resolve("Main.java");
        Files.writeString(file, source);
        return traceFile(file, options);
    }

    JsonObject traceFile(Path file, String... options) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("jacoco"))
                .forEach(command::add);
        command.addAll(List.of("-cp", System.getProperty("java.class.path"),
                "cs1302.tracer.App", "trace", "-i", file.toString()));
        if (!List.of(options).contains("--timeout-ms")) {
            command.addAll(List.of("--timeout-ms", "5000"));
        }
        command.addAll(List.of(options));
        Path out = directory.resolve("out.json");
        Path err = directory.resolve("err.txt");
        Process process = new ProcessBuilder(command).redirectOutput(out.toFile())
                .redirectError(err.toFile()).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("outer deadline").isTrue();
            String json = Files.readString(out);
            assertThat(json).as(Files.readString(err)).isNotEmpty();
            return JsonParser.parseString(json).getAsJsonObject();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test void compiledDependencyDiscoveryUsesTrustedRootAndCapsMetadataReads() throws Exception {
        Path root = Files.createDirectories(directory.resolve("sample"));
        Path main = root.resolve("Main.java");
        Files.writeString(main, "package sample; public class Main { public static void main(String[] args) { Helper.run(); } }");
        Files.writeString(root.resolve("Helper.java"), "package sample; class Helper { static void run() { System.out.println(2); } } /*"
                + " padding".repeat(100) + " */");
        Files.writeString(root.resolve("Unrelated.java"), "This invalid file must not be parsed.");
        var result = traceFile(main, "--result-envelope");
        assertThat(result.get("complete").getAsBoolean()).isTrue();
        assertThat(result.get("stdout").getAsString()).isEqualTo("2\n");
        result = traceFile(main, "--result-envelope", "--max-source-bytes", "180");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("source_limit");
        assertThat(result.get("phase").getAsString()).isEqualTo("compile");
    }

    @Test void largeInputReachesGuestAndEof() throws Exception {
        Path input = directory.resolve("input.txt");
        Files.writeString(input, "x".repeat(1_048_576));
        var result = trace("""
                public class Main {
                    public static void main(String[] args) throws Exception {
                        System.out.println(System.in.readAllBytes().length);
                    }
                }
                """, "--result-envelope", "--stdin-file", input.toString());
        assertThat(result.get("complete").getAsBoolean()).isTrue();
        assertThat(result.get("stdout").getAsString()).isEqualTo("1048576\n");
    }

    @Test void inputBudgetCountsUtf8BytesBeforeCompilation() throws Exception {
        Path input = directory.resolve("utf8.txt");
        Files.writeString(input, "é😀");
        String source = "public class Main { public static void main(String[] args) {} }";
        var stopped = trace(source, "--result-envelope", "--stdin-file", input.toString(),
                "--max-input-bytes", "5");
        assertThat(stopped.get("stopReason").getAsString()).isEqualTo("input_limit");
        assertThat(stopped.get("phase").getAsString()).isEqualTo("source");
        assertThat(stopped.get("trace").isJsonNull()).isTrue();
        assertThat(stopped.getAsJsonObject("limits").get("inputBytes").getAsLong()).isEqualTo(5);
        for (String cap : List.of("6", "0")) {
            assertThat(trace(source, "--result-envelope", "--stdin-file", input.toString(),
                    "--max-input-bytes", cap).get("complete").getAsBoolean()).isTrue();
        }
        assertThat(trace(source, "--result-envelope", "--stdin", "é😀", "--max-input-bytes", "5")
                .get("stopReason").getAsString()).isEqualTo("input_limit");
    }

    @Test void blockedFeederDoesNotPreventEarlyExitOrTimeout() throws Exception {
        Path input = directory.resolve("large.txt");
        Files.writeString(input, "x".repeat(1_048_576));
        for (String body : List.of("System.in.close();", "System.exit(0);")) {
            var result = trace("public class Main { public static void main(String[] args) "
                    + "throws Exception { " + body + " } }", "--result-envelope",
                    "--stdin-file", input.toString());
            assertThat(result.get("complete").getAsBoolean()).isTrue();
        }
        Path pid = directory.resolve("guest.pid");
        String path = pid.toString().replace("\\", "\\\\");
        var result = trace("public class Main { public static void main(String[] args) "
                + "throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(\""
                + path + "\"), Long.toString(ProcessHandle.current().pid())); while(true) {} } }",
                "--result-envelope", "--stdin-file", input.toString(), "--timeout-ms", "2500");
        assertThat(result.get("stopReason").getAsString()).isEqualTo("timeout");
        assertThat(Files.exists(pid)).isTrue();
        assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid)))
                .map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test void finalMetadataUsesDeclarationScopeAndParameters() throws Exception {
        var result = trace("""
                public class Main {
                    public static void main(final String[] args) {
                        { final int x = Integer.parseInt("1");
                          System.out.println(x); }
                        { int x = 2;
                          x++;
                          System.out.println(x); }
                    }
                }
                """, "-a", "-f", "modern");
        boolean seenMutable = false;
        for (var step : result.getAsJsonArray("steps")) {
            for (var frame : step.getAsJsonObject().getAsJsonArray("callStack")) {
                for (var variable : frame.getAsJsonObject().getAsJsonArray("locals")) {
                    var v = variable.getAsJsonObject();
                    if (v.get("name").getAsString().equals("args")) {
                        assertThat(v.get("final").getAsBoolean()).isTrue();
                    }
                    if (v.get("name").getAsString().equals("x")
                            && v.get("value").getAsInt() >= 2) {
                        seenMutable = true;
                        assertThat(v.get("final").getAsBoolean()).isFalse();
                    }
                }
            }
        }
        assertThat(seenMutable).isTrue();
    }

    @Test void constructorsAndOverloadedParametersHaveCorrectFlagsInBothFormats() throws Exception {
        String source = """
                public class Main {
                    Main(final int constructorParam) {
                        System.out.println(constructorParam);
                    }
                    static void inspect(final int parameter) {
                        System.out.println(parameter);
                    }
                    static void inspect(String parameter) {
                        System.out.println(parameter);
                    }
                    public static void main(String[] args) {
                        new Main(2);
                        inspect(3);
                        inspect("text");
                    }
                }
                """;
        for (String format : List.of("modern", "pytutor")) {
            var result = trace(source, "-a", "-f", format);
            var seen = new HashSet<String>();
            for (var step : result.getAsJsonArray(format.equals("modern") ? "steps" : "trace")) {
                for (var frame : step.getAsJsonObject().getAsJsonArray(
                        format.equals("modern") ? "callStack" : "stack_to_render")) {
                    var attributes = new LinkedHashMap<String, JsonObject>();
                    if (format.equals("modern")) {
                        for (var value : frame.getAsJsonObject().getAsJsonArray("locals")) {
                            var local = value.getAsJsonObject();
                            attributes.put(local.get("name").getAsString(), local);
                        }
                    } else {
                        frame.getAsJsonObject().getAsJsonObject("locals_attrs").entrySet()
                                .forEach(e -> attributes.put(e.getKey(), e.getValue().getAsJsonObject()));
                    }
                    for (var e : attributes.entrySet()) {
                        if (!Set.of("constructorParam", "parameter").contains(e.getKey())) continue;
                        String type = e.getValue().get("type").getAsString();
                        seen.add(e.getKey() + ":" + type);
                        assertThat(e.getValue().get("final").getAsBoolean()).isEqualTo(type.equals("int"));
                    }
                }
            }
            assertThat(seen).containsExactlyInAnyOrder("constructorParam:int", "parameter:int",
                    "parameter:java.lang.String");
        }
    }

    @Test void envelopeHasSameChronologicalSequence() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        int x = 1;
                        System.out.println(x);
                    }
                }
                """;
        for (String format : List.of("modern", "pytutor")) {
            String sequence = format.equals("modern") ? "steps" : "trace";
            var ordinary = trace(source, "-a", "-f", format).getAsJsonArray(sequence);
            var envelope = trace(source, "-a", "-f", format, "--result-envelope")
                    .getAsJsonObject("trace").getAsJsonArray(sequence);
            assertThat(envelope.asList().stream().map(s -> s.getAsJsonObject().get("line")))
                    .containsExactlyElementsOf(ordinary.asList().stream()
                            .map(s -> s.getAsJsonObject().get("line")).toList());
        }
    }
}
