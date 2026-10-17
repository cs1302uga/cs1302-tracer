package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class SourceNamesTest {

    @Test
    void utilityConstructorIsPrivate() throws Exception {
        var constructor = SourceNames.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        constructor.newInstance();
    }

    @Test
    void infersPublicTypeAmongHelpersAndIgnoresNestedTypes() {
        assertThat(SourceNames.inferPath("""
                package demo.nested;
                class Helper {}
                public class Main { public static class Nested {} }
                """)).isEqualTo("demo/nested/Main.java");
        assertThat(SourceNames.inferPath("public interface Contract {}"))
                .isEqualTo("Contract.java");
    }

    @Test
    void infersSoleNonpublicTypes() {
        for (String source : new String[] { "class Helper {}", "interface Helper {}",
                "enum Helper { VALUE }", "record Helper(int number) {}", "@interface Helper {}" }) {
            assertThat(SourceNames.inferPath(source)).isEqualTo("Helper.java");
        }
    }

    @Test
    void rejectsUnparseableEmptyAndAmbiguousInference() {
        assertThatIllegalArgumentException().isThrownBy(() -> SourceNames.inferPath("class {"))
                .withMessageContaining("Cannot infer source path");
        for (String source : new String[] { "", "class A {} class B {}",
                "public class A {} public class B {}", "module demo {}" }) {
            assertThatIllegalArgumentException().isThrownBy(() -> SourceNames.inferPath(source))
                    .withMessageContaining("Supply an explicit source path");
        }
    }

    @Test
    void preservesStrictUndelimitedInference() {
        assertThat(SourceNames.requirePublicBinaryName(StaticJavaParser.parse(
                "package demo; class Helper {} public class Main {}"))).isEqualTo("demo.Main");
        assertThatIllegalArgumentException().isThrownBy(() -> CompilationHelper.parseMultiFileStream(
                "class Helper {}"))
                .withMessage("Java source code must have exactly one public top-level type "
                        + "declaration. Found 0 such declarations on lines [].");
        assertThatIllegalArgumentException().isThrownBy(() -> SourceNames.requirePublicBinaryName(
                StaticJavaParser.parse("public class A {}\npublic class B {}")))
                .withMessageContaining("Found 2 such declarations on lines [1, 2]");
    }

    @Test
    void diagnosesSyntheticDeclarationsWithoutSourceRanges() {
        CompilationUnit unit = new CompilationUnit();
        unit.addClass("First");
        unit.addClass("Second");
        assertThatIllegalArgumentException().isThrownBy(() -> SourceNames.requirePublicBinaryName(unit))
                .withMessageContaining("Found 2 such declarations on lines []");
    }

}
