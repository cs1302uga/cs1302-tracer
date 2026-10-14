package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.*;
import cs1302.tracer.App;
import java.util.*;
import org.junit.jupiter.api.Test;

class InvocationTypeResolverTest {
    private Method method(String owner, String name, String descriptor) {
        return mirror(Method.class, Map.of("name", name, "signature", descriptor,
                "declaringType", mirror(ReferenceType.class, Map.of("name", owner))));
    }

    private StackFrame frame(Method method, int line, Map<String, Object> extras) {
        var properties = new HashMap<String, Object>(extras);
        properties.put("location", mirror(Location.class, Map.of("method", method, "lineNumber", line)));
        return mirror(StackFrame.class, properties);
    }

    private AstTypeResolver resolver(String source) {
        return new AstTypeResolver(App.parseSource(source, Optional.empty()));
    }

    @Test
    void duplicateSourceMethodsAndAbsentDebugMethodsAreNotAttributed() {
        var unit = App.parseSource("class Main { static <T> void helper(T x) {} }", Optional.empty());
        var duplicate = new InvocationTypeResolver(Arrays.asList(null, unit, unit));
        assertThat(duplicate.declaration(method("Main", "helper", "(Ljava/lang/Object;)V"))).isEmpty();
        var resolver = resolver("class Main { void run() {} }");
        assertThat(resolver.invocationTypes().declaration(method("Main", "missing", "()V"))).isEmpty();
        assertThat(resolver.invocationTypes().invocationBindings(
                frame(method("External", "run", "()V"), 1, Map.of()),
                method("Main", "helper", "(Ljava/lang/Object;)V"), Map.of())).isEmpty();
    }

    @Test
    void unavailableOrChangedFramesRetainLexicalShadowing() {
        var resolver = resolver("class Main<T> { <T> void helper(T item) {} }");
        var targetMethod = method("Main", "helper", "(Ljava/lang/Object;)V");
        var receiver = mirror(ObjectReference.class, Map.of("uniqueID", 1L));
        for (Object frames : List.of(new IncompatibleThreadStateException(), List.of())) {
            var thread = mirror(ThreadReference.class, Map.of("frames", frames));
            var target = frame(targetMethod, 1, Map.of("thread", thread, "thisObject", receiver));
            assertThat(resolver.invocationTypes().bindings(target, resolver,
                    Map.of(1L, "Main<java.lang.String>"))).containsEntry("T", "T");
        }
        var props = new HashMap<String, Object>();
        var thread = mirror(ThreadReference.class, props);
        var target = frame(targetMethod, 1, Map.of("thread", thread));
        props.put("frames", List.of(target));
        assertThat(resolver.invocationTypes().bindings(target, resolver, Map.of())).containsEntry("T", "T");
    }

    @Test
    void unsupportedCallFormsDoNotGuessBindings() {
        for (String statement : List.of("helper(null);", "helper(1, 2);", "helper(new Object());")) {
            String declaration = statement.contains("1, 2")
                    ? "static <T> void helper(T x, T y) {}" : "static <T> void helper(T x) {}";
            var resolver = resolver("class Main { " + declaration + "\n void run() { " + statement + " } }");
            String descriptor = statement.contains("1, 2")
                    ? "(Ljava/lang/Object;Ljava/lang/Object;)V" : "(Ljava/lang/Object;)V";
            assertThat(resolver.invocationTypes().invocationBindings(
                    frame(method("Main", "run", "()V"), 2, Map.of()),
                    method("Main", "helper", descriptor), Map.of())).isEmpty();
        }
        var resolver = resolver("class Main { static <T> void helper(T... items) {}\n void run() { String[] a = {}; helper(a); } }");
        assertThat(resolver.invocationTypes().invocationBindings(
                frame(method("Main", "run", "()V"), 2, Map.of()),
                method("Main", "helper", "([Ljava/lang/Object;)V"), Map.of())).isEmpty();
    }

    @Test
    void unknownCallerVariablesAndMissingSolverStayUnresolved() {
        var unit = App.parseSource("class Main { static <T> void helper(T x) {}\n <U> void run(U item) { helper(item); } }", Optional.empty());
        var resolver = new AstTypeResolver(unit);
        var caller = frame(method("Main", "run", "(Ljava/lang/Object;)V"), 2, Map.of());
        var callee = method("Main", "helper", "(Ljava/lang/Object;)V");
        assertThat(resolver.invocationTypes().invocationBindings(caller, callee, Map.of("U", "U"))).isEmpty();
        assertThat(resolver.invocationTypes().invocationBindings(caller, callee, Map.of("U", "java.lang.Number")))
                .containsEntry("T", "java.lang.Number");
        unit.removeData(AstTypeResolver.TYPE_SOLVER);
        assertThat(resolver.invocationTypes().invocationBindings(caller, callee, Map.of())).isEmpty();
    }

