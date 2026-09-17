package cs1302.tracer;

import static org.assertj.core.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompilationPreviewTest {
    @Test
    void absentSourceDoesNotRequirePreview() {
        assertThat(CompilationHelper.detectPreviewUsage((CompilationUnit) null)).isFalse();
        assertThat(CompilationHelper.detectPreviewUsage((List<CompilationUnit>) null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"java.lang.IO", "java.io.IO", "java.lang.IO.Nested", "java.io.IO.Nested"})
    void recognizesIoImports(String imported) {
        assertThat(CompilationHelper.detectPreviewUsage(StaticJavaParser.parse(
                "import " + imported + "; class Main {}"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IO", "java.lang.IO", "java.io.IO"})
    void recognizesQualifiedIoCalls(String scope) {
        assertThat(CompilationHelper.detectPreviewUsage(StaticJavaParser.parse(
                "class Main { public static void main(String[] a) { " + scope + ".readln(); } }")))
                .isTrue();
    }

    @Test
    void packagePrivateStaticMainRequiresPreviewDetection() {
        assertThat(CompilationHelper.detectPreviewUsage(StaticJavaParser.parse(
                "class Main { static void main(String[] a) {} }"))).isTrue();
    }

    @Test
    void parsesSourceWithoutATypeSolver() {
        var unit = new App.Trace().parseSource("class Main {}");
        assertThat(unit.getType(0).getNameAsString()).isEqualTo("Main");
    }
}
