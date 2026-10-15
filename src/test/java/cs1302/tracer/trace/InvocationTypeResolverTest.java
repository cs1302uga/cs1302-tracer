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
        for (String statement : List.of("helper(null);", "helper(1, 2);", "helper(new Object() {});")) {
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
        String key = unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).get(1)
                .resolve().getTypeParameters().getFirst().getQualifiedName();
        assertThat(resolver.invocationTypes().invocationBindings(caller, callee, Map.of(key, "U"))).isEmpty();
        assertThat(resolver.invocationTypes().invocationBindings(caller, callee, Map.of(key, "java.lang.Number")))
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
        String key = call.getTypeArguments().orElseThrow().get(0).resolve()
                .asTypeVariable().asTypeParameter().getQualifiedName();
        assertThat((Map<?, ?>) infer.invoke(null, call, Map.of(key, ""))).isEmpty();
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

    @Test
    void contextsAreReusedAcrossLocalsAndRebuiltOnlyForChangedReceivers() {
        var resolver = resolver("""
                class Main<T> {
                    T field;
                    <U> void helper(U item) {
                        this.<U>helper(item);
                    }
                    <T> void forward(T item) {
                        helper(this.field);
                    }
                }
                """);
        var receiver = mirror(ObjectReference.class, Map.of("uniqueID", 1L));
        var frames = new ArrayList<StackFrame>();
        for (int i = 0; i < 30; i++) {
            frames.add(frame(method("Main", "helper", "(Ljava/lang/Object;)V"),
                    4, Map.of("thisObject", receiver)));
        }
        frames.add(frame(method("Main", "forward", "(Ljava/lang/Object;)V"),
                7, Map.of("thisObject", receiver)));
        var objectTypes = new HashMap<Long, String>(Map.of(1L, "Main<java.lang.String>"));
        var contexts = resolver.invocationTypes().new FrameContexts(frames, resolver, objectTypes);
        var bindings = contexts.bindings(frames.getFirst());
        assertThat(bindings).containsEntry("U", "java.lang.String");
        for (StackFrame frame : frames) {
            var cached = contexts.bindings(frame);
            for (int local = 0; local < 20; local++) {
                assertThat(contexts.bindings(frame)).isSameAs(cached);
            }
        }
        objectTypes.put(2L, "Main<java.lang.Number>");
        contexts.receiverTypeChanged(2L);
        assertThat(contexts.bindings(frames.getFirst())).isSameAs(bindings);
        contexts.receiverTypeChanged(1L);
        assertThat(contexts.bindings(frames.getFirst())).isSameAs(bindings);
        objectTypes.put(1L, "Main<java.lang.Number>");
        contexts.receiverTypeChanged(1L);
        assertThat(contexts.bindings(frames.getFirst()))
                .isNotSameAs(bindings).containsEntry("U", "java.lang.Number");
        var separate = resolver.invocationTypes().new FrameContexts(frames, resolver,
                Map.of(1L, "Main<java.lang.String>"));
        assertThat(separate.bindings(frames.getFirst()))
                .isNotSameAs(bindings).containsEntry("U", "java.lang.String");
    }

    @Test
    void barePrimitivesAreNotConcreteMethodBindings() {
        var resolver = resolver("""
                class Main {
                    static <T> void helper(T item) {}
                    void run() { int item = 1; helper(item); }
                }
                """);
        assertThat(resolver.invocationTypes().invocationBindings(
                frame(method("Main", "run", "()V"), 3, Map.of()),
                method("Main", "helper", "(Ljava/lang/Object;)V"), Map.of()))
                .doesNotContainValue("int");
    }
    @Test
    void expressionInferenceRequiresRetainedSolverMetadata() {
        var unit = App.parseSource("class Main { static <T> void helper(T x) {}\n"
                + "static void main(String[] args) { helper(\"word\"); } }", Optional.empty());
        var resolver = new AstTypeResolver(unit).invocationTypes();
        var caller = frame(method("Main", "main", "([Ljava/lang/String;)V"), 2, Map.of());
        var callee = method("Main", "helper", "(Ljava/lang/Object;)V");
        assertThat(resolver.invocationBindings(caller, callee, Map.of()))
                .containsExactlyEntriesOf(Map.of("T", "java.lang.String"));
        unit.removeData(AstTypeResolver.TYPE_SOLVER);
        assertThat(resolver.invocationBindings(caller, callee, Map.of())).isEmpty();
    }

    @Test
    void lostSolverDuringFrameExtractionRetainsLexicalBindings() {
        var unit = App.parseSource("class Main { <T> void helper(T item) {} }", Optional.empty());
        var resolver = new AstTypeResolver(unit);
        var callee = method("Main", "helper", "(Ljava/lang/Object;)V");
        assertThat(resolver.invocationTypes().declaration(callee)).isPresent();
        unit.removeData(com.github.javaparser.ast.Node.SYMBOL_RESOLVER_KEY);
        var target = frame(callee, 1, Map.of());
        var contexts = resolver.invocationTypes().new FrameContexts(List.of(target), resolver, Map.of());
        assertThat(contexts.bindings(target)).containsExactlyEntriesOf(Map.of("T", "T"));
    }

}
