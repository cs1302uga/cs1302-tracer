package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import cs1302.tracer.internal.App;
import cs1302.tracer.internal.CompilationHelper;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.serialize.ModernTraceSerializer;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GenericInvocationTest {
    private List<ExecutionSnapshot> trace(String source, int line) throws Exception {
        var unit = App.parseSource(source, Optional.empty());
        try (var compiled = CompilationHelper.compile(source)) {
            return DebugTraceHelper.trace(compiled, List.of(line), unit, "").get(line);
        }
    }

    @Test
    void boundedMaxResolvesEachInvocationInBothFormatsAndStyles() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        Integer[] items = {7, 10, 8};
                        System.out.println(max(items));
                        String[] words = {"pear", "apple", "plum"};
                        System.out.println(max(words));
                    }
                    public static <T extends Comparable<T>> T max(T[] items) {
                        T maxItem = items[0];
                        for (int i = 1; i < items.length; i++) {
                            T item = items[i];
                            int result = item.compareTo(maxItem);
                            if (result > 0) maxItem = item;
                        }
                        return maxItem;
                    }
                }
                """;
        var snapshots = trace(source, 12);
        assertThat(snapshots).hasSize(4);
        for (int i = 0; i < snapshots.size(); i++) {
            String type = i < 2 ? "java.lang.Integer" : "java.lang.String";
            var snapshot = snapshots.get(i);
            for (TypeStyle style : TypeStyle.values()) {
                var modern = new ModernTraceSerializer(false, false, false, style).createTrace(source, snapshot);
                var frame = modern.steps().getFirst().callStack().stream().filter(f -> f.methodName().equals("max"))
                        .findFirst().orElseThrow();
                assertThat(frame.locals()).filteredOn(v -> v.name().equals("items")).extracting(v -> v.type())
                        .containsExactly(style.format(type + "[]"));
                assertThat(frame.locals()).filteredOn(v -> v.name().equals("item") || v.name().equals("maxItem"))
                        .extracting(v -> v.type()).containsExactly(style.format(type), style.format(type));
                var pytutor = new PyTutorSerializer(false, false, false, style).createTrace(source, snapshot);
                var json = PyTutorSerializer.getGson().toJsonTree(pytutor).getAsJsonObject();
                var frames = json.getAsJsonArray("trace").get(0).getAsJsonObject().getAsJsonArray("stack_to_render");
                boolean found = false;
                for (var element : frames) {
                    var f = element.getAsJsonObject();
                    if (f.get("func_name").getAsString().startsWith("max:")) {
                        found = true;
                        var attrs = f.getAsJsonObject("locals_attrs");
                        assertThat(attrs.getAsJsonObject("items").get("type").getAsString())
                                .isEqualTo(style.format(type + "[]"));
                        assertThat(attrs.getAsJsonObject("item").get("type").getAsString())
                                .isEqualTo(style.format(type));
                        assertThat(attrs.getAsJsonObject("maxItem").get("type").getAsString())
                                .isEqualTo(style.format(type));
                    }
                }
                assertThat(found).isTrue();
            }
        }
    }

    private List<ExecutionSnapshot> capture(String source) throws Exception {
        int line = source.substring(0, source.indexOf("// capture")).split("\n", -1).length;
        return trace(source, line);
    }

    private List<String> types(List<ExecutionSnapshot> snapshots, String method, String variable) {
        return snapshots.stream().flatMap(s -> s.stack().stream()).filter(f -> f.methodName().equals(method))
                .flatMap(f -> f.visibleVariables().stream()).filter(f -> f.identifier().equals(variable))
                .map(f -> f.typeName()).toList();
    }

    @Test
    void explicitWiderTypesShadowClassBindingsAndSurviveNullLocals() throws Exception {
        String source = """
                public class Main<T> {
                    public static void main(String[] args) {
                        Main<String> receiver = new Main<>();
                        receiver.<Number>helper(Integer.valueOf(7));
                        receiver.<Number>helper(null);
                    }
                    public <T> void helper(T item) {
                        T local = null;
                        java.util.List<T[]> nested = null;
                        System.out.println(item); // capture
                    }
                }
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "helper", "item")).containsExactly("java.lang.Number", "java.lang.Number");
        assertThat(types(snapshots, "helper", "local")).containsExactly("java.lang.Number", "java.lang.Number");
        assertThat(types(snapshots, "helper", "nested")).containsExactly("java.util.List<java.lang.Number[]>",
                "java.util.List<java.lang.Number[]>");
        assertThat(snapshots.getFirst().heap().values()).anyMatch(v -> v.toString().contains("Integer"));
    }

    @Test
    void forwardingAndRecursiveFramesKeepIndependentBindings() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        Integer item = 3;
                        forward(item);
                    }
                    static <U> void forward(U item) {
                        Main.<U>recur(item, 1);
                    }
                    static <T> void recur(T item, int depth) {
                        if (depth > 0) Main.<String>recur("nested", 0);
                        System.out.println(item); // capture
                    }
                }
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "recur", "item")).contains("java.lang.Integer", "java.lang.String")
                .doesNotContain("T", "U");
        assertThat(types(snapshots, "forward", "item")).containsOnly("java.lang.Integer");
    }

    @Test
    void sameLineCallsAndUnsupportedInferenceRemainUnresolved() throws Exception {
        String source = """
                public class Main<T> {
                    public static void main(String[] args) {
                        Main<String> receiver = new Main<>();
                        receiver.<Integer>helper(1); receiver.<String>helper("two");
                        String value = receiver.helper(null);
                    }
                    <T> T helper(T item) {
                        System.out.println(item); // capture
                        return item;
                    }
                }
                """;
        assertThat(types(capture(source), "helper", "item")).containsExactly("T", "T", "T");
    }

    @Test
    void overloadDescriptorsDisambiguateCallsOnTheSameLine() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        Main.<Number>helper(1); helper("unrelated");
                    }
                    static <T> void helper(T item) {
                        T local = item;
                        System.out.println(local); // capture
                    }
                    static void helper(String item) { System.out.println(item); }
                }
                """;
        assertThat(types(capture(source), "helper", "local")).containsExactly("java.lang.Number");
    }

    @Test
    void parameterizedExplicitBindingsSubstituteInsideArrays() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        Main.<java.util.List<String>>helper(null);
                    }
                    static <T> void helper(T item) {
                        T[] array = null;
                        System.out.println(array); // capture
                    }
                }
                """;
        assertThat(types(capture(source), "helper", "array")).containsExactly("java.util.List<java.lang.String>[]");
    }

    @Test
    void constructorCallersUseDescriptorsAndSuspendedSourceLines() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        new Main();
                        new Main(1);
                    }
                    Main() {
                        Main.<Number>helper(1); helper("overload");
                        Main.<CharSequence>helper(new StringBuilder());
                    }
                    Main(int ignored) {
                        Main.<Integer>helper(1);
                    }
                    static <T> void helper(T item) {
                        T local = null;
                        System.out.println(item); // capture
                    }
                    static void helper(String item) {}
                }
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "helper", "item")).containsExactly("java.lang.Number", "java.lang.CharSequence",
                "java.lang.Integer");
        assertThat(types(snapshots, "helper", "local")).containsExactly("java.lang.Number", "java.lang.CharSequence",
                "java.lang.Integer");
    }

    @Test
    void shadowedClassFieldsAndMethodArgumentsKeepTheirDeclaringIdentity() throws Exception {
        String source = """
                public class Main<T> {
                    T value;
                    java.util.List<T[]> values;
                    public static void main(String[] args) {
                        Main<String> receiver = new Main<>();
                        receiver.<Number>forward(1);
                    }
                    <T> void forward(T item) {
                        consume(this.value);
                        consume(item);
                        consume(this.values);
                    }
                    <U> void consume(U value) {
                        U local = null;
                        System.out.println(value); // capture
                    }
                }
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "consume", "value")).containsExactly("java.lang.String", "java.lang.Number",
                "java.util.List<java.lang.String[]>");
        assertThat(types(snapshots, "consume", "local")).containsExactly("java.lang.String", "java.lang.Number",
                "java.util.List<java.lang.String[]>");
    }

    @Test
    void concreteDefaultPackageAndPrimitiveArrayBindingsArePreserved() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        Main.<Person>helper(null);
                        Main.<int[]>helper(null);
                        Main.<java.util.List<int[]>>helper(null);
                        int[] numbers = {1};
                        helper(numbers);
                        int[][] nested = {{1}};
                        helper(nested);
                    }
                    static <T> void helper(T item) {
                        T local = null;
                        System.out.println(item); // capture
                    }
                }
                class Person {}
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "helper", "item")).containsExactly("Person", "int[]", "java.util.List<int[]>",
                "int[]", "int[][]");
        assertThat(types(snapshots, "helper", "local")).containsExactly("Person", "int[]", "java.util.List<int[]>",
                "int[]", "int[][]");
    }

    @Test
    void concurrentThreadsKeepDeclaredTypesIndependent() throws Exception {
        verifyThreadBindings(false);
    }

    @Test
    void concurrentThreadsKeepExpressionTypesIndependent() throws Exception {
        verifyThreadBindings(true);
    }

    private void verifyThreadBindings(boolean expressions) throws Exception {
        String source = """
                public class Main {
                    static class Numbers extends Thread {
                        public void run() {
                            Number value = Integer.valueOf(7);
                            helper(value);
                        }
                    }
                    static class Words extends Thread {
                        public void run() {
                            String value = "word";
                            helper(value);
                        }
                    }
                    static <T> void helper(T item) {
                        T local = item;
                        System.out.println(local);
                    }
                    public static void main(String[] args) throws Exception {
                        Thread numbers = new Numbers();
                        Thread words = new Words();
                        numbers.start();
                        words.start();
                        numbers.join();
                        words.join();
                    }
                }
                """;
        if (expressions) {
            source = source
                    .replace("Number value = Integer.valueOf(7);\n            helper(value);", "helper(number());")
                    .replace("String value = \"word\";\n            helper(value);", "helper(\"word\");")
                    .replace("static <T> void helper",
                            "static Number number() { return 7; }\n    static <T> void helper");
            assertThat(source).doesNotContain("helper(value)");
        }
        try (var session = new cs1302.tracer.internal.execution.TraceSession(
                cs1302.tracer.api.TraceLimits.instructorDefaults(),
                cs1302.tracer.api.InspectionPolicy.FIELDS, true);
                var compiled = CompilationHelper.compile(source)) {
            session.enableMultithread();
            var snapshots = DebugTraceHelper.traceChronological(compiled,
                    DebugTraceHelper.getValidBreakpointLines(compiled),
                    List.of(App.parseSource(source, Optional.empty())), true);
            var labels = snapshots.stream().flatMap(s -> s.threads().stream()).flatMap(t -> t.stack().stream())
                    .filter(f -> f.methodName().equals("helper")).flatMap(f -> f.visibleVariables().stream())
                    .filter(f -> f.identifier().equals("item")).map(f -> f.typeName()).toList();
            assertThat(labels).contains("java.lang.Number", "java.lang.String").doesNotContain("T",
                    "java.lang.Integer");
        }
    }

    @Test
    void absentSourceResolutionPreservesUnresolvedTypes() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) { Main.<String>helper("text"); }
                    static <T> void helper(T item) {
                        System.out.println(item);
                    }
                }
                """;
        try (var compiled = CompilationHelper.compile(source)) {
            var snapshots = DebugTraceHelper
                    .trace(compiled, List.of(4), com.github.javaparser.StaticJavaParser.parse(source), "").get(4);
            assertThat(types(snapshots, "helper", "item")).containsExactly("T");
        }
    }

    @Test
    void persistentBatchJobsDoNotReuseMethodBindings() throws Exception {
        verifyBatchBindings(false);
    }

    @Test
    void persistentBatchJobsDoNotReuseExpressionBindings() throws Exception {
        verifyBatchBindings(true);
    }

    private void verifyBatchBindings(boolean expressions) throws Exception {
        var requests = new StringBuilder();
        for (String type : List.of("Number", "String")) {
            String source = """
                    public class Main {
                        public static void main(String[] args) {
                            Main.<%s>helper(null);
                        }
                        static <T> void helper(T item) {
                            System.out.println(item);
                        }
                    }
                    """.formatted(type);
            if (expressions) {
                source = source
                        .replace("Main.<" + type + ">helper(null);",
                                type.equals("Number") ? "helper(number());" : "helper(\"word\");")
                        .replace("static <T> void helper",
                                "static Number number() { return 7; }\n    static <T> void helper");
            }
            var request = new com.google.gson.JsonObject();
            request.addProperty("id", type);
            request.addProperty("source", source);
            request.addProperty("allBreakpoints", true);
            request.addProperty("format", "modern");
            requests.append(request).append('\n');
        }
        var output = new java.io.StringWriter();
        try (var service = new cs1302.tracer.internal.batch.BatchTraceService(1, 10)) {
            service.processStream(new java.io.ByteArrayInputStream(
                    requests.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), output);
        }
        var responses = output.toString().strip().split("\\R");
        assertThat(responses).hasSize(2);
        for (int i = 0; i < responses.length; i++) {
            var result = com.google.gson.JsonParser.parseString(responses[i]).getAsJsonObject()
                    .getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).isTrue();
            var types = new java.util.ArrayList<String>();
            for (var step : result.getAsJsonObject("trace").getAsJsonArray("steps")) {
                for (var frame : step.getAsJsonObject().getAsJsonArray("callStack")) {
                    for (var local : frame.getAsJsonObject().getAsJsonArray("locals")) {
                        var variable = local.getAsJsonObject();
                        if (variable.get("name").getAsString().equals("item")) {
                            types.add(variable.get("type").getAsString());
                        }
                    }
                }
            }
            assertThat(types).isNotEmpty().containsOnly(i == 0 ? "java.lang.Number" : "java.lang.String");
        }
    }

    @Test
    void expressionBindingsSurviveForwardingAndRecursiveFrames() throws Exception {
        String source = """
                public class Main {
                    static boolean nested;
                    static class Person {}
                    public static void main(String[] args) { forward("root"); }
                    static <U> void forward(U item) { recur(item); }
                    static <T> void recur(T item) {
                        if (!nested) { nested = true; recur(new Person()); }
                        T local = null;
                        System.out.println(local); // capture
                    }
                }
                """;
        var snapshots = capture(source);
        assertThat(types(snapshots, "recur", "local")).contains("java.lang.String", "Main.Person").doesNotContain("T",
                "U");
        assertThat(types(snapshots, "forward", "item")).containsOnly("java.lang.String");
    }
}
