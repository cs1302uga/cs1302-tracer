package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ReferenceType;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.execution.JobOptions;
import java.io.File;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BreakpointSpecTest {

    @Test
    @DisplayName("parses unqualified and qualified breakpoint specs")
    void testParseValid() {
        BreakpointSpec unq1 = BreakpointSpec.parse("15");
        assertThat(unq1.lineNumber()).isEqualTo(15);
        assertThat(unq1.file().isPresent()).isFalse();
        assertThat(unq1.file()).isEmpty();
        assertThat(unq1.toString()).isEqualTo("15");

        BreakpointSpec unq2 = BreakpointSpec.of(-1);
        assertThat(unq2.lineNumber()).isEqualTo(-1);
        assertThat(unq2.file().isPresent()).isFalse();

        BreakpointSpec blankFile = BreakpointSpec.of("   ", 10);
        assertThat(blankFile.file()).isEmpty();

        BreakpointSpec qual1 = BreakpointSpec.parse("Main.java:20");
        assertThat(qual1.lineNumber()).isEqualTo(20);
        assertThat(qual1.file().isPresent()).isTrue();
        assertThat(qual1.file()).contains("Main.java");
        assertThat(qual1.toString()).isEqualTo("Main.java:20");

        BreakpointSpec qual2 = BreakpointSpec.parse("  pkg/Sub.java : 42  ");
        assertThat(qual2.lineNumber()).isEqualTo(42);
        assertThat(qual2.file()).contains("pkg/Sub.java");

        BreakpointSpec qual3 = BreakpointSpec.of("pkg/Sub.java", 42);
        assertThat(qual3).isEqualTo(qual2);
        assertThat(qual3.hashCode()).isEqualTo(qual2.hashCode());
    } // testParseValid

    @Test
    @DisplayName("rejects invalid breakpoint strings")
    void testParseInvalid() {
        assertThatThrownBy(() -> BreakpointSpec.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("Main.java:"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse(":15"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("Main.java:abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("Main.java:15:20"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("-2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("Main.java:0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.parse("Main.java:-5"))
                .isInstanceOf(IllegalArgumentException.class);
    } // testParseInvalid

    @Test
    @DisplayName("rejects non-positive line numbers during construction")
    void testConstructInvalid() {
        assertThatThrownBy(() -> BreakpointSpec.of(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.of(-2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.of("Main.java", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BreakpointSpec.of("Main.java", -5))
                .isInstanceOf(IllegalArgumentException.class);
    } // testConstructInvalid

    @Test
    @DisplayName("matches source path by filename or full path")
    void testMatchesSourcePath() {
        BreakpointSpec unqualified = BreakpointSpec.of(10);
        assertThat(unqualified.matchesSourcePath("Main.java")).isTrue();
        assertThat(unqualified.matchesSourcePath("pkg/Main.java")).isTrue();
        assertThat(unqualified.matchesSourcePath(null)).isTrue();

        BreakpointSpec specSimple = BreakpointSpec.of("Main.java", 10);
        assertThat(specSimple.matchesSourcePath("Main.java")).isTrue();
        assertThat(specSimple.matchesSourcePath("pkg/Main.java")).isTrue();
        assertThat(specSimple.matchesSourcePath("pkg" + File.separator + "Main.java")).isTrue();
        assertThat(specSimple.matchesSourcePath("Other.java")).isFalse();
        assertThat(specSimple.matchesSourcePath(null)).isFalse();
        assertThat(specSimple.matchesSourcePath("   ")).isFalse();

        BreakpointSpec specNoExt = BreakpointSpec.of("Main", 10);
        assertThat(specNoExt.matchesSourcePath("Main.java")).isTrue();
        assertThat(specNoExt.matchesSourcePath("pkg/Main.java")).isTrue();
        assertThat(specNoExt.matchesSourcePath("pkg.Main")).isTrue();

        BreakpointSpec specPath = BreakpointSpec.of("pkg/Main.java", 10);
        assertThat(specPath.matchesSourcePath("pkg/Main.java")).isTrue();
        assertThat(specPath.matchesSourcePath("root/pkg/Main.java")).isTrue();
        assertThat(specPath.matchesSourcePath("other/Main.java")).isFalse();
    } // testMatchesSourcePath

    @Test
    @DisplayName("matches reference type with proxy ReferenceType")
    void testMatchesReferenceType() {
        BreakpointSpec unqualified = BreakpointSpec.of(10);
        ReferenceType refTypeSimple = createProxyReferenceType("Main.java", "sample.Main", List.of("Main.java"));
        assertThat(unqualified.matchesReferenceType(refTypeSimple)).isTrue();
        assertThat(unqualified.matchesReferenceType(null)).isTrue();

        BreakpointSpec spec = BreakpointSpec.of("Main.java", 10);
        assertThat(spec.matchesReferenceType(null)).isFalse();
        assertThat(spec.matchesReferenceType(refTypeSimple)).isTrue();

        ReferenceType refTypeAbsent = createProxyReferenceTypeWithAbsentInfo("sample.UnknownAbsent");
        assertThat(spec.matchesReferenceType(refTypeAbsent)).isFalse();

        ReferenceType refTypeNullInfo = createProxyReferenceType(null, "sample.Other", null);
        assertThat(spec.matchesReferenceType(refTypeNullInfo)).isFalse();

        ReferenceType refTypeSourceName = createProxyReferenceType(
                "Main.java", "sample.DifferentName", List.of("Main.java"));
        assertThat(spec.matchesReferenceType(refTypeSourceName)).isTrue();

        ReferenceType refTypeOtherPaths = createProxyReferenceType("Other.java", "sample.Other", List.of("pkg/Main.java"));
        assertThat(spec.matchesReferenceType(refTypeOtherPaths)).isTrue();

        ReferenceType refTypeMismatch = createProxyReferenceType("Other.java", "sample.Other", List.of("pkg/Mismatch.java"));
        assertThat(spec.matchesReferenceType(refTypeMismatch)).isFalse();
    } // testMatchesReferenceType

    @Test
    @DisplayName("resolves breakpoint specs against ASM valid line map")
    void testResolveBreakpointSpecs() {
        Map<String, Set<Integer>> validLines = Map.of(
                "sample/Main.java", Set.of(5, 10, 15),
                "sample/Helper.java", Set.of(3, 7, 12));

        assertThat(BreakpointReader.resolve(validLines, null)).isSameAs(validLines);
        assertThat(BreakpointReader.resolve(validLines, List.of())).isSameAs(validLines);

        List<BreakpointSpec> specs = List.of(
                BreakpointSpec.of(5),
                BreakpointSpec.of("Helper.java", 7),
                BreakpointSpec.of("Helper.java", 99),
                BreakpointSpec.of("Nonexistent.java", 5),
                BreakpointSpec.of(-1));

        Map<String, Set<Integer>> resolved = BreakpointReader.resolve(validLines, specs);
        assertThat(resolved.get("sample/Main.java")).contains(5, -1);
        assertThat(resolved.get("sample/Helper.java")).contains(7, -1);
    } // testResolveBreakpointSpecs

    @Test
    @DisplayName("JobOptions parses breakpoint strings and splits comma separated values")
    void testJobOptionsParsing() {
        assertThat(JobOptions.parseBreakpoints(null)).isEmpty();
        assertThat(JobOptions.parseBreakpoints(List.of())).isEmpty();

        List<String> rawWithBlanks = Arrays.asList(null, "   ", "5", "Main.java:10, , Helper.java:12", "15");
        List<BreakpointSpec> parsed = JobOptions.parseBreakpoints(rawWithBlanks);
        assertThat(parsed).containsExactly(
                BreakpointSpec.of(5),
                BreakpointSpec.of("Main.java", 10),
                BreakpointSpec.of("Helper.java", 12),
                BreakpointSpec.of(15));

        JobOptions options = new JobOptions();
        options.breakpoints = List.of("8", "Other.java:9");
        assertThat(options.breakpointSpecs()).containsExactly(
                BreakpointSpec.of(8),
                BreakpointSpec.of("Other.java", 9));
    } // testJobOptionsParsing

    @Test
    @DisplayName("BreakpointReader.resolve with CompilationResult")
    void testResolveWithCompilationResult() throws Exception {
        String source = """
                public class Main {
                    public static void main(String[] args) {
                        int a = 1;
                        int b = 2;
                    }
                }
                """;
        try (var compiled = CompilationHelper.compile(source)) {
            List<BreakpointSpec> specs = List.of(
                    BreakpointSpec.of("Main.java", 3),
                    BreakpointSpec.of(4),
                    BreakpointSpec.of("Other.java", 3));
            Map<String, Set<Integer>> resolved = BreakpointReader.resolve(compiled, specs);
            assertThat(resolved.values().stream().flatMap(Set::stream).toList()).contains(3, 4);
        } // try
    } // testResolveWithCompilationResult

    private static ReferenceType createProxyReferenceType(
            String sourceName, String fqn, List<String> sourcePaths) {
        return (ReferenceType) Proxy.newProxyInstance(
                BreakpointSpecTest.class.getClassLoader(),
                new Class<?>[] {ReferenceType.class},
                (proxy, method, args) -> {
                    if ("sourceName".equals(method.getName())) {
                        return sourceName;
                    } // if
                    if ("name".equals(method.getName())) {
                        return fqn;
                    } // if
                    if ("sourcePaths".equals(method.getName())) {
                        return sourcePaths;
                    } // if
                    return null;
                });
    } // createProxyReferenceType

    private static ReferenceType createProxyReferenceTypeWithAbsentInfo(String fqn) {
        return (ReferenceType) Proxy.newProxyInstance(
                BreakpointSpecTest.class.getClassLoader(),
                new Class<?>[] {ReferenceType.class},
                (proxy, method, args) -> {
                    if ("sourceName".equals(method.getName()) || "sourcePaths".equals(method.getName())) {
                        throw new AbsentInformationException();
                    } // if
                    if ("name".equals(method.getName())) {
                        return fqn;
                    } // if
                    return null;
                });
    } // createProxyReferenceTypeWithAbsentInfo
} // BreakpointSpecTest
