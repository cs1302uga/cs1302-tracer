package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JavaTimeIntegrationTest.*;
import static cs1302.tracer.internal.trace.SetIntegrationTest.*;

import com.google.gson.*;
import cs1302.tracer.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SetBoundaryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void binaryDirectSetAndItsSubclassRetainGenericElements(TraceFormat format) throws Exception {
        Path file = directory.resolve("Direct.java");
        Files.writeString(file, """
                import java.util.*;
                public class Direct<E> implements Set<E> {
                    private final Set<E> data = new LinkedHashSet<>();
                    public int size() { return data.size(); }
                    public boolean isEmpty() { return data.isEmpty(); }
                    public boolean contains(Object o) { return data.contains(o); }
                    public Iterator<E> iterator() { return data.iterator(); }
                    public Object[] toArray() { return data.toArray(); }
                    public <T> T[] toArray(T[] a) { return data.toArray(a); }
                    public boolean add(E e) { return data.add(e); }
                    public boolean remove(Object o) { return data.remove(o); }
                    public boolean containsAll(Collection<?> c) { return data.containsAll(c); }
                    public boolean addAll(Collection<? extends E> c) { return data.addAll(c); }
                    public boolean retainAll(Collection<?> c) { return data.retainAll(c); }
                    public boolean removeAll(Collection<?> c) { return data.removeAll(c); }
                    public void clear() { data.clear(); }
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-g:none", "-d", directory.toString(), file.toString())).isZero();
        Files.delete(file);
        String source = """
                public class Main {
                    static class Child<E> extends Direct<E> {}
                    static Direct<String> direct;
                    static Child<String> child;
                    static Object[] roots;
                    public static void main(String[] args) {
                        Direct<String> direct = new Direct<>(); direct.add("first"); direct.add("last");
                        Child<String> child = new Child<>(); child.add("first"); child.add("last");
                        roots = new Object[] { direct, child };
                        System.out.println("ready");
                    }
                }
                """;
        var outcome = Tracer.trace(TraceRequest.builder(source).format(format).build(),
                TraceClassPath.builder().addPath(directory).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        var trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        var heap = lastHeap(trace, format);
        for (var root : rootElements(heap, format, 2)) {
            var entry = heap.get(ref(root, format));
            assertThat(elements(entry, format, true).stream().map(value -> display(heap, value, format)).toList())
                    .containsExactly("first", "last");
        }
        assertThat(trace.toString()).contains("Direct<java.lang.String>", "Child<java.lang.String>");
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void setBudgetsPreserveCompletedSnapshots(TraceFormat format) {
        String source = """
                import java.util.*;
                public class Main {
                    static Object view;
                    public static void main(String[] args) {
                        System.out.println("before");
                        Set<Object> values = new LinkedHashSet<>();
                        for (int i = 0; i < 80; i++) values.add(new Object());
                        view = Collections.unmodifiableSet(values);
                        System.out.println("after");
                    }
                }
                """;
        for (boolean heapLimit : List.of(false, true)) {
            var limits = TraceLimits.builder().timeoutMillis(15000);
            if (heapLimit) limits.heapObjects(30); else limits.elements(50);
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true)
                    .limits(limits.build()).build());
            assertThat(outcome.complete()).isFalse();
            assertThat(outcome.stopReason()).as(outcome.json()).isEqualTo(heapLimit ? "heap_limit" : "element_limit");
            var trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
            assertThat(trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace")).isNotEmpty();
        }
    }

    @Test
    void throwingAccessorDoesNotHideLaterProgramExceptionsInEitherGuestPath() throws Exception {
        String source = """
                import java.util.*;
                public class Main {
                    static class Broken extends HashSet<Object> {
                        public Object[] toArray() { throw new IllegalStateException("inspection"); }
                    }
                    static Object view;
                    public static void main(String[] args) {
                        view = Collections.unmodifiableSet(new Broken());
                        System.out.println("captured");
                        throw new IllegalArgumentException("program");
                    }
                }
                """;
        for (TraceFormat format : TraceFormat.values()) {
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true).build());
            assertThat(outcome.stopReason()).as(outcome.json()).isEqualTo("guest_exception");
            assertThat(outcome.traceJson()).contains("UnmodifiableSet", "captured");
        }
        JsonObject job = new JsonObject();
        job.addProperty("source", source);
        job.addProperty("format", "modern");
        job.addProperty("chronological", true);
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream((job + "\n" + job + "\n").getBytes(StandardCharsets.UTF_8)), output);
        }
        var results = output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()
                .getAsJsonObject("result")).toList();
        assertThat(results).hasSize(2);
        for (var result : results) {
            assertThat(result.get("stopReason").getAsString()).as(result.toString()).isEqualTo("guest_exception");
            assertThat(result.getAsJsonObject("trace").toString()).contains("UnmodifiableSet", "captured");
        }
    }

    @Test
    void blockedWrapperAccessorStopsAndNextBatchJobRecovers() throws Exception {
        String source = """
                import java.util.*;
                public class Main {
                    static class Blocked extends HashSet<Object> {
                        public Object[] toArray() { while (true) {} }
                    }
                    static Object view;
                    public static void main(String[] args) {
                        System.out.println("before");
                        view = Collections.unmodifiableSet(new Blocked());
                        System.out.println("after");
                    }
                }
                """;
        var request = TraceRequest.builder(source).format(TraceFormat.MODERN).chronological(true)
                .limits(TraceLimits.builder().timeoutMillis(1500).build()).build();
        var outcome = Tracer.trace(request);
        assertThat(outcome.stopReason()).as(outcome.json()).isEqualTo("timeout");
        assertThat(JsonParser.parseString(outcome.traceJson()).getAsJsonObject().getAsJsonArray("steps")).isNotEmpty();
        JsonObject job = new JsonObject();
        job.addProperty("source", source);
        job.addProperty("format", "modern");
        job.addProperty("chronological", true);
        JsonObject limits = new JsonObject(); limits.addProperty("timeoutMillis", 1500); job.add("limits", limits);
        JsonObject next = new JsonObject();
        next.addProperty("source", """
                public class Main {
                    static Object value;
                    public static void main(String[] a) {
                        value = java.util.Collections.unmodifiableSet(java.util.Set.of("recovered"));
                        System.out.println("done");
                    }
                }
                """);
        next.addProperty("format", "pytutor");
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream((job + "\n" + next + "\n").getBytes(StandardCharsets.UTF_8)), output);
        }
        var results = output.toString().lines().map(line -> JsonParser.parseString(line).getAsJsonObject()
                .getAsJsonObject("result")).toList();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("stopReason").getAsString()).isEqualTo("timeout");
        assertThat(results.get(1).get("complete").getAsBoolean()).as(results.get(1).toString()).isTrue();
        assertThat(results.get(1).toString()).contains("recovered").doesNotContain("Blocked");
    }
}
