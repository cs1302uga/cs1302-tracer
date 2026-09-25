package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class MultithreadCliTest {
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
