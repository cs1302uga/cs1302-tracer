package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.serialize.ModernTraceSerializer;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

@Timeout(60)
class SourceIdentityContractTest {

    private static final String AMBIGUOUS = "// --- a/Common.java ---\n"
            + "class A { public static void main(String[] args) { System.out.print(\"ran\"); } }\n"
            + "// --- b/Common.java ---\nclass B {}\n";
    private static final ExecutionSnapshot INVALID = new ExecutionSnapshot(null, List.of(), Map.of(),
            new byte[0], new byte[0]);
    private final ModernTraceSerializer modern = new ModernTraceSerializer(false, false, false);
    private final PyTutorSerializer pytutor = new PyTutorSerializer(false, false, false);

    @Test
    void singleSnapshotFormatsKeepTheirCompetingFailureOrder() {
        assertThatThrownBy(() -> this.modern.createTrace(AMBIGUOUS, INVALID))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.pytutor.createTrace(AMBIGUOUS, INVALID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
    }

    @Test
    void sequencesAndModernBreakpointMapsConvertBeforeSourceProjection() {
        assertThatThrownBy(() -> this.modern.createTrace(AMBIGUOUS, List.of(INVALID)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.pytutor.createTrace(AMBIGUOUS, List.of(INVALID)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.modern.createBreakpointsTrace(AMBIGUOUS, Map.of(1, INVALID)))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryPythonTutorBreakpointsProjectSourcesBeforeConvertingSteps(boolean accumulate) {
        TraceJob job = TraceJob.resolve(TraceJob.Profile.CLI, TraceFormat.PYTUTOR, TypeStyle.FQN,
                null, null, List.of("1"), false, accumulate, false, false, false, false, true, false);
        assertThatThrownBy(() -> TraceRendering.ordinary(CompilationHelper.parseSources(AMBIGUOUS).submitted(), "", job,
                new TraceDispatch.Breakpoints(Map.of(1, List.of(INVALID)), accumulate), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate debug source path: Common.java");
    }

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void ambiguousDebugPathsCompileAndExecuteBeforeRenderingFails(TraceFormat format) throws Exception {
        try (var prepared = SourcePreparation.prepare(AMBIGUOUS, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY)) {
            assertThat(prepared.compilation().compiledClassNames()).contains("A", "B");
        }
        var outcome = Tracer.trace(TraceRequest.builder(AMBIGUOUS).format(format)
                .chronological(true).inspection(InspectionPolicy.FIELDS).build());
        assertThat(outcome.stdout()).isEqualTo("ran");
        assertThat(outcome.phase()).isEqualTo("serialize");
        assertThat(outcome.complete()).isFalse();
        assertThat(String.join("\n", outcome.diagnostics())).contains("Duplicate debug source path: Common.java");
    }

    @Test
    void ordinaryPreparationKeepsDependencyFailureBeforeInvalidSource(@TempDir Path root) {
        TraceClassPath dependencies = TraceClassPath.builder().addPath(root.resolve("missing.jar")).build();
        assertThatThrownBy(() -> SourcePreparation.prepare("invalid Java", Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, dependencies))
                .isInstanceOf(java.nio.file.NoSuchFileException.class)
                .hasMessage(root.resolve("missing.jar").toString());
    }

    @Test
    void breakpointListingKeepsInvalidSourceBeforeDependencyFailure(@TempDir Path root) throws Exception {
        Path input = Files.writeString(root.resolve("Main.java"), "invalid Java");
        App.ListBreakpoints command = new App.ListBreakpoints();
        AtomicInteger exit = new AtomicInteger();
        command.exitHandler = exit::set;
        new CommandLine(command).parseArgs("--input", input.toString(), "--class-path",
                root.resolve("missing.jar").toString());
        PrintStream previous = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try (PrintStream captured = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            command.run();
        } finally {
            System.setErr(previous);
        }
        assertThat(exit).hasValue(1);
        assertThat(errors.toString(StandardCharsets.UTF_8)).contains("Parsing failed")
                .doesNotContain("Class-path entry is not a directory");
    }

}
