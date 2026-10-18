package cs1302.tracer.internal.trace;

import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.util.Map;
import java.util.Set;
import javassist.bytecode.SignatureAttribute;
import org.junit.jupiter.api.Test;

/** Raw signatures keep exact structure and fail closed when the prepared declaration cannot prove it. */
class BinaryTypeSignaturesTest {

    private final TypeSolver solver = new ReflectionTypeSolver();

    @Test
    void descriptorsVariablesAndWildcardBoundsPreserveTheirStructures() {
        assertThat(BinaryTypeSignatures.field("I", solver)).contains(new BinaryTypeSignatures.View("int", Set.of()));
        assertThat(BinaryTypeSignatures.field("[TT;", solver))
                .contains(new BinaryTypeSignatures.View("T[]", Set.of("T")));
        assertThat(BinaryTypeSignatures.field("Ljava/util/List<*>;", solver))
                .contains(new BinaryTypeSignatures.View("java.util.List<?>", Set.of()));
        assertThat(BinaryTypeSignatures.field("Ljava/util/List<+Ljava/lang/Number;>;", solver))
                .contains(new BinaryTypeSignatures.View("java.util.List<? extends java.lang.Number>", Set.of()));
        assertThat(BinaryTypeSignatures.field("Ljava/util/List<-TT;>;", solver))
                .contains(new BinaryTypeSignatures.View("java.util.List<? super T>", Set.of("T")));
    }

    @Test
    void invalidUnresolvedMismatchedAndInconsistentSignaturesNeverSupplyLabels() {
        assertThat(BinaryTypeSignatures.field("not_a_descriptor", solver)).isEmpty();
        assertThat(BinaryTypeSignatures.field("V", solver)).isEmpty();
        assertThat(BinaryTypeSignatures.field("Lmissing/Type;", solver)).isEmpty();
        assertThat(BinaryTypeSignatures.field("Ljava/util/List<Ljava/lang/String;Ljava/lang/Integer;>;", solver))
                .isEmpty();
        TypeSolver wrong = mirror(TypeSolver.class, Map.of("solveType", solver.solveType("java.lang.Object")));
        assertThat(BinaryTypeSignatures.field("Ljava/lang/String;", wrong)).isEmpty();
        assertThat(BinaryTypeSignatures.field("Ljava/util/List<".repeat(65) + "TT;" + ">;".repeat(65), solver))
                .isEmpty();
        assertThat(BinaryTypeSignatures.field("[".repeat(TypeTraversal.MAX_DEPTH) + "I", solver))
                .contains(new BinaryTypeSignatures.View("int" + "[]".repeat(TypeTraversal.MAX_DEPTH), Set.of()));
        assertThat(BinaryTypeSignatures.field("[".repeat(TypeTraversal.MAX_DEPTH + 1) + "I", solver)).isEmpty();
    }

    @Test
    void ownerArgumentsRemainVisibleButUnsupportedHierarchyOwnersAndExcessiveNestingFallBack() throws Exception {
        var nested = SignatureAttribute.toTypeSignature(
                "Ljava/util/Map<TK;TV;>.Entry<TK;TV;>;");
        assertThat(BinaryTypeSignatures.view(nested, solver)).contains(
                new BinaryTypeSignatures.View("java.util.Map<K, V>.Entry<K, V>", Set.of("K", "V")));
        assertThat(BinaryTypeSignatures.parent(nested, solver)).isEmpty();
        var entry = solver.solveType("java.util.Map$Entry");
        TypeSolver collision = (TypeSolver) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { TypeSolver.class }, (self, method, arguments) ->
                    "java.util.Map$Entry".equals(arguments[0]) ? entry : solver.solveType("java.lang.Object"));
        assertThat(BinaryTypeSignatures.view(nested, collision)).isEmpty();
        SignatureAttribute.ClassType excessive = new SignatureAttribute.ClassType("java.lang.Object");
        for (int index = 0; index <= TypeTraversal.MAX_DEPTH; index++) {
            excessive = new SignatureAttribute.NestedClassType(excessive, "Nested", null);
        }
        assertThat(BinaryTypeSignatures.view(excessive, solver)).isEmpty();
    }

    @Test
    void signatureUtilityCannotBeConstructedThroughItsPublicContract() throws Exception {
        var constructor = BinaryTypeSignatures.class.getDeclaredConstructor();
        assertThat(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

} // BinaryTypeSignaturesTest
