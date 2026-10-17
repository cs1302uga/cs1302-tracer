package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.*;

import cs1302.tracer.api.TraceClassPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryCompilationPolicyTest {

    @TempDir Path root;

    @Test
    void dependencyProcessorsAreNeverDiscoveredDuringGuestCompilation() throws Exception {
        Path processor = Files.writeString(root.resolve("ExplodingProcessor.java"), """
                public class ExplodingProcessor extends javax.annotation.processing.AbstractProcessor {
                    public java.util.Set<String> getSupportedAnnotationTypes() { return java.util.Set.of("*"); }
                    public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                        return javax.lang.model.SourceVersion.latestSupported();
                    }
                    public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> types,
                            javax.annotation.processing.RoundEnvironment round) {
                        throw new AssertionError("dependency processor executed");
                    }
                }
                """);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                root.toString(), processor.toString())).isZero();
        Path service = root.resolve("META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "ExplodingProcessor\n");
        try (var prepared = SourcePreparation.prepare(
                "public class Main { public static void main(String[] args) {} }", Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(root).build())) {
            assertThat(prepared.compilation().compiledClassNames()).containsExactly("Main");
        }
    }

    @Test
    void neighboringSourcesRemainDiscoverableButBinaryOnlyNeighborsRequireExplicitPaths() throws Exception {
        Path helper = root.resolve("dep/Helper.java");
        Files.createDirectories(helper.getParent());
        String text = "package dep; public class Helper {}";
        Files.writeString(helper, text);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                root.toString(), helper.toString())).isZero();
        Files.delete(helper);
        String source = "public class Main extends dep.Helper { public static void main(String[] args) {} }";
        assertThatThrownBy(() -> CompilationHelper.compile(source, Optional.of(root)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Compilation");
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(root).build())) {
            assertThat(prepared.compilation().compiledClassNames()).containsExactly("Main");
        }
        Files.writeString(helper, text);
        try (var compiled = CompilationHelper.compile(source, Optional.of(root))) {
            assertThat(compiled.compiledClassNames()).contains("Main", "dep.Helper");
        }
    }

    @Test
    void preparationClosesDependenciesAfterInvalidSourcesAndPreservesOriginalFailures() throws Exception {
        var config = TraceClassPath.builder().addPath(root).copy(true).build();
        assertThatThrownBy(() -> SourcePreparation.prepare("invalid", Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, config)).isInstanceOf(IllegalArgumentException.class);
        var failure = new IllegalArgumentException("primary");
        var cleanup = new IOException("cleanup");
        SourcePreparation.closeAfterFailure(() -> { throw cleanup; }, failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanup);
        SourcePreparation.closeAfterFailure(() -> {}, failure);
        assertThat(failure.getSuppressed()).containsExactly(cleanup);
        assertThat(root).exists();
    }

    @Test
    void closingPreparationStillReleasesDependencyOwnershipAfterCompilationCleanupFailure() throws Exception {
        Path dependency = Files.createDirectory(root.resolve("dep"));
        var config = TraceClassPath.builder().addPath(dependency).copy(true).build();
        var dependencies = cs1302.tracer.internal.classpath.PreparedClassPath.prepare(config);
        Path captured = dependencies.entries().getFirst();
        Path output = Files.createDirectory(root.resolve("compiled"));
        var compiled = new CompilationHelper.CompilationResult(FaultPaths.refusingDeletion(output),
                java.util.Set.of(), "Main");
        var preparation = new SourcePreparation(compiled, List.of(), dependencies);
        assertThatThrownBy(preparation::close).isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(captured).doesNotExist();
    }

    @Test
    void compatibilityPreparationConstructorStillOwnsCompiledOutput() throws Exception {
        Path output = Files.createDirectory(root.resolve("compiled"));
        var compiled = new CompilationHelper.CompilationResult(output, java.util.Set.of(), "Main");
        try (var preparation = new SourcePreparation(compiled, List.of())) {
            assertThat(preparation.compilation()).isSameAs(compiled);
            assertThat(preparation.dependencies()).isNull();
        }
        assertThat(output).doesNotExist();
    }

}
