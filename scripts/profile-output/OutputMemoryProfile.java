import com.github.javaparser.StaticJavaParser;
import com.google.gson.Gson;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Standalone measurement harness; deliberately outside production sources. */
public final class OutputMemoryProfile {
    private static Instrumentation instrumentation;

    public static void premain(String ignored, Instrumentation agent) {
        instrumentation = agent;
    }

    public static void main(String[] args) throws Exception {
        String workload = args[0];
        int iterations = Integer.parseInt(args[1]);
        int chunk = Integer.parseInt(args[2]);
        String emission = "System.out.print(\"x\".repeat(" + chunk + "));\n";
        String source = "public class Main {\npublic static void main(String[] args) {\n"
                + (workload.equals("burst") ? emission : "")
                + "int sum = 0;\nfor (int i = 0; i < " + iterations + "; i++) {\n"
                + (workload.equals("continuous") ? emission : "")
                + "sum += i;\n}\n}\n}\n";
        List<ExecutionSnapshot> snapshots;
        try (var compiled = CompilationHelper.compile(source)) {
            snapshots = DebugTraceHelper.traceChronological(compiled,
                    DebugTraceHelper.getValidBreakpointLines(compiled),
                    StaticJavaParser.parse(source), true);
        }
        Set<byte[]> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        long logicalBytes = 0;
        int previousLength = 0;
        for (var snapshot : snapshots) {
            byte[] stdout = snapshot.stdout();
            byte[] stderr = snapshot.stderr();
            if (stdout.length < previousLength || stderr.length != 0) {
                throw new AssertionError("unexpected output sequence");
            }
            for (byte value : stdout) {
                if (value != 'x') {
                    throw new AssertionError("unexpected output content");
                }
            }
            previousLength = stdout.length;
            logicalBytes += stdout.length + stderr.length;
            arrays.add(stdout);
            arrays.add(stderr);
        }
        int expected = chunk * (workload.equals("continuous") ? iterations : 1);
        if (previousLength != expected) {
            throw new AssertionError("incomplete final output");
        }
        long arrayBytes = 0;
        long payloadBytes = 0;
        for (byte[] array : arrays) {
            arrayBytes += instrumentation.getObjectSize(array);
            payloadBytes += array.length;
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("workload", workload);
        result.put("iterations", iterations);
        result.put("chunkBytes", chunk);
        result.put("snapshots", snapshots.size());
        result.put("uniqueOutputArrays", arrays.size());
        result.put("logicalCumulativeOutputBytes", logicalBytes);
        result.put("retainedOutputPayloadBytes", payloadBytes);
        result.put("retainedOutputArrayBytes", arrayBytes);
        result.put("finalOutputBytes", expected);
        // A lower bound, not an implementation measurement: excludes buffer slack,
        // offsets, object headers, serializers, and all non-output trace state.
        result.put("sharedPayloadLowerBoundBytes", expected);
        System.out.println(new Gson().toJson(result));
    }
}
