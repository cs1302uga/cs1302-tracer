package cs1302.tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("CompilationHelper")
public class CompilationHelperTest {

  @Test
  @DisplayName("CompilationHelper constructor coverage")
  void testCompilationHelperConstructor() throws Exception {
    java.lang.reflect.Constructor<CompilationHelper> constructor =
        CompilationHelper.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    CompilationHelper instance = constructor.newInstance();
    assertThat(instance).isNotNull();
  }

  @Nested
  @DisplayName("AST Top-Level Declaration Detection")
  class DeclarationDetection {

    @ParameterizedTest(name = "should compile and detect top-level declaration for: {0}")
    @ValueSource(
        strings = {
          "public class SimpleMain { public static void main(String[] args) {} }",
          "public class VarargsMain { public static void main(String... args) {} }",
          "public class FqnArrMain { public static void main(java.lang.String[] args) {} }",
          "public class FqnVarargsMain { public static void main(java.lang.String... args) {} }",
          "public record RecordMain(String name, int age) { public static void main(String[] args)"
              + " {} }",
          "public enum EnumMain { VAL; public static void main(String[] args) {} }"
        })
    void shouldCompileVariousTopLevelTypes(String source) throws IOException {
      try (CompilationHelper.CompilationResult result = CompilationHelper.compile(source)) {
        assertThat(result).isNotNull();
        assertThat(result.mainClass()).isNotEmpty();
        assertThat(result.compiledClassNames()).isNotEmpty();
        assertThat(result.classPath()).isNotNull();
      }
    }

    @Test
    @DisplayName("should compile class in custom package")
    void shouldCompileCustomPackage() throws IOException {
      String source =
          """
          package com.example.app;
          public class PkgMain {
              public static void main(String[] args) {
                  int a = 1;
              }
          }
          """;
      try (CompilationHelper.CompilationResult result = CompilationHelper.compile(source)) {
        assertThat(result.mainClass()).isEqualTo("com.example.app.PkgMain");
      }
    }

    @Test
    @DisplayName("should detect main in nested static class")
    void shouldDetectNestedMain() throws IOException {
      String source =
          """
          package com.example.app;
          public class OuterClass {
              public static class InnerStaticClass {
                  public static void main(String[] args) {}
              }
          }
          """;
      try (CompilationHelper.CompilationResult result = CompilationHelper.compile(source)) {
        assertThat(result.mainClass()).isEqualTo("com.example.app.OuterClass.InnerStaticClass");
      }
    }

    @Test
    @DisplayName("should fail when source has no public top-level type declaration")
    void shouldThrowWhenNoPublicTopLevelType() {
      String source = "class NonPublic { public static void main(String[] args) {} }";

      assertThatThrownBy(() -> CompilationHelper.compile(source))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must have exactly one public top-level type declaration");
    }

    @Test
    @DisplayName("should fail when source has multiple public top-level type declarations")
    void shouldThrowWhenMultiplePublicTopLevelTypes() {
      String source =
          """
          public class Main1 { public static void main(String[] args) {} }
          public class Main2 {}
          """;

      assertThatThrownBy(() -> CompilationHelper.compile(source))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must have exactly one public top-level type declaration");
    }

    @Test
    @DisplayName("should fail when source has no main method")
    void shouldThrowWhenNoMainMethod() {
      String source = "public class NoMain { public void foo() {} }";

      assertThatThrownBy(() -> CompilationHelper.compile(source))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must have exactly one main method");
    }

    @Test
    @DisplayName("should fail when source has multiple main methods")
    void shouldThrowWhenMultipleMainMethods() {
      String source =
          """
          public class MultiMain {
              public static void main(String[] args) {}
              public static class Inner {
                  public static void main(String[] args) {}
              }
          }
          """;

      assertThatThrownBy(() -> CompilationHelper.compile(source))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must have exactly one main method");
    }
  }

  @Nested
  @DisplayName("Compilation Error Handling & Resource Management")
  class CompilationErrorsAndResources {

    @Test
    @DisplayName("should throw IllegalArgumentException on syntax error during AST parsing")
    void shouldThrowOnSyntaxError() {
      String invalidSyntax =
          "public class BadSyntax { public static void main(String[] args) { int x = ; } }";
      assertThatThrownBy(() -> CompilationHelper.compile(invalidSyntax))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Parsing failed");
    }

