package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobOutputTest {

    @Test
    void freezesRetainedOutputWhenTheSessionStopsAtItsOutputLimit() throws Exception {
        cs1302.tracer.execution.TraceLimits limits = new cs1302.tracer.execution.TraceLimits(0, 0, 3, 0, 0, 0, 0, 0);
        try (cs1302.tracer.execution.TraceSession session = new cs1302.tracer.execution.TraceSession(
                limits, cs1302.tracer.execution.InspectionPolicy.FIELDS, true);
                StreamDrainer out = new StreamDrainer(new java.io.ByteArrayInputStream(
                        "too much output".getBytes(StandardCharsets.UTF_8)));
                StreamDrainer err = new StreamDrainer(java.io.InputStream.nullInputStream())) {
            JobOutput job = JobOutput.fromStart(out, err);
            out.waitForEof(1000);
            err.waitForEof(1000);
            JobOutput.Finished finished = job.finish();
            assertThat(session.stopReason()).isEqualTo("output_limit");
            assertThat(finished.stdout().asUtf8String()).isEqualTo("too");
            out.reset();
            assertThat(job.finish()).isSameAs(finished);
            assertThat(finished.stdout().asUtf8String()).isEqualTo("too");
        }
    }

    @Test
    void freezesTrailingOutputAndSurvivesConsecutiveJobs() throws Exception {
        try (PipedInputStream outInput = new PipedInputStream();
                PipedOutputStream outWriter = new PipedOutputStream(outInput);
                PipedInputStream errInput = new PipedInputStream();
                PipedOutputStream errWriter = new PipedOutputStream(errInput);
                StreamDrainer out = new StreamDrainer(outInput);
                StreamDrainer err = new StreamDrainer(errInput)) {
            outWriter.write("previous".getBytes(StandardCharsets.UTF_8));
            out.syncUntil(8, 1000);
            JobOutput job = JobOutput.begin(out, err);
            outWriter.write("first".getBytes(StandardCharsets.UTF_8));
            errWriter.write("Picked up JAVA_TOOL_OPTIONS: test\nwarning".getBytes(StandardCharsets.UTF_8));
            job.drain(5, 41);
            JobOutput.Prefix captured = job.capture();
            assertThat(captured.rawBytes()).isEqualTo(46);
            ExecutionSnapshot prefix = new ExecutionSnapshot(List.of(), List.of(), Map.of(),
                    captured.stdout(), captured.stderr(), Optional.empty(), "", 0);
            outWriter.write(" trailing".getBytes(StandardCharsets.UTF_8));
            job.drain(14, 41);
            JobOutput.Finished finished = job.finish();
            ExecutionSnapshot detached = finished.detach(prefix);
            assertThat(finished.stdout().asUtf8String()).isEqualTo("first trailing");
            assertThat(finished.stderr().asUtf8String()).isEqualTo("warning");
            assertThat(detached.stdoutSlice().asUtf8String()).isEqualTo("first");
            out.reset();
            err.reset();
            JobOutput next = JobOutput.begin(out, err);
            outWriter.write("second".getBytes(StandardCharsets.UTF_8));
            next.drain(6, 0);
            assertThat(next.finish().stdout().asUtf8String()).isEqualTo("second");
            assertThat(next.finish().stderr().isEmpty()).isTrue();
            job.drain(0, 0);
            assertThat(job.finish()).isSameAs(finished);
            assertThat(job.stdout().asUtf8String()).isEqualTo("first trailing");
            assertThat(detached.stdoutSlice().asUtf8String()).isEqualTo("first");
            assertThat(detached.stderrSlice().asUtf8String()).isEqualTo("warning");
        }
    }
}
