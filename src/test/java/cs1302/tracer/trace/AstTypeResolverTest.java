package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.CompilationHelper.CompilationResult;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("AstTypeResolver Tests")
public class AstTypeResolverTest {

  @Nested
  @DisplayName("Type Argument Extraction Tests")
  class TypeArgumentExtractionTests {

    @Test
    @DisplayName("should extract simple type arguments")
    void shouldExtractSimpleTypeArguments() {
      List<String> args =
          AstTypeResolver.extractTypeArguments("cs1302.generics.Pair<java.lang.String, java.lang.Integer>");
      assertThat(args).containsExactly("java.lang.String", "java.lang.Integer");
    }

    @Test
    @DisplayName("should extract nested generic type arguments")
    void shouldExtractNestedGenericTypeArguments() {
      List<String> args =
          AstTypeResolver.extractTypeArguments("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>");
      assertThat(args).containsExactly("java.lang.String", "java.util.List<java.lang.Integer>");
    }

    @Test
    @DisplayName("should handle null and non-generic type strings")
    void shouldHandleNullAndNonGeneric() {
      assertThat(AstTypeResolver.extractTypeArguments(null)).isEmpty();
      assertThat(AstTypeResolver.extractTypeArguments("int")).isEmpty();
      assertThat(AstTypeResolver.extractTypeArguments("java.lang.String")).isEmpty();
      assertThat(AstTypeResolver.extractTypeArguments("Invalid<")).isEmpty();
    }
  }

  @Nested
  @DisplayName("Type Substitution Tests")
  class TypeSubstitutionTests {

    @Test
    @DisplayName("should substitute simple type parameter")
    void shouldSubstituteSimpleTypeParameter() {
      Map<String, String> bindings = Map.of("K", "java.lang.String", "V", "java.lang.Integer");
      assertThat(AstTypeResolver.substituteType("K", bindings)).isEqualTo("java.lang.String");
      assertThat(AstTypeResolver.substituteType("V", bindings)).isEqualTo("java.lang.Integer");
      assertThat(AstTypeResolver.substituteType("int", bindings)).isEqualTo("int");
    }

    @Test
    @DisplayName("should substitute nested generic types and arrays")
    void shouldSubstituteNestedGenericsAndArrays() {
      Map<String, String> bindings = Map.of("T", "java.lang.Double");
      assertThat(AstTypeResolver.substituteType("java.util.List<T>", bindings))
          .isEqualTo("java.util.List<java.lang.Double>");
      assertThat(AstTypeResolver.substituteType("T[]", bindings))
          .isEqualTo("java.lang.Double[]");
    }

    @Test
    @DisplayName("should handle null and empty bindings")
    void shouldHandleNullAndEmptyBindings() {
      assertThat(AstTypeResolver.substituteType(null, Map.of("K", "String"))).isNull();
      assertThat(AstTypeResolver.substituteType("K", null)).isEqualTo("K");
      assertThat(AstTypeResolver.substituteType("K", Map.of())).isEqualTo("K");
    }
  }

  @Nested
  @DisplayName("AST Indexing and Type Bindings Tests")
  class AstIndexingTests {

    @Test
    @DisplayName("should extract type bindings from parsed class")
    void shouldExtractTypeBindings() {
      String source =
          """
          package cs1302.test;
          public class Container<K, V> {
              private K key;
              private V value;
              public Container(K key, V value) {
                  this.key = key;
                  this.value = value;
              }
          }
          """;
      CompilationUnit cu = StaticJavaParser.parse(source);
      AstTypeResolver resolver = new AstTypeResolver(cu);

      Optional<AstTypeResolver.ClassGenericInfo> info =
          resolver.getClassGenericInfo("cs1302.test.Container");
      assertThat(info).isPresent();
      assertThat(info.get().typeParameters()).containsExactly("K", "V");
      assertThat(info.get().fieldTypes()).containsEntry("key", "K").containsEntry("value", "V");

      Map<String, String> bindings =
          resolver.getTypeBindings(
              "cs1302.test.Container",
              "cs1302.test.Container<java.lang.String, java.lang.Integer>");
      assertThat(bindings)
          .containsEntry("K", "java.lang.String")
          .containsEntry("V", "java.lang.Integer");

      assertThat(resolver.getTypeBindings("NonExistent", "NonExistent<String>")).isEmpty();
      assertThat(resolver.getTypeBindings(null, null)).isEmpty();
    }

