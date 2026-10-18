package cs1302.tracer.internal.trace;

import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.sun.jdi.ClassType;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Functional presentation evidence never replaces runtime generic bindings. */
class FunctionalLambdaMetadataTest {

    private static final String COMPARATOR = "java.util.Comparator";
    private static final String HIDDEN = "dependency.Factory$$Lambda/0x123abc";

    private static AstTypeResolver resolver() {
        var unit = StaticJavaParser.parse("class Main {}");
        unit.setData(AstTypeResolver.TYPE_SOLVER, new ReflectionTypeSolver());
        return new AstTypeResolver(Arrays.asList(null, StaticJavaParser.parse("class Other {}"), unit));
    }

    private static ObjectReference reference(String runtime, List<String> interfaces) {
        var implemented = interfaces.stream().map(name -> mirror(InterfaceType.class, Map.of("name", name)))
                .toList();
        var type = mirror(ClassType.class, Map.of("name", runtime, "allInterfaces", implemented));
        return mirror(ObjectReference.class, Map.of("uniqueID", 91L, "referenceType", type));
    }

    static Stream<Arguments> views() {
        return Stream.of(
                Arguments.of(List.of(COMPARATOR), COMPARATOR),
                Arguments.of(List.of(COMPARATOR + "<java.lang.String>"), COMPARATOR + "<java.lang.String>"),
                Arguments.of(List.of(COMPARATOR, COMPARATOR + "<java.lang.String>"),
                        COMPARATOR + "<java.lang.String>"),
                Arguments.of(List.of(COMPARATOR + "<java.lang.String>", COMPARATOR),
                        COMPARATOR + "<java.lang.String>"),
                Arguments.of(List.of(COMPARATOR + "<java.lang.String>", COMPARATOR + "<java.lang.String>"),
                        COMPARATOR + "<java.lang.String>"),
                Arguments.of(List.of(COMPARATOR + "<java.lang.String>", COMPARATOR + "<java.lang.Integer>"),
                        COMPARATOR),
                Arguments.of(List.of(COMPARATOR + "<java.lang.Integer>", COMPARATOR + "<java.lang.String>"),
                        COMPARATOR),
                Arguments.of(List.of(COMPARATOR + "<?>"), COMPARATOR + "<?>"),
                Arguments.of(List.of(COMPARATOR + "<? super java.lang.String>"),
                        COMPARATOR + "<? super java.lang.String>"),
                Arguments.of(List.of(COMPARATOR + "<? extends java.lang.Number>"),
                        COMPARATOR + "<? extends java.lang.Number>"),
                Arguments.of(List.of(COMPARATOR + "<?> ", COMPARATOR + "<java.lang.String>"), COMPARATOR));
    }

    @ParameterizedTest
    @MethodSource("views")
    void aliasesResolveDeterministicallyWithoutNormalizingWildcards(List<String> observed, String expected) {
        var metadata = new ReifiedTypeMetadata(resolver());
        var lambda = reference(HIDDEN, List.of(COMPARATOR));
        for (String view : observed) {
            metadata.refineDeclared(lambda, view);
        }
        assertThat(metadata.functionalView(91)).isEqualTo(expected);
        assertThat(metadata.typeOf(91)).isEqualTo(HIDDEN);
        assertThat(new ReifiedTypeMetadata(resolver()).functionalView(91)).isNull();
    }

    @Test
    void structuralSamResolutionRejectsMarkersClassesAndMissingDeclarations() {
        var resolver = resolver();
        assertThat(resolver.functionalInterfaceName(COMPARATOR, COMPARATOR)).contains(COMPARATOR);
        assertThat(resolver.functionalInterfaceName("java.lang.Runnable", "java.lang.Runnable"))
                .contains("java.lang.Runnable");
        assertThat(resolver.functionalInterfaceName("java.io.Serializable", "java.io.Serializable")).isEmpty();
        assertThat(resolver.functionalInterfaceName("java.util.List", "java.util.List")).isEmpty();
        assertThat(resolver.functionalInterfaceName("java.lang.String", "java.lang.String")).isEmpty();
        assertThat(resolver.functionalInterfaceName("missing.Interface", "missing.Interface")).isEmpty();
        assertThat(new AstTypeResolver((com.github.javaparser.ast.CompilationUnit) null)
                .functionalInterfaceName(COMPARATOR, COMPARATOR)).isEmpty();
    }

