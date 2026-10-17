package cs1302.tracer.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.SourceBundle;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.api.TraceOutcome;
import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.trace.PersistentGuestSession;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Checks source identity and reachable generic graphs together across reuse of one guest. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BatchSourceHeapIsolationTest {

    private static final String BOX = """
            package reuse;

            public class Box<T> {

                public T value;
                public Box<T> next;

                public Box(T value) {
                    this.value = value;
                }

            }
            """;
    private static final String FIRST = """
            package firstjob;

            import reuse.Box;

            public class Main {

                public static void main(String[] args) throws Exception {
                    int input = System.in.read();
                    Box<Integer> root = new Box<>(input);
                    Box<Integer> tail = new Box<>(input + 1);
                    root.next = tail;
                    tail.next = root;
                    Box<Integer> alias = root;
                    System.out.print("first:" + input);
                }

            }
            """;
    private static final String SECOND = """
            package secondjob;

            import reuse.Box;

            public class Entry {

                public static void main(String[] args) throws Exception {
                    String input = "second:" + (char) System.in.read();
                    Box<String> root = new Box<>(input);
                    root.next = root;
                    Box<String> alias = root;
                    System.out.print(input);
                }

            }
            """;

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void reusedGuestKeepsSourceIdentityAndGenericGraphScopedToEachJob(TraceFormat format) {
        SourceBundle first = SourceBundle.builder().addSource("reuse/Box.java", BOX)
                .addSource("firstjob/Main.java", FIRST).build();
        SourceBundle second = SourceBundle.builder().addSource("reuse/Box.java", BOX)
                .addSource("secondjob/Entry.java", SECOND).build();
        AtomicInteger launches = new AtomicInteger();
        try (BatchTraceWorker worker = new BatchTraceWorker(10, () -> {
            launches.incrementAndGet();
            return PersistentGuestSession.create();
        })) {
            BatchJobResponse firstResponse = worker.execute(request("first", first, "firstjob/Main.java", FIRST,
                    "A", format));
            assertThat(firstResponse.id()).isEqualTo("first");
            assertThat(firstResponse.result().complete()).as(firstResponse.result().toString()).isTrue();
            TraceOutcome detachedFirst = TraceRendering.freeze(firstResponse.result(), false);
            String originalEnvelope = detachedFirst.json();
            JsonObject originalTrace = JsonParser.parseString(detachedFirst.traceJson()).getAsJsonObject();
            JsonObject firstStep = assertSources(originalTrace, first, "firstjob/Main.java", FIRST, format, "A");
            assertGraph(firstStep, format, "java.lang.Integer", false);
            assertThat(detachedFirst.stdout()).isEqualTo("first:65");

            BatchJobResponse secondResponse = worker.execute(request("second", second, "secondjob/Entry.java", SECOND,
                    "Z", format));
            assertThat(secondResponse.id()).isEqualTo("second");
            assertThat(secondResponse.result().complete()).as(secondResponse.result().toString()).isTrue();
            TraceOutcome detachedSecond = TraceRendering.freeze(secondResponse.result(), false);
            JsonObject secondTrace = JsonParser.parseString(detachedSecond.traceJson()).getAsJsonObject();
            JsonObject secondStep = assertSources(secondTrace, second, "secondjob/Entry.java", SECOND, format, "Z");
            assertGraph(secondStep, format, "java.lang.String", true);
            assertThat(detachedSecond.stdout()).isEqualTo("second:Z");
            assertThat(secondTrace.getAsJsonObject("sources").has("firstjob/Main.java")).isFalse();
            assertThat(secondStep.toString()).doesNotContain("firstjob.", "reuse.Box<java.lang.Integer>");
            assertThat(detachedSecond.stderr()).isEmpty();
            assertThat(launches).as("both jobs must use the same persistent guest").hasValue(1);

            assertThat(detachedFirst.json()).isEqualTo(originalEnvelope);
            assertThat(JsonParser.parseString(detachedFirst.traceJson())).isEqualTo(originalTrace);
            assertThat(TraceRendering.freeze(firstResponse.result(), false).json()).isEqualTo(originalEnvelope);
            assertGraph(firstStep, format, "java.lang.Integer", false);
        }
    }

    private static BatchJobRequest request(String id, SourceBundle bundle, String path, String main,
            String stdin, TraceFormat format) {
        List<String> lines = main.lines().toList();
        int stopLine = java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains("System.out.print")).findFirst().orElseThrow() + 1;
        TraceLimits limits = TraceLimits.instructorDefaults().toBuilder().timeoutMillis(10000).snapshots(100).build();
        return BatchJobRequest.builder().id(id).source(bundle.source()).format(format.toString()).stdin(stdin)
                .allBreakpoints(true).breakpoints(List.of(path + ":" + stopLine)).inspection(InspectionPolicy.FIELDS)
                .typeStyle("fqn").limits(limits).build();
    }

    private static JsonObject assertSources(JsonObject trace, SourceBundle bundle, String entryPath, String main,
            TraceFormat format, String stdin) {
        assertThat(trace.get("code").getAsString()).isEqualTo(bundle.source());
        assertThat(trace.get("entryFile").getAsString()).isEqualTo(entryPath);
        assertThat(trace.get("stdin").getAsString()).isEqualTo(stdin);
        JsonObject sources = trace.getAsJsonObject("sources");
        assertThat(sources.keySet()).containsExactly("reuse/Box.java", entryPath);
        assertThat(sources.get("reuse/Box.java").getAsString()).isEqualTo(BOX);
        assertThat(sources.get(entryPath).getAsString()).isEqualTo(main);
        JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
        assertThat(steps).isNotEmpty();
        JsonObject step = steps.get(0).getAsJsonObject();
        assertThat(step.get("file").getAsString()).isEqualTo(entryPath);
        return step;
    }

    private static void assertGraph(JsonObject step, TraceFormat format, String argument, boolean selfCycle) {
        JsonObject heap = step.getAsJsonObject("heap");
        String root = reference(local(step, format, "root"), format);
        assertThat(reference(local(step, format, "alias"), format)).isEqualTo(root);
        String expectedType = "reuse.Box<" + argument + ">";
        List<Map.Entry<String, JsonElement>> boxes = heap.entrySet().stream()
                .filter(entry -> type(entry.getValue(), format).startsWith("reuse.Box")).toList();
        assertThat(boxes).hasSize(selfCycle ? 1 : 2);
        assertThat(boxes).allSatisfy(entry -> assertThat(type(entry.getValue(), format)).isEqualTo(expectedType));
        JsonElement rootObject = heap.get(root);
        String next = reference(field(rootObject, format, "next"), format);
        if (selfCycle) {
            assertThat(next).isEqualTo(root);
        } else {
            assertThat(next).isNotEqualTo(root);
            assertThat(reference(local(step, format, "tail"), format)).isEqualTo(next);
            assertThat(reference(field(heap.get(next), format, "next"), format)).isEqualTo(root);
        }
        JsonElement value = heap.get(reference(field(rootObject, format, "value"), format));
        if (selfCycle) {
            if (format == TraceFormat.MODERN) {
                assertThat(value.getAsJsonObject().get("kind").getAsString()).isEqualTo("string");
                assertThat(value.getAsJsonObject().get("value").getAsString()).isEqualTo("second:Z");
            } else {
                assertThat(value.getAsJsonArray().get(0).getAsString()).isEqualTo("INSTANCE");
                assertThat(value.getAsJsonArray().get(1).getAsString()).isEqualTo("String");
                assertThat(value.getAsJsonArray().get(2).getAsJsonArray().get(1).getAsString())
                        .isEqualTo("second:Z");
            }
        } else {
            assertThat(type(value, format)).isEqualTo("java.lang.Integer");
            assertThat(field(value, format, "value").getAsInt()).isEqualTo(65);
        }
    }

    private static JsonElement local(JsonObject step, TraceFormat format, String name) {
        if (format == TraceFormat.MODERN) {
            JsonArray stack = step.getAsJsonArray("callStack");
            return stack.get(stack.size() - 1).getAsJsonObject().getAsJsonArray("locals").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(local -> local.get("name").getAsString().equals(name))
                    .findFirst().orElseThrow().get("value");
        }
        JsonArray stack = step.getAsJsonArray("stack_to_render");
        return stack.get(stack.size() - 1).getAsJsonObject().getAsJsonObject("encoded_locals").get(name);
    }

    private static String reference(JsonElement value, TraceFormat format) {
        if (format == TraceFormat.MODERN) {
            return value.getAsJsonObject().get("ref").getAsString();
        }
        assertThat(value.getAsJsonArray().get(0).getAsString()).isEqualTo("REF");
        return value.getAsJsonArray().get(1).getAsString();
    }

    private static String type(JsonElement value, TraceFormat format) {
        if (format == TraceFormat.MODERN) {
            return value.getAsJsonObject().get("type").getAsString();
        }
        JsonArray object = value.getAsJsonArray();
        return object.get(0).getAsString().equals("INSTANCE") ? object.get(1).getAsString() : "";
    }

    private static JsonElement field(JsonElement object, TraceFormat format, String name) {
        if (format == TraceFormat.MODERN) {
            return object.getAsJsonObject().getAsJsonArray("fields").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(field -> field.get("name").getAsString().equals(name))
                    .findFirst().orElseThrow().get("value");
        }
        JsonArray fields = object.getAsJsonArray();
        return java.util.stream.IntStream.range(2, fields.size()).mapToObj(fields::get)
                .map(JsonElement::getAsJsonArray).filter(field -> field.get(0).getAsString().equals(name))
                .findFirst().orElseThrow().get(1);
    }

}
