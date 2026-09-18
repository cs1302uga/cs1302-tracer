package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceAnalysisTest {

    @Test
    @DisplayName("empty analysis provides default safe instances")
    void testEmptyAnalysis() {
        SourceAnalysis empty = SourceAnalysis.empty();
        assertThat(empty.parsedSources()).isEmpty();
        assertThat(empty.astTypeResolver()).isNotNull();
        assertThat(empty.lambdaMethodAssignments()).isEmpty();
        assertThat(empty.finalMethodVariables()).isEmpty();
        assertThat(empty.findClassDeclaration("Foo")).isEmpty();
        assertThat(empty.findClassDeclaration(null)).isEmpty();
        assertThat(empty.findStaticLambdaImplementation("Foo", "bar")).isEmpty();
        assertThat(empty.findStaticLambdaImplementation(null, "bar")).isEmpty();
        assertThat(empty.findStaticLambdaImplementation("Foo", null)).isEmpty();

        assertThat(SourceAnalysis.from(null)).isSameAs(empty);
        assertThat(SourceAnalysis.from(List.of())).isSameAs(empty);
        assertThat(SourceAnalysis.from(Arrays.asList((CompilationUnit) null))).isSameAs(empty);
    } // testEmptyAnalysis

    @Test
    @DisplayName("indexes compilation unit declarations and static lambdas")
    void testSourceIndexing() {
        String code = """
                package sample;
                import java.util.function.Function;

                public class Example {
                    static Function<Integer, Integer> doubler = x -> x * 2;
                    Function<Integer, Integer> instanceDoubler = x -> x * 4;

                    public static void main(String[] args) {
                        final int factor = 3;
                        Function<Integer, Integer> tripler = y -> y * factor;
                    }
                }
                """;
        var config = new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(new ReflectionTypeSolver()));
        CompilationUnit cu = new JavaParser(config).parse(code).getResult().orElseThrow();
        SourceAnalysis analysis = SourceAnalysis.from(List.of(cu));

        assertThat(analysis.parsedSources()).containsExactly(cu);
        assertThat(analysis.findClassDeclaration("sample.Example")).isPresent();
        assertThat(analysis.findClassDeclaration("Example")).isPresent();
        assertThat(analysis.findClassDeclaration("Nonexistent")).isEmpty();
        assertThat(analysis.findClassDeclaration(null)).isEmpty();

        assertThat(analysis.findStaticLambdaImplementation("sample.Example", "doubler")).isPresent();
        assertThat(analysis.findStaticLambdaImplementation("Example", "doubler")).isPresent();
        // Instance lambda fields should not be indexed as static lambdas
        assertThat(analysis.findStaticLambdaImplementation("sample.Example", "instanceDoubler"))
                .isEmpty();
        assertThat(analysis.findStaticLambdaImplementation("Example", "instanceDoubler"))
                .isEmpty();
        assertThat(analysis.findStaticLambdaImplementation("Example", "nonexistent")).isEmpty();
        assertThat(analysis.findStaticLambdaImplementation(null, "doubler")).isEmpty();
        assertThat(analysis.findStaticLambdaImplementation("Example", null)).isEmpty();

        assertThat(analysis.astTypeResolver()).isNotNull();
        assertThat(analysis.lambdaMethodAssignments()).isNotEmpty();
        assertThat(analysis.finalMethodVariables()).isNotEmpty();

        var lambdaList = analysis.lambdaMethodAssignments().values().iterator().next();
        assertThatThrownBy(lambdaList::clear).isInstanceOf(UnsupportedOperationException.class);
        var finalSet = analysis.finalMethodVariables().values().iterator().next();
        assertThatThrownBy(finalSet::clear).isInstanceOf(UnsupportedOperationException.class);
    } // testSourceIndexing

    @Test
    @DisplayName("tolerates unresolved lambda symbols without throwing")
    void testIsolatesLambdaResolutionFailures() {
        // Parse without symbol solver so calculateResolvedType throws UnsolvedSymbolException or IllegalStateException
        String unresolvedCode = """
                package sample;
                public class Unresolved {
                    static UnknownSam lambda = x -> x;
                    void test() {
                        UnknownSam localLambda = x -> x;
                        localLambda = x -> x;
                        this.lambda = x -> x;
                    }
                }
                """;
        CompilationUnit cu = new JavaParser().parse(unresolvedCode).getResult().orElseThrow();
        SourceAnalysis analysis = SourceAnalysis.from(List.of(cu));

        assertThat(analysis.findClassDeclaration("sample.Unresolved")).isPresent();
        assertThat(analysis.findStaticLambdaImplementation("sample.Unresolved", "lambda")).isEmpty();
        assertThat(analysis.lambdaMethodAssignments().getOrDefault("sample.Unresolved.test()", List.of())).isEmpty();

        var lambdas = cu.findAll(com.github.javaparser.ast.expr.LambdaExpr.class);
        assertThatThrownBy(() -> DebugTraceHelper.tryImplementLambdaSam(lambdas.get(0)))
                .isInstanceOf(RuntimeException.class);
    } // testIsolatesLambdaResolutionFailures
} // SourceAnalysisTest