    @Test
    @DisplayName("should throw IllegalArgumentException on semantic error during javac compilation")
    void shouldThrowOnJavacError() {
      String invalidType =
          "public class BadType { public static void main(String[] args) { int x = \"not an int\"; } }";
      assertThatThrownBy(() -> CompilationHelper.compile(invalidType))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Compilation of provided Java source code failed with the following messages:");
    }

    @Test
    @DisplayName("should safely close CompilationResult and delete files")
    void shouldCloseCompilationResult() throws IOException {
      String source = "public class CloseTest { public static void main(String[] args) {} }";
      CompilationHelper.CompilationResult result = CompilationHelper.compile(source);
      Path path = result.classPath();
      assertThat(Files.exists(path)).isTrue();
      result.close();
      assertThat(Files.exists(path)).isFalse();

      // Calling close again should not throw
      result.close();

      // Calling close on non-existent or null path
      CompilationHelper.CompilationResult emptyResult =
          new CompilationHelper.CompilationResult(null, Set.of(), "Dummy");
      emptyResult.close();
    }
  }

  @Nested
  @DisplayName("Source Root Discovery & Multi-File Package Compilation")
  class SourceRootAndMultiFile {

    @Test
    @DisplayName("should find source root for file with matching package directory structure")
    void shouldFindSourceRootForPackagedFile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
      Path pkgDir = Files.createDirectories(tempDir.resolve("a/b/c"));
      Path filePath = pkgDir.resolve("Foo.java");
      Files.writeString(filePath, "package a.b.c; public class Foo {}");

      com.github.javaparser.ast.CompilationUnit cu =
          com.github.javaparser.StaticJavaParser.parse("package a.b.c; public class Foo {}");
      var sourceRoot = CompilationHelper.findSourceRoot(cu, java.util.Optional.of(filePath));

      assertThat(sourceRoot).isPresent();
      assertThat(sourceRoot.get()).isEqualTo(tempDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("should return empty source root for file without package declaration")
    void shouldFindSourceRootForUnpackagedFile() {
      com.github.javaparser.ast.CompilationUnit cu =
          com.github.javaparser.StaticJavaParser.parse("public class Foo {}");
      Path filePath = Path.of("/root/project/Foo.java");
      var sourceRoot = CompilationHelper.findSourceRoot(cu, java.util.Optional.of(filePath));

      assertThat(sourceRoot).isEmpty();
    }

    @Test
    @DisplayName("should return empty when file structure does not match package")
    void shouldReturnEmptyWhenHierarchyMismatched() {
      com.github.javaparser.ast.CompilationUnit cu =
          com.github.javaparser.StaticJavaParser.parse("package x.y.z; public class Foo {}");
      Path filePath = Path.of("/root/other/Foo.java");
      var sourceRoot = CompilationHelper.findSourceRoot(cu, java.util.Optional.of(filePath));

      assertThat(sourceRoot).isEmpty();
    }

    @Test
    @DisplayName("should return empty source root for stdin without package")
    void shouldReturnEmptyForStdinUnpackaged() {
      com.github.javaparser.ast.CompilationUnit cu =
          com.github.javaparser.StaticJavaParser.parse("public class Foo {}");
      var sourceRoot = CompilationHelper.findSourceRoot(cu, java.util.Optional.empty());

      assertThat(sourceRoot).isEmpty();
    }

    @Test
    @DisplayName("should return empty source root for stdin with unknown package")
    void shouldReturnEmptyForUnknownPackageStdin() {
      com.github.javaparser.ast.CompilationUnit cu =
          com.github.javaparser.StaticJavaParser.parse("package non.existent.pkg; public class Foo {}");
      var sourceRoot = CompilationHelper.findSourceRoot(cu, java.util.Optional.empty());

      assertThat(sourceRoot).isEmpty();
    }

    @Test
    @DisplayName("should compile multi-file package with source root")
    void shouldCompileMultiFilePackage(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
      Path pkgDir = Files.createDirectories(tempDir.resolve("pkg/sample"));
      Files.writeString(
          pkgDir.resolve("Helper.java"),
          "package pkg.sample; public class Helper { public static int getValue() { return 42; } }");

      String driverSource =
          """
          package pkg.sample;
          public class MainDriver {
              public static void main(String[] args) {
                  int val = Helper.getValue();
              }
          }
          """;

      try (CompilationHelper.CompilationResult result =
          CompilationHelper.compile(driverSource, java.util.Optional.of(tempDir))) {
        assertThat(result).isNotNull();
        assertThat(result.mainClass()).isEqualTo("pkg.sample.MainDriver");
        assertThat(result.compiledClassNames()).contains("pkg.sample.MainDriver", "pkg.sample.Helper");
      }
    }
  }

