package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.internal.execution.TraceCompletion;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.execution.TraceSession;
import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.trace.TraceDispatch;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Optional;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(60)
class SourceRecoveryContractTest {

    private static final String SOURCE = "public class Main { public static void main(String[] args) { "
            + "System.out.print(42); } }";

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void realPreparationCleanupFailureRetainsDetachedRecoveryAfterFilesDisappear(TraceFormat format) throws Exception {
        TraceJob job = TraceJob.fromJava(TraceRequest.builder(SOURCE).format(format).chronological(true)
                .inspection(InspectionPolicy.FIELDS).build(), false);
        try (SourcePreparation original = SourcePreparation.prepare(SOURCE, Optional.empty(), job.discovery());
                TraceSession session = new TraceSession(job.limits(), job.inspection(), true, job.evalEnumHash())) {
            var compiled = original.compilation();
            var failing = new CompilationHelper.CompilationResult(FaultPaths.refusingDeletion(compiled.classPath()),
                    compiled.compiledClassNames(), compiled.mainClass(), compiled.sourceRoot(),
                    compiled.previewEnabled(), compiled.dependencies());
            SubmittedSources submitted = original.submitted();
            try {
                try (SourcePreparation prepared = new SourcePreparation(failing, original.units(),
                        original.dependencies(), submitted)) {
                    session.phase("trace");
                    var captured = TraceDispatch.dedicated().capture(prepared, job.capture().selection(), "");
                    TraceCompletion.workerSuccess(submitted, "", job, session, captured);
                }
                throw new AssertionError("Expected deterministic compilation-directory cleanup failure");
            } catch (UncheckedIOException failure) {
                compiled.close();
                assertThat(Files.exists(compiled.classPath())).isFalse();
                Object payload = TraceCompletion.workerRecovery(submitted, "", job, session, failure);
                var outcome = TraceRendering.freeze(session.result(format.toString(), payload, failure), false);
                var trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
                assertThat(trace.getAsJsonObject("sources").get("Main.java").getAsString()).isEqualTo(SOURCE);
                assertThat(outcome.stdout()).isEqualTo("42");
                assertThat(outcome.phase()).isEqualTo("serialize");
                assertThat(outcome.complete()).isFalse();
                assertThat(outcome.diagnostics()).contains(failure.toString());
            }
        }
    }

}
