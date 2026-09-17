package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AstTypeResolverBoundaryTest {
    private final AstTypeResolver resolver = new AstTypeResolver(List.of());

    @ParameterizedTest
    @ValueSource(strings = {"ArrayList", "LinkedList", "Vector", "ArrayDeque", "PriorityQueue",
            "HashSet", "LinkedHashSet", "TreeSet"})
    void reconcilesSupportedCollectionsWithSimpleAndQualifiedNames(String name) {
        for (String runtime : List.of(name, "java.util." + name)) {
            assertThat(resolver.reconcileRuntimeType(runtime, "Collection<? extends String>"))
                    .isEqualTo(runtime + "<String>");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"HashMap", "LinkedHashMap", "TreeMap", "Hashtable", "ConcurrentHashMap"})
    void reconcilesSupportedMapsWithSimpleAndQualifiedNames(String name) {
        String qualified = (name.equals("ConcurrentHashMap") ? "java.util.concurrent."
                : "java.util.") + name;
        for (String runtime : List.of(name, qualified)) {
            assertThat(resolver.reconcileRuntimeType(runtime, "Map<String, Integer>"))
                    .isEqualTo(runtime + "<String, Integer>");
            assertThat(resolver.reconcileRuntimeType(runtime, "Other<String>"))
                    .isEqualTo(runtime);
        }
    }

    @Test
    void preservesUnknownAndMalformedRuntimeTypes() {
        assertThat(resolver.reconcileRuntimeType(null, "Declared")).isEqualTo("Declared");
        assertThat(resolver.reconcileRuntimeType(null, null)).isEqualTo("java.lang.Object");
        assertThat(resolver.reconcileRuntimeType("Unknown", "Other<")).isEqualTo("Unknown");
        assertThat(resolver.reconcileRuntimeType("Unknown", "Other<String>")).isEqualTo("Unknown");
        assertThat(AstTypeResolver.normalizeWildcard(null)).isEqualTo("java.lang.Object");
        assertThat(AstTypeResolver.rawTypeMatches("Type", null)).isFalse();
        assertThat(AstTypeResolver.rawTypeMatches("p.Type", "Type")).isTrue();
        assertThat(AstTypeResolver.rawTypeMatches("Type", "p.Type")).isTrue();
    }

    @Test
    void substitutesWildcardBoundsAndHandlesUnparseableTypeText() {
        Map<String, String> bindings = Map.of("T", "String");
        assertThat(AstTypeResolver.substituteType("List<? extends T>", bindings))
                .isEqualTo("List<? extends String>");
        assertThat(AstTypeResolver.substituteType("List<? super T>", bindings))
                .isEqualTo("List<? super String>");
        assertThat(AstTypeResolver.substituteType("List<?>", bindings)).isEqualTo("List<?>");
        assertThat(AstTypeResolver.substituteType("T ... invalid", bindings))
                .isEqualTo("String ... invalid");
        assertThat(AstTypeResolver.substituteType("T", Map.of("T", "Bad<"))).isEqualTo("Bad<");
        assertThat(AstTypeResolver.substituteType("p.T", bindings)).isEqualTo("p.T");
    }

    @Test
    void resolvesConstructorLocalsAndInheritedTypeArguments() {
        var unit = StaticJavaParser.parse("""
                package sample;
                class Base<T> {}
                class Child<T> extends Base<T> {
                    Child(T value) {
                        String label = "child";
                    }
                }
                """);
        var indexed = new AstTypeResolver(unit);
        assertThat(indexed.resolveVariableType("sample.Child", "<init>", "label", 5))
                .contains("String");
        assertThat(indexed.resolveSubclassType("Child", "Base<Integer>"))
                .isEqualTo("Child<Integer>");
        assertThat(indexed.reconcileRuntimeType("sample.Child", "Base<Integer>"))
                .isEqualTo("sample.Child<Integer>");
        assertThat(indexed.resolveSubclassType("Child", "Other<Integer>"))
                .isEqualTo("Child");
        assertThat(indexed.resolveSubclassType("Child", "Base<Integer, String>"))
                .isEqualTo("Child");
    }

    @Test
    void emptyAndPartialAstInputsRemainUsable() {
        for (var empty : List.of(new AstTypeResolver((com.github.javaparser.ast.CompilationUnit) null),
                new AstTypeResolver((List<com.github.javaparser.ast.CompilationUnit>) null),
                new AstTypeResolver(java.util.Arrays.asList((com.github.javaparser.ast.CompilationUnit) null)))) {
            assertThat(empty.getClassGenericInfo("Missing")).isEmpty();
        }
        assertThat(AstTypeResolver.resolveAstType(null)).isEqualTo("java.lang.Object");
        assertThat(AstTypeResolver.resolveAstTypeWithParams(null, null)).isEqualTo("java.lang.Object");
        assertThat(AstTypeResolver.resolveAstTypeWithParams(StaticJavaParser.parseType("int"), null)).isEqualTo("int");
        assertThat(AstTypeResolver.resolveAstTypeWithParams(StaticJavaParser.parseType("p.T"), List.of("T"))).isEqualTo("T");
        assertThat(AstTypeResolver.resolveAstTypeWithParams(StaticJavaParser.parseType("List<>"), null)).isEqualTo("List<>");
        assertThat(AstTypeResolver.extractTypeArguments("Map<List<A,B>,C,>"))
                .containsExactly("List<A,B>", "C");
        assertThat(AstTypeResolver.substituteType("T<String>", Map.of("T", "Integer")))
                .isEqualTo("T<String>");
    }

    @Test
    void genericMethodsAndConstructorsKeepTheirOwnParameters() {
        var unit = StaticJavaParser.parse("""
                class Generic<T> {
                    <U> Generic(U arg) { U value = arg; }
                    <V> V method(V arg) { V local; return arg; }
                    void empty() {}
                }
                class Plain {}
                """);
        var indexed = new AstTypeResolver(unit);
        assertThat(indexed.resolveVariableType("Generic", "<init>", "arg", 2)).contains("U");
        assertThat(indexed.resolveVariableType("Generic", "method", "local", 0)).contains("V");
        assertThat(indexed.resolveVariableType("Generic", "method", "arg", 99)).contains("V");
        assertThat(indexed.resolveVariableType("Generic", "empty", "x", 4)).isEmpty();
        assertThat(indexed.reconcileRuntimeType("Plain", "Generic<String>")).isEqualTo("Plain");
        assertThat(indexed.getTypeBindings("Generic", null)).isEmpty();
        assertThat(indexed.getTypeBindings("Generic", "Generic")).isEmpty();
        assertThat(indexed.getTypeBindings("Generic", "Generic<A,B>")).isEmpty();
    }

    @Test
    void unpositionedAllocationNodesAreNotIndexed() {
        var unit = StaticJavaParser.parse("class C { void m() { Object x = new Object(); x = new Object(); } }");
        unit.walk(node -> node.setRange(null));
        var indexed = new AstTypeResolver(unit);
        assertThat(indexed.getAllocationType("C", 0)).isEmpty();
    }

    @Test
    void unresolvedAllocationsKeepExplicitArgumentsOrRawType() {
        var allocation = StaticJavaParser.parseExpression("new <String> Box()").asObjectCreationExpr();
        assertThat(resolver.resolveObjectCreationType(allocation)).isEqualTo("Box<String>");
        allocation = StaticJavaParser.parseExpression("new Box()").asObjectCreationExpr();
        allocation.setTypeArguments(new com.github.javaparser.ast.NodeList<>());
        assertThat(resolver.resolveObjectCreationType(allocation)).isEqualTo("Box");
        var assignment = StaticJavaParser.parseExpression("x = new Box()").asAssignExpr();
        assertThat(resolver.resolveObjectCreationType(assignment.getValue().asObjectCreationExpr())).isEqualTo("Box");
        var call = StaticJavaParser.parseExpression("accept(new Box())").asMethodCallExpr();
        assertThat(resolver.resolveObjectCreationType(call.getArgument(0).asObjectCreationExpr())).isEqualTo("Box");
    }

    @Test
    void symbolSolverResolvesGenericAllocationsAndArrayAssignments() {
        var config = new com.github.javaparser.ParserConfiguration().setSymbolResolver(
                new com.github.javaparser.symbolsolver.JavaSymbolSolver(
                        new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver()));
        var parser = new com.github.javaparser.JavaParser(config);
        var unit = parser.parse("""
                import java.util.*;
                class C {
                    void m() {
                        List<String> list = new ArrayList<>();
                        list = new ArrayList<>();
                        int[] nums = new int[1];
                        nums = new int[2];
                        Object plain = new Object();
                    }
                }
                """).getResult().orElseThrow();
        var indexed = new AstTypeResolver(unit);
        assertThat(indexed.getAllocationType("C", 4)).contains("java.util.ArrayList<java.lang.String>");
        assertThat(indexed.getAllocationType("C", 5)).contains("java.util.ArrayList<java.lang.String>");
        assertThat(indexed.getAllocationType("C", 7)).isEmpty();
        assertThat(indexed.getAllocationType("C", 8)).contains("java.lang.Object");
    }

    @Test
    void preservesUnboundGenericClassesAndSkipsArrayContainingAllocations() {
        var unit = StaticJavaParser.parse("""
                class Box<T> { void m() {
                    Object obj = new Box<int[]>();
                } }
                """);
        var indexed = new AstTypeResolver(unit);
        assertThat(indexed.reconcileRuntimeType("Box", "Other<String>")).isEqualTo("Box");
        assertThat(indexed.getAllocationType("Box", 2)).contains("Object");
    }

    @Test
    void expressionResolutionCanRefineAnUnresolvedRawCreatedType() {
        var parser = new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration()
                .setSymbolResolver(new com.github.javaparser.symbolsolver.JavaSymbolSolver(
                        new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver())));
        for (String generic : List.of("java.util.List<String>", "java.util.List<?>")) {
            var unit = parser.parse("class C { " + generic + " value; }").getResult().orElseThrow();
            var resolved = unit.findFirst(com.github.javaparser.ast.body.VariableDeclarator.class).orElseThrow().getType().resolve();
            var allocation = new com.github.javaparser.ast.expr.ObjectCreationExpr() {
                @Override public com.github.javaparser.resolution.types.ResolvedType calculateResolvedType() { return resolved; }
            };
            allocation.setType("Raw");
            assertThat(resolver.resolveObjectCreationType(allocation)).isEqualTo(
                    generic.contains("?") ? "Raw" : "java.util.List<java.lang.String>");
        }
        var resolvedString = parser.parse("class C { String value; }").getResult().orElseThrow()
                .findFirst(com.github.javaparser.ast.body.VariableDeclarator.class).orElseThrow().getType().resolve();
        var type = new com.github.javaparser.ast.type.ClassOrInterfaceType() {
            @Override public com.github.javaparser.resolution.types.ResolvedType resolve() { return resolvedString; }
        };
        type.setName("String");
        type.setTypeArguments(StaticJavaParser.parseType("T"));
        assertThat(AstTypeResolver.resolveAstTypeWithParams(type, List.of("T"))).isEqualTo("java.lang.String<T>");
    }
}
