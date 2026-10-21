package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JavaTimeIntegrationTest.*;

import com.google.gson.*;
import cs1302.tracer.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SetIntegrationTest {
    private static final String SOURCE = """
            import java.util.*;
            import java.util.concurrent.*;
            public class Main {
                enum Small { A, B }
                enum Large { %s }
                static class Custom extends AbstractSet<Object> {
                    public int size() { return 2; }
                    public Iterator<Object> iterator() { return Arrays.<Object>asList("z", "a").iterator(); }
                }
                static class Child extends Custom {}
                static Object[] roots;
                public static void main(String[] args) {
                    TreeSet<String> sorted = new TreeSet<>(Comparator.reverseOrder());
                    sorted.addAll(List.of("a", "z"));
                    LinkedHashSet<Object> linked = new LinkedHashSet<>(Arrays.asList("z", null, "a"));
                    Map<String,Integer> map = new LinkedHashMap<>(); map.put("z", 1); map.put("a", 2);
                    roots = new Object[] {
                        new HashSet<>(List.of("a", "z")), linked, sorted,
                        EnumSet.allOf(Small.class), EnumSet.of(Large.E0, Large.E69),
                        Collections.emptySet(), Collections.singleton("a"), Set.of("a", "z"),
                        Collections.unmodifiableSet(linked), Collections.checkedSet(linked, Object.class),
                        Collections.synchronizedSet(linked), map.keySet(), sorted.headSet("a"),
                        new CopyOnWriteArraySet<>(List.of("z", "a")), new ConcurrentSkipListSet<>(List.of("z", "a")),
                        new Custom(), new Child(), new ArrayDeque<>(List.of("z", "a")),
                        Collections.unmodifiableSortedSet(sorted), Collections.unmodifiableNavigableSet(sorted)
                    };
                    System.out.println(roots.length);
                }
            }
            """.formatted(java.util.stream.IntStream.range(0, 70).mapToObj(i -> "E" + i)
                    .collect(java.util.stream.Collectors.joining(",")));

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void implementationsViewsAndInheritedAccessorsKeepElements(TraceFormat format) {
        var outcome = Tracer.trace(TraceRequest.builder(SOURCE).format(format).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        var heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
        var roots = rootElements(heap, format, 20);
        List<List<String>> expected = List.of(List.of("a", "z"), Arrays.asList("z", null, "a"),
                List.of("z", "a"), List.of("A", "B"), List.of("E0", "E69"), List.of(), List.of("a"),
                List.of("a", "z"), Arrays.asList("z", null, "a"), Arrays.asList("z", null, "a"),
                Arrays.asList("z", null, "a"), List.of("z", "a"), List.of("z"), List.of("z", "a"),
                List.of("a", "z"), List.of("z", "a"), List.of("z", "a"), List.of("z", "a"),
                List.of("z", "a"), List.of("z", "a"));
        for (int i = 0; i < roots.size(); i++) {
            var object = heap.get(ref(roots.get(i), format));
            var actual = elements(object, format, true).stream().map(value -> display(heap, value, format)).toList();
            if (i == 0 || i == 7) assertThat(actual).containsExactlyInAnyOrderElementsOf(expected.get(i));
            else assertThat(actual).as("root %s", i).containsExactlyElementsOf(expected.get(i));
        }
        if (format == TraceFormat.MODERN) {
            assertThat(heap.getAsJsonObject(ref(roots.get(8), format)).get("type").getAsString())
                    .startsWith("java.util.Collections$UnmodifiableSet");
        }
        assertThat(ref(roots.get(8), format)).isNotEqualTo(ref(roots.get(1), format));
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void accessorOnTheEntryClassDoesNotReenterMethodExitCapture(TraceFormat format) {
        String source = """
                public class Main extends java.util.HashSet<Object> {
                    static Main value;
                    public Object[] toArray() { return new Object[] { "element" }; }
                    public static void main(String[] args) {
                        value = new Main();
                        value.add("element");
                        System.out.println("ready");
                    }
                }
                """;
        for (boolean chronological : List.of(false, true)) {
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(chronological).build());
            assertThat(outcome.complete()).as(outcome.json()).isTrue();
            var heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
            assertThat(heap.entrySet().stream().map(Map.Entry::getValue).filter(value -> isSet(value, format)).toList())
                    .singleElement().satisfies(set -> assertThat(elements(set, format, true).stream()
                            .map(value -> display(heap, value, format)).toList()).containsExactly("element"));
        }
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void returnedArrayIsNotSortedDeduplicatedOrCompared(TraceFormat format) {
        String source = """
                import java.util.*;
                public class Main {
                    static class Item {
                        public int hashCode() { throw new AssertionError("hash"); }
                        public boolean equals(Object other) { throw new AssertionError("equals"); }
                    }
                    static class OddSet extends AbstractSet<Object> {
                        final Item item = new Item();
                        public int size() { throw new AssertionError("size"); }
                        public Iterator<Object> iterator() { throw new AssertionError("iterator"); }
                        public Object[] toArray() { return new Object[] { item, null, item }; }
                    }
                    static Object value;
                    public static void main(String[] args) {
                        value = new OddSet();
                        System.out.println("ready");
                    }
                }
                """;
        var outcome = Tracer.trace(TraceRequest.builder(source).format(format).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        var heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
        var sets = heap.entrySet().stream().map(Map.Entry::getValue).filter(value -> isSet(value, format)).toList();
        assertThat(sets).singleElement().satisfies(set -> {
            var contents = elements(set, format, true);
            assertThat(contents).hasSize(3);
            assertThat(contents.get(1).isJsonNull()).isTrue();
            assertThat(ref(contents.get(0), format)).isEqualTo(ref(contents.get(2), format));
        });
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void wrappersRetainIdentityAndSnapshotsDetachMembershipAndMutableElements(TraceFormat format) {
        String source = """
                import java.util.*;
                public class Main {
                    static class Item { int value = 1; }
                    static Object[] roots;
                    public static void main(String[] args) {
                        Item item = new Item();
                        Set<Object> backing = new LinkedHashSet<>();
                        backing.add(item); backing.add(null);
                        Set<Object> view = Collections.unmodifiableSet(backing);
                        Set<Object> second = Collections.unmodifiableSet(backing);
                        roots = new Object[] { backing, view, view, second, Collections.unmodifiableSet(view), item };
                        System.out.println("before");
                        backing.add(Set.of("nested"));
                        item.value = 2;
                        System.out.println("after");
                    }
                }
                """;
        var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true).build());
        assertThat(outcome.complete()).as(outcome.json()).isTrue();
        var trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
        List<Integer> sizes = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        String identity = null;
        for (var step : trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace")) {
            var heap = step.getAsJsonObject().getAsJsonObject("heap");
            var roots = findRoots(heap, format, 6);
            if (roots.isEmpty()) continue;
            String wrapper = ref(roots.get(1), format);
            if (identity == null) identity = wrapper;
            assertThat(wrapper).isEqualTo(identity).isEqualTo(ref(roots.get(2), format))
                    .isEqualTo(ref(roots.get(4), format)).isNotEqualTo(ref(roots.get(0), format))
                    .isNotEqualTo(ref(roots.get(3), format));
            var contents = elements(heap.get(wrapper), format, true);
            assertThat(contents).containsExactlyElementsOf(elements(heap.get(ref(roots.get(0), format)), format, true));
            assertThat(contents).containsExactlyElementsOf(elements(heap.get(ref(roots.get(3), format)), format, true));
            assertThat(ref(contents.getFirst(), format)).isEqualTo(ref(roots.get(5), format));
            assertThat(contents.get(1).isJsonNull()).isTrue();
            sizes.add(contents.size());
            values.add(fields(heap.get(ref(roots.get(5), format)), format).get("value").getAsInt());
        }
        assertThat(sizes).contains(2, 3);
        assertThat(values).contains(1, 2);
        assertThat(sizes.getFirst()).isEqualTo(2);
        assertThat(sizes.getLast()).isEqualTo(3);
        assertThat(values.getFirst()).isEqualTo(1);
        assertThat(values.getLast()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void nullAndThrowingAccessorsFallBackForTheInspectedObject(TraceFormat format) {
        for (String body : List.of("return null;", "throw new IllegalStateException();")) {
            String source = """
                    import java.util.*;
                    public class Main {
                        static class Broken extends AbstractSet<Object> {
                            int marker = 42;
                            public int size() { return 0; }
                            public Iterator<Object> iterator() { return Collections.emptyIterator(); }
                            public Object[] toArray() { %s }
                        }
                        static class BrokenList extends ArrayList<Object> {
                            int marker = 42;
                            public Object[] toArray() { %s }
                        }
                        static class BrokenQueue extends ArrayDeque<Object> {
                            int marker = 42;
                            public Object[] toArray() { %s }
                        }
                        static Object[] roots;
                        public static void main(String[] args) {
                            Broken set = new Broken();
                            roots = new Object[] { set, Collections.unmodifiableSet(set), new BrokenList(), new BrokenQueue() };
                            System.out.println(roots.length);
                        }
                    }
                    """.formatted(body, body, body);
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).build());
            assertThat(outcome.complete()).as(outcome.json()).isTrue();
            var heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
            var roots = rootElements(heap, format, 4);
            for (int i : List.of(0, 2, 3)) {
                assertThat(fields(heap.get(ref(roots.get(i), format)), format).get("marker").getAsInt()).isEqualTo(42);
            }
            assertThat(fields(heap.get(ref(roots.get(1), format)), format)).isEmpty();
        }
    }

    @Test
    void fieldsAndMultithreadNeverInvokeTheWrapperOrItsBacking() {
        String source = """
                import java.util.*;
                public class Main {
                    static class Blocked extends HashSet<Object> {
                        public Object[] toArray() { while (true) {} }
                    }
                    static Set<Object> backing;
                    static Set<Object> view;
                    public static void main(String[] args) {
                        backing = new Blocked();
                        view = Collections.unmodifiableSet(backing);
                        System.out.println("ready");
                    }
                }
                """;
        for (TraceFormat format : TraceFormat.values()) for (boolean multithread : List.of(false, true)) {
            if (multithread && format == TraceFormat.PYTUTOR) continue;
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).multithread(multithread)
                    .inspection(multithread ? InspectionPolicy.TRUSTED : InspectionPolicy.FIELDS)
                    .limits(TraceLimits.builder().timeoutMillis(10000).build()).build());
            assertThat(outcome.complete()).as(outcome.json()).isTrue();
            var heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
            assertThat(objects(heap, format, "java.util.Collections$UnmodifiableSet"))
                    .anySatisfy(fields -> assertThat(fields).containsKey("c"));
        }
    }

    @Test
    void reusedGuestsKeepWrapperContentsAndTypesJobLocal() throws Exception {
        StringBuilder input = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            JsonObject job = new JsonObject();
            job.addProperty("source", """
                    public class Main {
                        static Object value;
                        public static void main(String[] args) {
                            value = java.util.Collections.unmodifiableSet(java.util.Set.of(%s));
                            System.out.println("ready");
                        }
                    }
                    """.formatted(i % 2 == 0 ? "\"job" + i + "\"" : Integer.toString(i)));
            job.addProperty("format", i < 2 ? "modern" : "pytutor");
            input.append(job).append('\n');
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output);
        }
        var responses = output.toString().lines().toList();
        assertThat(responses).hasSize(4);
        for (int i = 0; i < 4; i++) {
            var result = JsonParser.parseString(responses.get(i)).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            var format = i < 2 ? TraceFormat.MODERN : TraceFormat.PYTUTOR;
            var heap = lastHeap(result.getAsJsonObject("trace"), format);
            var sets = heap.entrySet().stream().map(Map.Entry::getValue).filter(value -> isSet(value, format)).toList();
            assertThat(sets).isNotEmpty();
            for (var set : sets) assertThat(elements(set, format, true).stream()
                    .map(value -> display(heap, value, format)).toList())
                    .containsExactly(i % 2 == 0 ? "job" + i : Integer.toString(i));
        }
    }

    static boolean isSet(JsonElement value, TraceFormat format) {
        return format == TraceFormat.MODERN
                ? value.getAsJsonObject().get("kind").getAsString().equals("array")
                    && !value.getAsJsonObject().get("type").getAsString().endsWith("[]")
                : value.getAsJsonArray().get(0).getAsString().equals("SET");
    }

    static List<JsonElement> rootElements(JsonObject heap, TraceFormat format, int count) {
        var result = findRoots(heap, format, count);
        assertThat(result).hasSize(count);
        return result;
    }

    static List<JsonElement> findRoots(JsonObject heap, TraceFormat format, int count) {
        return heap.entrySet().stream().map(Map.Entry::getValue).filter(value -> format == TraceFormat.MODERN
                ? value.getAsJsonObject().get("kind").getAsString().equals("array")
                    && value.getAsJsonObject().get("type").getAsString().equals("java.lang.Object[]")
                    && value.getAsJsonObject().getAsJsonArray("elements").size() == count
                : value.getAsJsonArray().get(0).getAsString().equals("LIST") && value.getAsJsonArray().size() == count + 1)
                .map(value -> elements(value, format, false)).findFirst().orElse(List.of());
    }

    static List<JsonElement> elements(JsonElement value, TraceFormat format, boolean set) {
        if (format == TraceFormat.MODERN) {
            assertThat(value.getAsJsonObject().get("kind").getAsString()).isEqualTo("array");
            return value.getAsJsonObject().getAsJsonArray("elements").asList();
        }
        var array = value.getAsJsonArray();
        assertThat(array.get(0).getAsString()).isEqualTo(set ? "SET" : "LIST");
        return array.asList().subList(1, array.size());
    }

    static String display(JsonObject heap, JsonElement value, TraceFormat format) {
        if (value.isJsonNull()) return null;
        if (value.isJsonPrimitive()) return value.getAsString();
        var object = heap.get(ref(value, format));
        if (format == TraceFormat.MODERN) {
            var entry = object.getAsJsonObject();
            if (entry.has("value") && !entry.get("value").isJsonNull()) return entry.get("value").getAsString();
            return entry.get("type").getAsString().replaceFirst(".*[.$]", "");
        }
        var entry = object.getAsJsonArray();
        if (entry.get(0).getAsString().equals("STRING")) return entry.get(1).getAsString();
        if (entry.get(1).getAsString().equals("String")) return entry.get(2).getAsJsonArray().get(1).getAsString();
        if (entry.size() > 2 && entry.get(2).isJsonArray() && entry.get(2).getAsJsonArray().get(0).getAsString().equals("value"))
            return entry.get(2).getAsJsonArray().get(1).getAsString();
        return entry.get(1).getAsString().replaceFirst(".*[.$]", "");
    }
}
