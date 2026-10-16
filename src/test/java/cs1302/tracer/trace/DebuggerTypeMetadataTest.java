package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.DebuggerEventTest.call;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;
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
                Map.of("name", methodName, "declaringType", mirror(ReferenceType.class, Map.of("name", "C"))));
        var location = mirror(Location.class, Map.of("method", method, "lineNumber", 1));
        var values = new HashMap<String, Object>(Map.of("location", location, "visibleVariables", List.of()));
        if (self != null)
            values.put("thisObject", self);
        return mirror(StackFrame.class, values);
    }

    @Test
    void constructorTypePrepassHandlesMissingReceiverOrCaller() throws Exception {
        var resolver = new AstTypeResolver(StaticJavaParser.parse("class C { void m() { C obj = new C(); } }"));
        var signature = new Class<?>[] { ThreadReference.class, AstTypeResolver.class, ReifiedTypeMetadata.class };
        for (var frames : List.of(List.of(frame("<init>", null)), List.of(frame("<init>", null), frame("m", null)))) {
            var types = new ReifiedTypeMetadata(resolver);
            call("prepassObjectTypes", signature, mirror(ThreadReference.class, Map.of("frames", frames)), resolver,
                    types);
            assertThat(types.typeOf(1L)).isNull();
        }
    }

    @Test
    void allocationTypesRefineUnknownLocalsWithoutOverwritingMoreSpecificTypes() throws Exception {
        var resolver = new AstTypeResolver(List.of());
        var object = mirror(ObjectReference.class, Map.of("referenceType",
                mirror(ReferenceType.class, Map.of("name", "java.util.ArrayList")), "uniqueID", 1L));
        var variable = mirror(LocalVariable.class, Map.of("name", "unknown"));
        var signature = new Class<?>[] { StackFrame.class, LocalVariable.class, ObjectReference.class, Optional.class,
            AstTypeResolver.class, ReifiedTypeMetadata.class, Map.class };
        var types = metadata(resolver, Map.of(1L, "java.util.ArrayList"));
        call("prepassVariableReference", signature, frame("m", null), variable, object, Optional.of("List<String>"),
                resolver, types, Map.of());
        assertThat(types.typeOf(1L)).isEqualTo("java.util.ArrayList<String>");
        call("prepassVariableReference", signature, frame("m", null), variable, object, Optional.of("List<?>"),
                resolver, types, Map.of());
        assertThat(types.typeOf(1L)).isEqualTo("java.util.ArrayList<String>");
    }

    @Test
    void declaredLocalTypeSurvivesAnUnresolvedReceiverType() throws Exception {
        var resolver = new AstTypeResolver(
                StaticJavaParser.parse("class C { void m() { java.util.List<String> local; } }"));
        var self = mirror(ObjectReference.class, Map.of("uniqueID", 2L));
        var local = mirror(ObjectReference.class, Map.of("referenceType",
                mirror(ReferenceType.class, Map.of("name", "java.util.ArrayList")), "uniqueID", 1L));
        var variable = mirror(LocalVariable.class, Map.of("name", "local"));
        var types = new ReifiedTypeMetadata(resolver);
        call("prepassVariableReference",
                new Class<?>[] { StackFrame.class, LocalVariable.class, ObjectReference.class, Optional.class,
                    AstTypeResolver.class, ReifiedTypeMetadata.class, Map.class },
                frame("m", self), variable, local, Optional.empty(), resolver, types, Map.of());
        assertThat(types.typeOf(1L)).isEqualTo("java.util.ArrayList<String>");
        assertThat(types.typeOf(2L)).isNull();
    }

    @Test
    void localTypesSurviveMissingReceiverBindingsAndOptionalDeclaredNames() throws Exception {
        var resolver = new AstTypeResolver(StaticJavaParser.parse("class C<T> { void m() { T local; } }"));
        var self = mirror(ObjectReference.class, Map.of("uniqueID", 2L));
        var variable = mirror(LocalVariable.class, Map.of("name", "local", "typeName", "java.lang.Object"));
        assertThat(call("resolveLocalVariableType",
                new Class<?>[] { StackFrame.class, LocalVariable.class, AstTypeResolver.class, Map.class },
                frame("m", self), variable, resolver, Map.of())).isEqualTo("T");
        var child = mirror(ObjectReference.class,
                Map.of("referenceType", mirror(ReferenceType.class, Map.of("name", "Box")), "uniqueID", 1L));
        var fields = new ArrayList<ExecutionSnapshot.Field>();
        var stackFrame = mirror(StackFrame.class, Map.of("getValue", child));
        var types = metadata(resolver, Map.of(1L, "Box"));
        var signature = new Class<?>[] { StackFrame.class, LocalVariable.class, boolean.class, String.class,
            Optional.class, ReifiedTypeMetadata.class, List.class, Map.class, List.class };
        call("appendStackField", signature, stackFrame, variable, false, null, Optional.empty(), types,
                new ArrayList<>(), new HashMap<>(), fields);
        assertThat(fields.getFirst().typeName()).isNull();
        call("appendStackField", signature, stackFrame, variable, false, "Box<String>", Optional.empty(), types,
                new ArrayList<>(), new HashMap<>(), fields);
        assertThat(types.typeOf(1L)).isEqualTo("Box<String>");
    }

    @Test
    void lambdaConstraintParametersUseTheirBoundForTheReconstructedSignature() throws Exception {
        var declaration = (com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
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
