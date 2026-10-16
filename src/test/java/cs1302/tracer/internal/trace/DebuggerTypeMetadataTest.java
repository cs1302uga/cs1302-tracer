package cs1302.tracer.internal.trace;

import static cs1302.tracer.internal.trace.DebuggerEventTest.captureCall;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import com.sun.jdi.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.LambdaExpr;
import java.util.*;
import org.junit.jupiter.api.Test;

class DebuggerTypeMetadataTest {
    private static ReifiedTypeMetadata metadata(AstTypeResolver resolver, Map<Long, String> initial) {
        var metadata = new ReifiedTypeMetadata(resolver);
        initial.forEach(metadata::recordFallback);
        return metadata;
    }

    private static CompilationUnit parse(String source) {
        var parser = new JavaParser(
                new ParserConfiguration().setSymbolResolver(new com.github.javaparser.symbolsolver.JavaSymbolSolver(
                        new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver())));
        return parser.parse(source).getResult().orElseThrow();
    }

    @Test
    void lambdaReconstructionHandlesVoidExpressionsInferredParametersAndNonFunctionalTargets() throws Exception {
        var unit = parse("""
                interface Empty {}
                class C {
                    void m() {
                        Runnable action = () -> System.out.println();
                        java.util.function.Function<String,Integer> size = text -> text.length();
                        Empty invalid = () -> {};
                    }
                }
                """);
        var observations = SourceAnalysis.from(List.of(unit)).observations("C", "m", List.of(), 20);
        assertThat(observations.lambdaImplementation("action")).get().asString()
                .contains("void run()", "System.out.println()").doesNotContain("return");
        assertThat(observations.lambdaImplementation("size")).get().asString()
                .contains("java.lang.String text", "return text.length()");
        assertThat(observations.lambdaImplementation("invalid")).isEmpty();
    }

    @Test
    void lambdaAssignmentsTrackFieldsAndIgnoreArrayTargets() throws Exception {
        var unit = parse("""
                class C {
                    Runnable action;
                    void m() {
                        this.action = () -> {};
                        Runnable[] actions = new Runnable[1];
                        actions[0] = () -> {};
                        Runnable local;
                        local = () -> {};
                    }
                }
                """);
        var observations = SourceAnalysis.from(Arrays.asList(null, unit)).observations("C", "m", List.of(), 1);
        assertThat(observations.lambdaImplementation("action")).contains("void run() {\n}");
        assertThat(observations.lambdaImplementation("local")).contains("void run() {\n}");
        assertThat(observations.lambdaImplementation("actions")).isEmpty();
    }

    private static StackFrame frame(String methodName, ObjectReference self) {
        var method = mirror(com.sun.jdi.Method.class,
                Map.of("name", methodName, "argumentTypes", List.of(), "declaringType", mirror(ReferenceType.class, Map.of("name", "C"))));
        var location = mirror(Location.class, Map.of("method", method, "lineNumber", 1, "sourcePath", "C.java"));
        var values = new HashMap<String, Object>(Map.of("location", location, "visibleVariables", List.of()));
        if (self != null)
            values.put("thisObject", self);
        return mirror(StackFrame.class, values);
    }

    @Test
    void completeCaptureHandlesConstructorWithoutReceiverOrCaller() throws Exception {
        var source = SourceAnalysis.from(List.of(StaticJavaParser.parse("class C { void m() { C obj = new C(); } }")));
        for (var frames : List.of(List.of(frame("<init>", null)), List.of(frame("<init>", null), frame("m", null)))) {
            var snapshot = capture(source, frames);
            assertThat(snapshot.heap()).isEmpty();
            assertThat(snapshot.stack()).hasSize(frames.size());
        }
    }

    @Test
    void completeCaptureRefinesUnknownLocalsAndKeepsMoreSpecificSharedEvidence() throws Exception {
        var source = SourceAnalysis.from(List.of(StaticJavaParser.parse("""
            class C {
                void concrete() { java.util.List<String> allocated = new java.util.List<String>() {}; }
                void wildcard() { java.util.List<?> allocated = new java.util.List<Object>() {}; }
            }
            """)));
        var object = object(1L, "java.util.ArrayList");
        var first = localFrame("concrete", 2, "unknown", object, null);
        var second = localFrame("wildcard", 3, "unknown", object, null);
        var snapshot = capture(source, List.of(first, second));
        assertThat(snapshot.heap()).hasSize(1);
        assertThat(((TraceValue.Object) snapshot.heap().get(1L)).classFqn()).isEqualTo("java.util.ArrayList<String>");
        assertThat(snapshot.stack()).allSatisfy(stack ->
            assertThat(stack.visibleVariables().getFirst().value()).isEqualTo(new TraceValue.Reference(1L)));
    }