    @Test
    @DisplayName("should resolve variable types and allocation types")
    void shouldResolveVariablesAndAllocations() {
      String source =
          """
          package cs1302.test;
          public class Demo {
              public static void main(String[] args) {
                  String name = "Alice";
                  int count = 10;
              }
          }
          """;
      CompilationUnit cu = StaticJavaParser.parse(source);
      AstTypeResolver resolver = new AstTypeResolver(List.of(cu));

      Optional<String> nameType =
          resolver.resolveVariableType("cs1302.test.Demo", "main", "name", 4);
      assertThat(nameType).isPresent();
      assertThat(nameType.get()).contains("String");

      Optional<String> missing =
          resolver.resolveVariableType("cs1302.test.Demo", "main", "unknown", 4);
      assertThat(missing).isEmpty();

      assertThat(resolver.resolveVariableType("UnknownClass", "main", "name", 4)).isEmpty();
    }
  }

  @Nested
  @DisplayName("Full Generic Tracing Integration Test")
  class GenericTracingIntegrationTests {

    @Test
    @DisplayName("should trace multi-file generic classes with reified types")
    void shouldTraceGenericClassesWithReifiedTypes() throws Exception {
      String source =
          """
          // --- cs1302/generics/Pair.java ---
          package cs1302.generics;

          public class Pair<K, V> {
              private K key;
              private V value;

              public Pair(K key, V value) {
                  this.key = key;
                  this.value = value;
              }

              public K getKey() {
                  return this.key;
              }

              public V getValue() {
                  return this.value;
              }
          }

          // --- cs1302/generics/Driver.java ---
          package cs1302.generics;

          public class Driver {
              public static void main(String[] args) {
                  Pair<String, Integer> score = new Pair<>("Alice", 95);
                  Pair<Integer, Boolean> flag = new Pair<>(101, true);

                  String k1 = score.getKey();
                  Integer v1 = score.getValue();
              }
          }
          """;

      try (CompilationResult compilationResult = CompilationHelper.compile(source, Optional.empty())) {
        com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver combinedTypeSolver =
            new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver();
        combinedTypeSolver.add(new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver());
        combinedTypeSolver.add(
            new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(
                compilationResult.classPath()));
        com.github.javaparser.symbolsolver.JavaSymbolSolver symbolSolver =
            new com.github.javaparser.symbolsolver.JavaSymbolSolver(combinedTypeSolver);
        com.github.javaparser.ParserConfiguration config =
            new com.github.javaparser.ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
        com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser(config);

        List<CompilationHelper.SourceFile> sourceFiles =
            CompilationHelper.parseMultiFileStream(source);
        List<CompilationUnit> allCus =
            sourceFiles.stream()
                .map(sf -> parser.parse(sf.content()).getResult().get())
                .toList();
        Collection<Integer> breakPoints = DebugTraceHelper.getValidBreakpointLines(compilationResult);

        List<ExecutionSnapshot> snapshots =
            DebugTraceHelper.traceChronological(compilationResult, breakPoints, allCus, true);

        assertThat(snapshots).isNotEmpty();

        // Verify that score has reified Pair<String, Integer> type in stack snapshots
        boolean foundScoreReified = false;
        boolean foundFlagReified = false;

        for (ExecutionSnapshot snapshot : snapshots) {
          for (ExecutionSnapshot.StackSnapshot frame : snapshot.stack()) {
            for (ExecutionSnapshot.Field field : frame.visibleVariables()) {
              if ("score".equals(field.identifier())) {
                assertThat(field.typeName()).contains("Pair<").contains("String").contains("Integer");
                foundScoreReified = true;
              }
              if ("flag".equals(field.identifier())) {
                assertThat(field.typeName()).contains("Pair<").contains("Integer").contains("Boolean");
                foundFlagReified = true;
              }
            }
            if (frame.thisObject().isPresent()) {
              String thisType = frame.thisObject().get().typeName();
              if (thisType.contains("Pair<")) {
                assertThat(thisType).matches(".*Pair<.*, .*>.*");
              }
            }
          }
        }

        assertThat(foundScoreReified).isTrue();
        assertThat(foundFlagReified).isTrue();

        // Verify that heap objects also have reified generic types in classFqn
        boolean foundHeapReifiedObject = false;
        for (ExecutionSnapshot snapshot : snapshots) {
          for (TraceValue tv : snapshot.heap().values()) {
            if (tv instanceof TraceValue.Object obj && obj.classFqn().contains("Pair<")) {
              assertThat(obj.classFqn()).matches(".*Pair<.*, .*>.*");
              foundHeapReifiedObject = true;
            }
          }
        }
        assertThat(foundHeapReifiedObject).isTrue();
      }
    }
  }
}