    @Test
    void inconsistentCyclicAndWrongSolverIdentitiesCannotLicenseCanonicalAliases() {
        var cyclicValues = new HashMap<String, Object>(Map.of("getPackageName", "", "getClassName", "Outer$Inner"));
        var cyclic = mirror(ResolvedReferenceTypeDeclaration.class, cyclicValues);
        cyclicValues.put("containerType", Optional.of(cyclic));
        var owner = mirror(ResolvedReferenceTypeDeclaration.class,
                Map.of("getPackageName", "", "getClassName", "Other"));
        var inconsistent = mirror(ResolvedReferenceTypeDeclaration.class, Map.of("getPackageName", "",
                "getClassName", "Inner", "containerType", Optional.of(owner)));
        for (var declaration : List.of(cyclic, inconsistent)) {
            var unit = StaticJavaParser.parse("class Main {}");
            unit.setData(AstTypeResolver.TYPE_SOLVER, mirror(TypeSolver.class, Map.of("solveType", declaration)));
            var resolver = new AstTypeResolver(unit);
            assertThat(resolver.functionalInterfaceName(declaration == cyclic ? "Outer$Inner" : "Inner",
                    "Outer.Inner")).isEmpty();
            assertThat(resolver.functionalInterfaceName("missing.Other", "Outer.Inner")).isEmpty();
        }
    }

    @Test
    void markersUnimplementedTypesAndUnrelatedFunctionalViewsNeverChooseAnArbitraryAlias() {
        for (List<String> order : List.of(List.of(COMPARATOR, "java.lang.Runnable"),
                List.of("java.lang.Runnable", COMPARATOR))) {
            var metadata = new ReifiedTypeMetadata(resolver());
            var lambda = reference(HIDDEN, List.of(COMPARATOR, "java.io.Serializable", "java.lang.Runnable"));
            for (String ignored : List.of("java.lang.Object", "java.io.Serializable", "missing.Interface",
                    COMPARATOR + "<java.lang.String> & java.io.Serializable")) {
                metadata.refineDeclared(lambda, ignored);
            }
            assertThat(metadata.functionalView(91)).isNull();
            for (String view : order) {
                metadata.refineDeclared(lambda, view);
            }
            assertThat(metadata.functionalView(91)).isNull();
        }
    }

    @Test
    void ordinaryObjectsUnavailableEvidenceAndNonclassMirrorsKeepTheirHeadings() {
        var metadata = new ReifiedTypeMetadata(resolver());
        metadata.refineDeclared(reference("dependency.Comparator", List.of(COMPARATOR)), COMPARATOR);
        metadata.refineDeclared(reference("dependency.Fake$$Lambda", List.of(COMPARATOR)), COMPARATOR);
        metadata.refineDeclared(reference(HIDDEN, List.of(COMPARATOR)), null);
        var type = mirror(ReferenceType.class, Map.of("name", HIDDEN));
        metadata.refineDeclared(mirror(ObjectReference.class, Map.of("uniqueID", 91L, "referenceType", type)),
                COMPARATOR);
        var broken = mirror(ClassType.class, Map.of("name", HIDDEN,
                "allInterfaces", new IllegalStateException("Unavailable interfaces")));
        metadata.refineDeclared(mirror(ObjectReference.class, Map.of("uniqueID", 91L, "referenceType", broken)),
                COMPARATOR);
        assertThat(metadata.functionalView(91)).isNull();
        var unavailable = new ReifiedTypeMetadata(null);
        unavailable.refineDeclared(reference(HIDDEN, List.of(COMPARATOR)), COMPARATOR);
        assertThat(unavailable.functionalView(91)).isNull();
    }

    @Test
    void sourceLambdaBodiesRemainUnchangedEvenWhenAFunctionalViewWasObserved() {
        var metadata = new ReifiedTypeMetadata(resolver());
        var lambda = reference(HIDDEN, List.of(COMPARATOR));
        metadata.refineDeclared(lambda, COMPARATOR + "<java.lang.String>");
        var inspection = new HeapInspection(null, metadata);
        inspection.sourceLambda(lambda, "int compare(String a, String b) { return 0; }");
        assertThat(inspection.finish().get(91L))
                .isEqualTo(new TraceValue.Lambda("int compare(String a, String b) { return 0; }"));
    }
}