  @Nested
  @DisplayName("Multi-File Stdin Streaming")
  class MultiFileStreaming {

    @Test
    @DisplayName("should throw when raw input is null")
    void shouldThrowWhenRawInputIsNull() {
      assertThatThrownBy(() -> CompilationHelper.parseMultiFileStream(null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Input source cannot be null");
    }

    @Test
    @DisplayName("should throw when parsing fails on multi-file stream segment")
    void shouldThrowWhenSegmentFailsToParse() {
      String badStream =
          """
          // --- Valid.java ---
          public class Valid {}
          // --- Broken.java ---
          class Broken {{{ invalid syntax
          """;

      assertThatThrownBy(() -> CompilationHelper.parseMultiFileStream(badStream))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Parsing failed for Broken.java");
    }

    @Test
    @DisplayName("should parse multi-file stream with varied delimiters and package inference")
    void shouldParseMultiFileStream() {
      String multiFileStream =
          """
          // --- cs1302/math/Calculator.java ---
          package cs1302.math;
          public class Calculator {
              public static int add(int a, int b) { return a + b; }
          }
          // === Driver.java ===
          package cs1302.math;
          public class Driver {
              public static void main(String[] args) {
                  int sum = Calculator.add(2, 3);
              }
          }
          """;

      var sourceFiles = CompilationHelper.parseMultiFileStream(multiFileStream);
      assertThat(sourceFiles).hasSize(2);
      assertThat(sourceFiles.get(0).relativePath()).contains("cs1302", "math", "Calculator.java");
      assertThat(sourceFiles.get(1).relativePath()).contains("cs1302", "math", "Driver.java");

      var entryPoint = CompilationHelper.findEntryPoint(sourceFiles);
      assertThat(entryPoint.relativePath()).contains("Driver.java");

      var combinedCu = CompilationHelper.combineCompilationUnits(sourceFiles);
      assertThat(combinedCu.getTypes()).hasSize(2);
    }

    @Test
    @DisplayName("should handle helper methods corner cases for entry point and combined units")
    void shouldHandleHelperCornerCases() {
      assertThatThrownBy(() -> CompilationHelper.findEntryPoint(java.util.Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No source files provided");

      assertThatThrownBy(() -> CompilationHelper.combineCompilationUnits(java.util.Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No source files provided");

      String singleSource = "public class Single { public static void main(String[] args) {} }";
      var singleList = CompilationHelper.parseMultiFileStream(singleSource);
      assertThat(CompilationHelper.combineCompilationUnits(singleList)).isSameAs(singleList.get(0).ast());

      String noMain =
          """
          // --- A.java ---
          public class A {}
          // --- B.java ---
          public class B {}
          """;
      var noMainList = CompilationHelper.parseMultiFileStream(noMain);
      assertThat(CompilationHelper.findEntryPoint(noMainList).relativePath()).isEqualTo("A.java");
    }

    @Test
    @DisplayName("should compile and run multi-file stream directly from memory without disk files")
    void shouldCompileMultiFileStreamInMemory() throws IOException {
      String stream =
          """
          // --- cs1302/util/Greeter.java ---
          package cs1302.util;
          public class Greeter {
              public static String getGreeting(String name) {
                  return "Hello, " + name + "!";
              }
          }
          // --- cs1302/util/Main.java ---
          package cs1302.util;
          public class Main {
              public static void main(String[] args) {
                  String msg = Greeter.getGreeting("World");
                  System.out.println(msg);
              }
          }
          """;

      try (CompilationHelper.CompilationResult result = CompilationHelper.compile(stream)) {
        assertThat(result).isNotNull();
        assertThat(result.mainClass()).isEqualTo("cs1302.util.Main");
        assertThat(result.compiledClassNames())
            .contains("cs1302.util.Greeter", "cs1302.util.Main");
      }
    }
  }
}