    @Test
    void sourceLookupRespectsOverloadsAndBlockScope() {
        var resolver = resolver("""
                class Main {
                    <T> void helper(T item) {
                        { String local = "hidden"; }
                        T local = item;
                        System.out.println(local);
                        Object later = null;
                    }
                    void helper(String item) { int local = 1; }
                    void varargs(String... items) {}
                }
                """);
        var frame = frame(method("Main", "helper", "(Ljava/lang/Object;)V"), 5, Map.of());
        assertThat(resolver.resolveFrameVariableType(frame, "local")).contains("T");
        assertThat(resolver.resolveFrameVariableType(frame, "item")).contains("T");
        assertThat(resolver.resolveFrameVariableType(frame, "later")).isEmpty();
        assertThat(resolver.resolveFrameVariableType(frame, "absent")).isEmpty();
        assertThat(resolver.resolveFrameVariableType(
                frame(method("Main", "varargs", "([Ljava/lang/String;)V"), 9, Map.of()), "items"))
                .contains("java.lang.String[]");
    }

    @Test
    void fieldsCastsAndArrayAccessesUseDeclaredTypes() {
        for (String argument : List.of("this.value", "(Number) value", "values[0]")) {
            var resolver = resolver("class Main { Number value; Number[] values; static <T> void helper(T x) {}\n void run() { helper(" + argument + "); } }");
            assertThat(resolver.invocationTypes().invocationBindings(
                    frame(method("Main", "run", "()V"), 2, Map.of()),
                    method("Main", "helper", "(Ljava/lang/Object;)V"), Map.of()))
                    .containsEntry("T", "java.lang.Number");
        }
    }

    @Test
    void externalAndUnresolvableCallsCannotSupplyBindings() {
        var resolver = resolver("class Main { static <T> void helper(T x) {}\n void run() { Main.<String>helper(null); } }");
        assertThat(resolver.invocationTypes().invocationBindings(
                frame(method("Main", "run", "()V"), 2, Map.of()),
                method("Other", "helper", "(Ljava/lang/Object;)V"), Map.of())).isEmpty();
        resolver = resolver("class Main { void run() { Missing.helper(null); } }");
        assertThat(resolver.invocationTypes().invocationBindings(
                frame(method("Main", "run", "()V"), 1, Map.of()),
                method("Main", "helper", "(Ljava/lang/Object;)V"), Map.of())).isEmpty();
    }

    @Test
    void sourceGapsInCallerStackDoNotInheritUnrelatedBindings() {
        var resolver = resolver("class Main { void run() {} <T> void helper(T item) {} }");
        var props = new HashMap<String, Object>();
        var thread = mirror(ThreadReference.class, props);
        var target = frame(method("Main", "helper", "(Ljava/lang/Object;)V"), 1, Map.of("thread", thread));
        var external = frame(method("External", "invoke", "()V"), 1, Map.of());
        var caller = frame(method("Main", "run", "()V"), 1, Map.of());
        props.put("frames", List.of(target, caller, external));
        assertThat(resolver.invocationTypes().bindings(target, resolver, Map.of())).containsEntry("T", "T");
    }

    @Test
    void incompleteInferenceMetadataFailsClosed() throws Exception {
        var infer = InvocationTypeResolver.class.getDeclaredMethod("infer",
                com.github.javaparser.ast.expr.MethodCallExpr.class, Map.class);
        infer.setAccessible(true);
        for (String source : List.of(
                "class Main { static <T> void helper(T x) {} void run() { Main.<String, Integer>helper(null); } }",
                "class Main { static <T> void helper(T x) {} void run() { Main.<Missing>helper(null); } }",
                "class Main { static <T> void helper(T... x) {} void run() { helper(); } }",
                "class Main { static <T> void helper(String x) {} void run() { String a = null; helper(a); } }")) {
            var unit = App.parseSource(source, Optional.empty());
            var call = unit.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
            assertThat((Map<?, ?>) infer.invoke(null, call, Map.of())).isEmpty();
        }
        var unit = App.parseSource("class Main { static <T> void helper(T x) {} <U> void run(U x) { Main.<U>helper(x); } }", Optional.empty());
        var call = unit.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        assertThat((Map<?, ?>) infer.invoke(null, call, Map.of("U", ""))).isEmpty();
        unit = App.parseSource("class Main { static <T> void helper(T x) {} <U> void run() { Main.<java.util.List<U>>helper(null); } }", Optional.empty());
        call = unit.findFirst(com.github.javaparser.ast.expr.MethodCallExpr.class).orElseThrow();
        assertThat((Map<?, ?>) infer.invoke(null, call, Map.of())).isEmpty();
    }

    @Test
    void nestedMethodsFieldsAndLoopScopesDoNotPolluteLocals() {
        var resolver = resolver("""
                class Main {
                    <T> void helper(T item) {
                        class Nested {
                            String local;
                            void other() { String local = "hidden"; }
                        }
                        for (T local : java.util.List.of(item)) {
                            System.out.println(local);
                        }
                    }
                }
                """);
        var frame = frame(method("Main", "helper", "(Ljava/lang/Object;)V"), 8, Map.of());
        assertThat(resolver.resolveFrameVariableType(frame, "local")).contains("T");
        assertThat(resolver.resolveFrameVariableType(
                frame(method("Main", "helper", "(Ljava/lang/Object;)V"), 10, Map.of()), "local")).isEmpty();
    }
}
