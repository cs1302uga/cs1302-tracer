package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.App;
import cs1302.tracer.CompilationHelper;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Compiler-backed tests of real frame extraction, including deliberate fallbacks. */
class SimpleExpressionInferenceTest {
    record Example(String name, String generics, String members, String setup,
            String expression, String oracle, String label) {
        String source() {
            return """
                    import java.util.*;
                    public class Main {
                        %s
                        static <%s> T helper(T item) {
                            T local = null;
                            System.out.println(local); // capture
                            return local;
                        }
                        public static void main(String[] args) {
                            %s
                            helper(%s);
                        }
                    }
                    """.formatted(members, generics, setup, expression);
        }
    }

    @TestFactory
    Stream<DynamicTest> expressions() {
        return List.of(
                new Example("deep receiver chain deferred", "T", "", "", "\"a\"" + ".trim()".repeat(34), "java.lang.String", "T"),
                new Example("reference array clone deferred", "T", "", "String[] a = {};", "a.clone()", "java.lang.String[]", "T"),
                new Example("primitive array clone deferred", "T", "", "int[] a = {};", "a.clone()", "int[]", "T"),
                new Example("local class deferred", "T", "", "class Local {}", "new Local()", "Local", "T"),
                new Example("raw receiver fixed result deferred", "T", "static class Box<U> { List<String> value() { return null; } }", "Box b = new Box();", "b.value()", "java.util.List", "T"),
                new Example("raw inherited receiver deferred", "T", "static class Box<U> { List<String> value() { return null; } } static class Child extends Box {}", "Child b = new Child();", "b.value()", "java.util.List", "T"),
                new Example("parameterized receiver fixed result", "T", "static class Box<U> { List<String> value() { return null; } }", "Box<Integer> b = new Box<>();", "b.value()", "java.util.List<java.lang.String>", "java.util.List<java.lang.String>"),
                new Example("parameterized inherited receiver fixed result", "T", "static class Box<U> { List<String> value() { return null; } } static class Child extends Box<Integer> {}", "Child b = new Child();", "b.value()", "java.util.List<java.lang.String>", "java.util.List<java.lang.String>"),
                new Example("literal", "T", "", "", "\"hello\"", "java.lang.String", "java.lang.String"),
                new Example("explicit construction", "T", "", "", "new String(\"hello\")", "java.lang.String", "java.lang.String"),
                new Example("explicit generic construction", "T", "", "", "new ArrayList<String>()", "java.util.ArrayList<java.lang.String>", "java.util.ArrayList<java.lang.String>"),
                new Example("overloaded factory", "T", "static String make(int x) { return \"a\"; } static Number make(Object x) { return 1; }", "", "make(1)", "java.lang.String", "java.lang.String"),
                new Example("wider factory result", "T", "static Number make() { return 1; }", "", "make()", "java.lang.Number", "java.lang.Number"),
                new Example("instance method", "T", "", "String s = \"a\";", "s.trim()", "java.lang.String", "java.lang.String"),
                new Example("nested fixed results", "T", "", "", "new String(\"a\").trim().toString()", "java.lang.String", "java.lang.String"),
                new Example("primitive array result", "T", "static int[] make() { return new int[0]; }", "", "make()", "int[]", "int[]"),
                new Example("nested generic call deferred", "T", "", "", "Collections.emptyList()", "java.util.List<java.lang.Object>", "T"),
                new Example("generic receiver deferred", "T", "static class Box<U> { U value() { return null; } }", "Box<String> b = new Box<>();", "b.value()", "java.lang.String", "T"),
                new Example("generic owner deferred", "T", "static class Outer<U> { class Inner {} } static Outer<String>.Inner make() { return new Outer<String>().new Inner(); }", "", "make()", "Main.Outer<java.lang.String>.Inner", "T"),
                new Example("generic scope deferred", "T", "static <U> List<U> values() { return Collections.singletonList(null); }", "", "values().get(0)", "java.lang.Object", "T"),
                new Example("diamond deferred", "T", "", "", "new ArrayList<>()", "java.util.ArrayList<java.lang.Object>", "T"),
                new Example("raw construction deferred", "T", "", "", "new ArrayList()", "java.util.ArrayList", "T"),
                new Example("anonymous construction deferred", "T", "", "", "new Object() {}", "<anonymous java.lang.Object>", "T"),
                new Example("bounded literal deferred", "T extends CharSequence", "", "", "\"a\"", "java.lang.String", "T"),
                new Example("multiple type parameters deferred", "T, U", "", "", "\"a\"", "java.lang.String", "T"),
                new Example("boxing deferred", "T", "", "", "1", "java.lang.Integer", "T"),
                new Example("null deferred", "T", "", "", "null", "java.lang.Object", "T"),
                new Example("poly constructor argument deferred", "T", "", "", "new ArrayList<String>(Collections.emptyList())", "java.util.ArrayList<java.lang.String>", "T"),
                new Example("poly factory argument deferred", "T", "static String make(List<String> x) { return \"a\"; }", "", "make(Collections.emptyList())", "java.lang.String", "T")
        ).stream().map(example -> DynamicTest.dynamicTest(example.name(), () -> {
            String source = example.source();
            assertThat(GenericInferenceAssessmentTest.oracle(source)).isEqualTo(example.oracle());
            int line = source.substring(0, source.indexOf("// capture")).split("\n", -1).length;
            try (var session = new cs1302.tracer.execution.TraceSession(
                        cs1302.tracer.execution.TraceLimits.instructorDefaults(),
                        cs1302.tracer.execution.InspectionPolicy.FIELDS, true);
                    var compiled = CompilationHelper.compile(source)) {
                assertThat(session).isNotNull();
                var snapshots = DebugTraceHelper.trace(compiled, List.of(line),
                        App.parseSource(source, Optional.empty()), "").get(line);
                assertThat(snapshots).hasSize(1);
                var snapshot = snapshots.getFirst();
                GenericInferenceAssessmentTest.verifyFormats(source, snapshot, example.label());
                var frame = snapshot.stack().stream().filter(f -> f.methodName().equals("helper"))
                        .findFirst().orElseThrow();
                assertThat(frame.visibleVariables()).filteredOn(v -> v.identifier().equals("item"))
                        .extracting(v -> v.typeName()).containsExactly(example.label());
            }
        }));
    }
}