    @Test
    void completeCapturePreservesDeclaredLocalWithUnresolvedReceiver() throws Exception {
        var source = SourceAnalysis.from(List.of(StaticJavaParser.parse(
            "class C<T> { void m() { java.util.List<String> local; } }")));
        var snapshot = capture(source, List.of(localFrame("m", 1, "local",
            object(1L, "java.util.ArrayList"), object(2L, "C"))));
        assertThat(((TraceValue.Object) snapshot.heap().get(1L)).classFqn()).isEqualTo("java.util.ArrayList<String>");
        assertThat(snapshot.heap()).containsKey(2L);
    }

    private static ObjectReference object(long id, String name) {
        var type = mirror(ReferenceType.class, Map.of("name", name, "allFields", List.of()));
        return mirror(ObjectReference.class, Map.of("referenceType", type, "type", type, "uniqueID", id));
    }

    private static StackFrame localFrame(String methodName, int line, String variableName, ObjectReference value,
            ObjectReference self) {
        var method = mirror(com.sun.jdi.Method.class, Map.of("name", methodName, "argumentTypes", List.of(),
            "declaringType", mirror(ReferenceType.class, Map.of("name", "C"))));
        var location = mirror(Location.class, Map.of("method", method, "lineNumber", line,
            "sourcePath", "C.java"));
        var variable = mirror(LocalVariable.class, Map.of("name", variableName, "typeName", "java.lang.Object"));
        var values = new HashMap<String, Object>(Map.of("location", location,
            "visibleVariables", List.of(variable), "getValue", value));
        if (self != null) values.put("thisObject", self);
        return mirror(StackFrame.class, values);
    }

    private static ExecutionSnapshot capture(SourceAnalysis source, List<StackFrame> frames) throws Exception {
        try (var session = new cs1302.tracer.internal.execution.TraceSession(
                cs1302.tracer.api.TraceLimits.unlimited(), cs1302.tracer.api.InspectionPolicy.FIELDS, true);
                var out = new StreamDrainer(java.io.InputStream.nullInputStream());
                var err = new StreamDrainer(java.io.InputStream.nullInputStream())) {
            out.waitForEof(1000);
            err.waitForEof(1000);
            var thread = mirror(ThreadReference.class, Map.of("frames", frames, "frame", frames.getFirst()));
            var snapshot = SnapshotCapture.capture(thread, List.of(), source, new InputObservation(""),
                JobOutput.fromStart(out, err), null);
            assertThat(session.snapshots()).containsExactly(snapshot);
            return snapshot;
        }
    }

    @Test
    void localTypesSurviveMissingReceiverBindingsAndOptionalDeclaredNames() throws Exception {
        var resolver = new AstTypeResolver(StaticJavaParser.parse("class C<T> { void m() { T local; } }"));
        var self = mirror(ObjectReference.class, Map.of("uniqueID", 2L));
        var variable = mirror(LocalVariable.class, Map.of("name", "local", "typeName", "java.lang.Object"));
        assertThat(captureCall("resolveLocalVariableType",
                new Class<?>[] { StackFrame.class, LocalVariable.class, AstTypeResolver.class, Map.class },
                frame("m", self), variable, resolver, Map.of())).isEqualTo("T");
        var child = mirror(ObjectReference.class,
                Map.of("referenceType", mirror(ReferenceType.class, Map.of("name", "Box")), "uniqueID", 1L));
        var fields = new ArrayList<ExecutionSnapshot.Field>();
        var stackFrame = mirror(StackFrame.class, Map.of("getValue", child));
        var types = metadata(resolver, Map.of(1L, "Box"));
        var signature = new Class<?>[] { StackFrame.class, LocalVariable.class, boolean.class, String.class,
            Optional.class, ReifiedTypeMetadata.class, List.class, Map.class, List.class };
        captureCall("appendStackField", signature, stackFrame, variable, false, null, Optional.empty(), types,
                new ArrayList<>(), new HashMap<>(), fields);
        assertThat(fields.getFirst().typeName()).isNull();
        captureCall("appendStackField", signature, stackFrame, variable, false, "Box<String>", Optional.empty(), types,
                new ArrayList<>(), new HashMap<>(), fields);
        assertThat(types.typeOf(1L)).isEqualTo("Box<String>");
    }

    @Test
    void lambdaConstraintParametersUseTheirBoundForTheReconstructedSignature() throws Exception {
        var declaration = (com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {
                            com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration.class },
                        (self, method, args) -> com.github.javaparser.resolution.types.ResolvedLambdaConstraintType
                                .bound(com.github.javaparser.resolution.types.ResolvedPrimitiveType.INT));
        var parameter = new com.github.javaparser.ast.body.Parameter(
                com.github.javaparser.ast.type.PrimitiveType.intType(), "value") {
            @Override
            public com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration resolve() {
                return declaration;
            }
        };
        var unit = parse("class C { void m() { java.util.function.IntUnaryOperator op = value -> value; } }");
        var lambda = unit.findFirst(LambdaExpr.class).orElseThrow();
        lambda.setParameter(0, parameter);
        assertThat(SourceAnalysis.from(List.of(unit)).observations("C", "m", List.of(), 1)
                .lambdaImplementation("op")).contains("int applyAsInt(int value) {\n    return value;\n}");
    }
}
