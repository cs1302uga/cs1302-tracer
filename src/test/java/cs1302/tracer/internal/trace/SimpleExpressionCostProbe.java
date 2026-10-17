package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;
import cs1302.tracer.internal.App;
import cs1302.tracer.internal.CompilationHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Current form of the workload; the 3.2.3 version supports the historical 3.2.2 comparison. */
class SimpleExpressionCostProbe {
    @Test
    void boundedRepeatedInvocations() throws Exception {
        var results = new LinkedHashMap<String, List<Long>>();
        for (String expression : List.of("value", "\"word\"", "number()", "new String(\"word\")")) {
            String source = """
                    public class Main {
                        static Number number() { return 7; }
                        public static void main(String[] args) {
                            String value = "word";
                            for (int i = 0; i < 100; i++) helper(%s);
                        }
                        static <T> void helper(T item) {
                            T local = null;
                            System.out.println(local);
                        }
                    }
                    """.formatted(expression);
            var unit = App.parseSource(source, Optional.empty());
            var samples = new ArrayList<Long>();
            try (var compiled = CompilationHelper.compile(source)) {
                for (int run = 0; run < 7; run++) {
                    try (var session = new cs1302.tracer.internal.execution.TraceSession(
                            cs1302.tracer.api.TraceLimits.instructorDefaults(),
                            cs1302.tracer.api.InspectionPolicy.FIELDS, true)) {
                        assertThat(session).isNotNull();
                        long start = System.nanoTime();
                        var snapshots = DebugTraceHelper.trace(compiled, List.of(9), unit, "").get(9);
                        long nanos = System.nanoTime() - start;
                        assertThat(snapshots).hasSize(100);
                        if (run >= 2) {
                            samples.add(nanos);
                        }
                    }
                }
            }
            results.put(expression, samples);
        }
        Files.writeString(Path.of("target", "simple-expression-cost.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("java", System.getProperty("java.version"), "traceNanos", results)));
    }
}
