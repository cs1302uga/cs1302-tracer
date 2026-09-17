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
    private static CompilationUnit parse(String source) {
        var parser = new JavaParser(new ParserConfiguration().setSymbolResolver(
                new com.github.javaparser.symbolsolver.JavaSymbolSolver(
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
        var lambdas = unit.findAll(LambdaExpr.class);
        var signature = new Class<?>[] {LambdaExpr.class};
        assertThat(call("tryImplementLambdaSam", signature, lambdas.get(0)).toString())
                .contains("void run()", "System.out.println()").doesNotContain("return");
        assertThat(call("tryImplementLambdaSam", signature, lambdas.get(1)).toString())
                .contains("java.lang.String text", "return text.length()");
        assertThat(call("tryImplementLambdaSam", signature, lambdas.get(2))).isEqualTo(Optional.empty());
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
        var lambdas = new HashMap<String, List<?>>();
        var finals = new HashMap<String, Set<String>>();
        call("buildLambdaAndFinalMaps", new Class<?>[] {List.class, Map.class, Map.class},
                Arrays.asList(null, unit), lambdas, finals);
        assertThat(lambdas.get("C.m()")).hasSize(2);
        assertThat(call("findLambdaImplementation", new Class<?>[] {List.class, String.class, int.class},
                lambdas.get("C.m()"), "local", 1)).toString().contains("void run()");
    }

    private static StackFrame frame(String methodName, ObjectReference self) {
        var method = mirror(com.sun.jdi.Method.class, Map.of("name", methodName,
                "declaringType", mirror(ReferenceType.class, Map.of("name", "C"))));
        var location = mirror(Location.class, Map.of("method", method, "lineNumber", 1));
        var values = new HashMap<String, Object>(Map.of("location", location, "visibleVariables", List.of()));
        if (self != null) values.put("thisObject", self);
        return mirror(StackFrame.class, values);
    }

    @Test
    void constructorTypePrepassHandlesMissingReceiverOrCaller() throws Exception {
        var resolver = new AstTypeResolver(StaticJavaParser.parse("class C { void m() { C obj = new C(); } }"));
        var signature = new Class<?>[] {ThreadReference.class, AstTypeResolver.class, Map.class};
        for (var frames : List.of(List.of(frame("<init>", null)), List.of(frame("<init>", null), frame("m", null)))) {
            var types = new HashMap<Long, String>();
            call("prepassObjectTypes", signature, mirror(ThreadReference.class, Map.of("frames", frames)), resolver, types);
            assertThat(types).isEmpty();
        }
    }

    @Test
    void allocationTypesRefineUnknownLocalsWithoutOverwritingMoreSpecificTypes() throws Exception {
        var resolver = new AstTypeResolver(List.of());
        var object = mirror(ObjectReference.class, Map.of("referenceType", mirror(ReferenceType.class,
                Map.of("name", "java.util.ArrayList")), "uniqueID", 1L));
        var variable = mirror(LocalVariable.class, Map.of("name", "unknown"));
        var signature = new Class<?>[] {StackFrame.class, LocalVariable.class, ObjectReference.class,
                String.class, String.class, int.class, Optional.class, AstTypeResolver.class, Map.class};
        var types = new HashMap<Long, String>(Map.of(1L, "java.util.ArrayList"));
        call("prepassVariableReference", signature, frame("m", null), variable, object, "C", "m", 1,
                Optional.of("List<String>"), resolver, types);
        assertThat(types).containsEntry(1L, "java.util.ArrayList<String>");
        call("prepassVariableReference", signature, frame("m", null), variable, object, "C", "m", 1,
                Optional.of("List<?>"), resolver, types);
        assertThat(types).containsEntry(1L, "java.util.ArrayList<String>");
    }

    @Test
    void localTypesSurviveMissingReceiverBindingsAndOptionalDeclaredNames() throws Exception {
        var resolver = new AstTypeResolver(StaticJavaParser.parse("class C<T> { void m() { T local; } }"));
        var self = mirror(ObjectReference.class, Map.of("uniqueID", 2L));
        var variable = mirror(LocalVariable.class, Map.of("name", "local", "typeName", "java.lang.Object"));
        assertThat(call("resolveLocalVariableType", new Class<?>[] {StackFrame.class, LocalVariable.class,
                AstTypeResolver.class, Map.class, String.class, String.class}, frame("m", self), variable,
                resolver, Map.of(), "C", "m")).isEqualTo("T");
        var child = mirror(ObjectReference.class, Map.of("referenceType", mirror(ReferenceType.class,
                Map.of("name", "Box")), "uniqueID", 1L));
        var fields = new ArrayList<ExecutionSnapshot.Field>();
        var stackFrame = mirror(StackFrame.class, Map.of("getValue", child));
        var types = new HashMap<Long, String>(Map.of(1L, "Box"));
        var signature = new Class<?>[] {StackFrame.class, LocalVariable.class, boolean.class, String.class,
                Optional.class, AstTypeResolver.class, Map.class, List.class, Map.class, List.class};
        call("appendStackField", signature, stackFrame, variable, false, null, Optional.empty(), resolver,
                types, new ArrayList<>(), new HashMap<>(), fields);
        assertThat(fields.getFirst().typeName()).isNull();
        call("appendStackField", signature, stackFrame, variable, false, "Box<String>", Optional.empty(), resolver,
                types, new ArrayList<>(), new HashMap<>(), fields);
        assertThat(types).containsEntry(1L, "Box<String>");
    }

    @Test
    void lambdaConstraintParametersUseTheirBoundForTheReconstructedSignature() throws Exception {
        var declaration = (com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration.class},
                        (self, method, args) -> com.github.javaparser.resolution.types.ResolvedLambdaConstraintType.bound(
                                com.github.javaparser.resolution.types.ResolvedPrimitiveType.INT));
        var parameter = new com.github.javaparser.ast.body.Parameter(
                com.github.javaparser.ast.type.PrimitiveType.intType(), "value") {
            @Override public com.github.javaparser.resolution.declarations.ResolvedParameterDeclaration resolve() {
                return declaration;
            }
        };
        var lambda = StaticJavaParser.parseExpression("(int value) -> value").asLambdaExpr();
        lambda.setParameter(0, parameter);
        assertThat(call("formatLambdaParam", new Class<?>[] {LambdaExpr.class, int.class}, lambda, 0))
                .isEqualTo("int value");
    }
}
