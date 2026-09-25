package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class MultithreadCliTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void multithreadDoesNotDiscoverNeighboringSources(boolean envelope) throws Exception {
        var packageDir = Files.createDirectories(directory.resolve("isolated"));
        var input = packageDir.resolve("Main.java");
        Files.writeString(packageDir.resolve("Broken.java"), "not valid Java");
        Files.writeString(packageDir.resolve("Helper.java"),
                "package isolated; class Helper { static int value = 42; }");
        var source = """
                package isolated;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println(%s);
                    }
                }
                """;
        var options = new ArrayList<>(List.of("--multithread", "--format", "modern",
                "--input", input.toString(), "--timeout-ms", "10000"));
        if (envelope) options.add("--result-envelope");
        for (boolean usesNeighbor : List.of(false, true)) {
            Files.writeString(input, source.formatted(usesNeighbor ? "Helper.value" : "42"));
            var command = new App.Trace();
            new CommandLine(command).parseArgs(options.toArray(String[]::new));
            var status = new AtomicInteger();
            command.exitHandler = status::set;
            var output = new ByteArrayOutputStream();
            var previous = System.out;
            try (var captured = new PrintStream(output)) {
                System.setOut(captured);
                command.run();
            } finally {
                System.setOut(previous);
            }
            assertThat(status.get()).as("uses neighboring dependency: %s", usesNeighbor)
                    .isEqualTo(usesNeighbor ? 1 : 0);
            if (envelope) {
                var result = JsonParser.parseString(output.toString()).getAsJsonObject();
                assertThat(result.get("complete").getAsBoolean()).isEqualTo(!usesNeighbor);
                assertThat(result.getAsJsonArray("diagnostics").toString())
                        .contains("FIELDS inspection");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void enablesChronologicalThreadCaptureWithAndWithoutEnvelope(boolean envelope) {
        var source = """
                public class Main {
                    public static void main(String[] args) throws InterruptedException {
                        Thread worker = new Thread(() -> System.out.println("worker"), "worker");
                        worker.start();
                        worker.join();
                    }
                }
                """;
        var options = new ArrayList<>(List.of("--multithread", "--format", "modern",
                "--timeout-ms", "10000", "--max-threads", "8", "--max-frames", "128",
                "--max-snapshot-bytes", "1048576"));
        if (envelope) options.add("--result-envelope");
        var document = JsonParser.parseString(AppTest.executeCommand(App.Trace::new, source,
                options.toArray(String[]::new)).orElseThrow()).getAsJsonObject();
        if (envelope) {
            assertThat(document.get("complete").getAsBoolean()).isTrue();
            assertThat(document.get("stdout").getAsString()).isEqualTo("worker\n");
            document = document.getAsJsonObject("trace");
        }
        var steps = document.getAsJsonArray("steps");
        assertThat(steps).isNotEmpty().allSatisfy(step -> {
            assertThat(step.getAsJsonObject().has("threads")).isTrue();
            assertThat(step.getAsJsonObject().has("triggeringThreadId")).isTrue();
        });
        assertThat(steps).anySatisfy(step -> assertThat(step.getAsJsonObject()
                .get("event").getAsString()).isEqualTo("thread_death"));
    }

    @Test
    void rejectsPythonTutorBeforeReadingSource() {
        var command = new App.Trace();
        new CommandLine(command).parseArgs("--multithread", "--format", "pytutor");
        var status = new AtomicInteger();
        command.exitHandler = status::set;
        var errors = new ByteArrayOutputStream();
        var previous = System.err;
        try (var captured = new PrintStream(errors)) {
            System.setErr(captured);
            command.run();
        } finally {
            System.setErr(previous);
        }
        assertThat(status.get()).isEqualTo(2);
        assertThat(errors.toString()).contains("requires --format modern");
    }
}
