package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.*;
import cs1302.tracer.internal.App;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Boundary contracts for conservative inference and incomplete solver metadata. */
class InvocationTypeResolverBoundaryTest {
    private Map<?, ?> infer(String source) throws Exception {
        var unit = App.parseSource(source, Optional.empty());
        var call = unit.findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals("helper"))
                .findFirst().orElseThrow();
        var method = InvocationTypeResolver.class.getDeclaredMethod("infer", MethodCallExpr.class, Map.class);
        method.setAccessible(true);
        return (Map<?, ?>) method.invoke(null, call, Map.of());
    }

    @Test
    void nestedDeclaredExpressionsPreserveFixedResults() throws Exception {
        for (String argument : List.of("this.value", "(String) value", "values[0]", "this", "super")) {
            String expression = argument.equals("this") || argument.equals("super") ? argument + ".toString()"
                    : "fixed(" + argument + ")";
            assertThat(infer(
                    "class Main { String value; String[] values; " + "static String fixed(String s) { return s; } "
                            + "static <T> void helper(T x) {} void run() { helper(" + expression + "); } }"))
                    .as(expression).isEqualTo(Map.of("T", "java.lang.String"));
        }
        assertThat(infer("class Main { static <T> void helper(T x) {} "
                + "void run() { String[] a = {}; helper(a.toString()); } }"))
                .isEqualTo(Map.of("T", "java.lang.String"));
    }

    @Test
    void unsupportedExpressionShapesAndNonmatchingFormalsStayUnresolved() throws Exception {
        for (String expression : List.of("true ? \"a\" : \"b\"", "(\"a\")", "new Main().new Inner()", "clone()",
                "fixed()")) {
            assertThat(infer("class Main { class Inner {} "
                    + "public Main clone() { return this; } String fixed() { return \"a\"; } "
                    + "static <T> void helper(T x) {} void run() { helper(" + expression + "); } }")).as(expression)
                    .isEmpty();
        }
        assertThat(infer("class Main { public Main clone() { return this; } "
                + "static <T> void helper(T x) {} void run() { helper(this.clone()); } }"))
                .isEqualTo(Map.of("T", "Main"));
        assertThat(infer("class Main { static <T> void helper(String x) {} " + "void run() { helper(\"a\"); } }"))
                .isEmpty();
        assertThat(infer("class Main<U> { U value() { return null; } <T> void helper(U x) {} "
                + "void run() { helper(value()); } }")).isEmpty();
        assertThat(infer(
                "class Main<U> { <T> void helper(U x) {} " + "void run() { new Main<String>().helper(\"a\"); } }"))
                .isEmpty();
    }

    private boolean independent(ResolvedType type) throws Exception {
        var method = InvocationTypeResolver.class.getDeclaredMethod("independentType", ResolvedType.class,
                boolean.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, type, false, 0);
    }

    private Optional<?> concrete(ResolvedType type) throws Exception {
        var method = InvocationTypeResolver.class.getDeclaredMethod("concreteType", ResolvedType.class, Map.class,
                MethodCallExpr.class, boolean.class);
        method.setAccessible(true);
        return (Optional<?>) method.invoke(null, type, Map.of(), new MethodCallExpr("helper"), false);
    }

    @Test
    void nestedTypeTraversalHasAnInclusiveLimit() throws Exception {
        ResolvedType type = ResolvedPrimitiveType.INT;
        for (int depth = 1; depth <= 33; depth++) {
            type = new ResolvedArrayType(type);
            assertThat(independent(type)).as("array depth %s", depth).isEqualTo(depth <= 32);
        }
        assertThat(concrete(ResolvedPrimitiveType.INT)).isEmpty();
    }

    @Test
    void concreteArrayTraversalRetainsLegalDimensionsAndRejectsExcessiveDepth() throws Exception {
        ResolvedType type = ResolvedPrimitiveType.INT;
        for (int depth = 1; depth <= 10_000; depth++) {
            type = new ResolvedArrayType(type);
            if (depth == 255 || depth == TypeTraversal.MAX_DEPTH - 1) {
                assertThat(concrete(type).orElseThrow()).isEqualTo("int" + "[]".repeat(depth));
            }
            if (depth == TypeTraversal.MAX_DEPTH) {
                assertThat(concrete(type)).isEmpty();
            }
        }
        assertThat(concrete(type)).isEmpty();
    }

    @Test
    void concreteGenericTraversalRejectsExcessiveDepth() throws Exception {
        var unit = App.parseSource("class Box<T> { String value; }", Optional.empty());
        var declaration = unit.getClassByName("Box").orElseThrow().resolve();
        ResolvedType type = unit.getClassByName("Box").orElseThrow().getFieldByName("value").orElseThrow()
                .getVariable(0).getType().resolve();
        for (int depth = 0; depth < 10_000; depth++) {
            type = new ReferenceTypeImpl(declaration, List.of(type));
            if (depth == 31) {
                assertThat(concrete(type).orElseThrow())
                        .isEqualTo("Box<".repeat(32) + "java.lang.String" + ">".repeat(32));
            }
        }
        assertThat(concrete(type)).isEmpty();
    }

    @Test
    void callerBindingTextIsCheckedBeforeParsing() throws Exception {
        var unit = App.parseSource("class Box<T> {} class Main { static <T> void helper(T x) {} "
                + "<U> void run(U value) { helper(value); } }", Optional.empty());
        var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        String key = unit.getClassByName("Main").orElseThrow().getMethodsByName("run").getFirst().resolve()
                .getTypeParameters().getFirst().getQualifiedName();
        var method = InvocationTypeResolver.class.getDeclaredMethod("infer", MethodCallExpr.class, Map.class);
        method.setAccessible(true);
        for (int depth : List.of(32, 65)) {
            String binding = "Box<".repeat(depth) + "java.lang.String" + ">".repeat(depth);
            var result = (Map<?, ?>) method.invoke(null, call, Map.of(key, binding));
            assertThat(result).isEqualTo(depth == 32 ? Map.of("T", binding) : Map.of());
        }
    }

    @Test
    void callerSubstitutionKeepsTheRemainingTraversalBudget() throws Exception {
        var unit = App.parseSource(
                "class Main { static <T> void helper(T x) {} " + "<U> void run(U value) { helper(value); } }",
                Optional.empty());
        var call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        var parameter = unit.getClassByName("Main").orElseThrow().getMethodsByName("run").getFirst().resolve()
                .getTypeParameters().getFirst();
        ResolvedType type = new ResolvedTypeVariable(parameter);
        for (int i = 0; i < 255; i++)
            type = new ResolvedArrayType(type);
        var method = InvocationTypeResolver.class.getDeclaredMethod("concreteType", ResolvedType.class, Map.class,
                MethodCallExpr.class, boolean.class);
        method.setAccessible(true);
        for (int dimensions : List.of(127, 128)) {
            var result = (Optional<?>) method.invoke(null, type,
                    Map.of(parameter.getQualifiedName(), "int" + "[]".repeat(dimensions)), call, false);
            if (dimensions == 127) {
                assertThat(result.orElseThrow()).isEqualTo("int" + "[]".repeat(382));
            } else {
                assertThat(result).isEmpty();
            }
        }
    }

    @Test
    void anonymousDeclarationsCannotBecomeDisplayNames() throws Exception {
        var unit = App.parseSource("class Main { Object value = new Object() {}; }", Optional.empty());
        var creation = unit.findFirst(com.github.javaparser.ast.expr.ObjectCreationExpr.class).orElseThrow();
        // Exercise an adapter that supplies an anonymous declaration rather than its base type.
        var metadata = new HashMap<String, Object>();
        metadata.put("getTypeParameters", List.of());
        metadata.put("isTypeParameter", false);
        metadata.put("isAnonymousClass", true);
        metadata.put("containerType", Optional.empty());
        metadata.put("toAst", Optional.of(creation));
        var anonymous = new ReferenceTypeImpl(mirror(ResolvedReferenceTypeDeclaration.class, metadata));
        assertThat(independent(anonymous)).isFalse();
    }

    @Test
    void cyclicOwnerMetadataTerminatesWithoutSpecializing() throws Exception {
        var values = new HashMap<String, Object>();
        values.put("getTypeParameters", List.of());
        values.put("isTypeParameter", false);
        values.put("isAnonymousClass", false);
        values.put("toAst", Optional.empty());
        var declaration = mirror(ResolvedReferenceTypeDeclaration.class, values);
        values.put("containerType", Optional.of(declaration));
        assertThat(independent(new ReferenceTypeImpl(declaration))).isFalse();
    }
}
