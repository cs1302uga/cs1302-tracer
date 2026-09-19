package cs1302.tracer.batch;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.TypeStyle;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for batch job models and envelopes.
 */
class BatchJobModelTest {

    @Test
    @DisplayName("BatchJobRequest defaults format and limits when null")
    void testRequestDefaults() {
        BatchJobRequest req = new BatchJobRequest(
                "id-1", "class A {}", null, null, null, null, null, null, null, null, null, null, null);
        assertThat(req.id()).isEqualTo("id-1");
        assertThat(req.source()).isEqualTo("class A {}");
        assertThat(req.resolveFormat()).isEqualTo(TraceFormat.PYTUTOR);
        assertThat(req.resolveTypeStyle()).isEqualTo(TypeStyle.FQN);
        assertThat(req.resolveLimits()).isEqualTo(TraceLimits.instructorDefaults());

        BatchJobRequest unlimReq = new BatchJobRequest(
                "id-unlim", "class A {}", null, null, null, null,
                null, null, null, null, null, TraceLimits.unlimited(), null);
        assertThat(unlimReq.resolveLimits()).isEqualTo(TraceLimits.unlimited());
    } // testRequestDefaults

    @Test
    @DisplayName("BatchJobRequest resolves explicit modern format and simple style")
    void testRequestExplicitValues() {
        TraceLimits limits = TraceLimits.instructorDefaults();
        BatchJobRequest req = new BatchJobRequest(
                "id-2", "class B {}", "modern", "input", List.of("5"),
                true, true, true, true, true, "simple", limits, InspectionPolicy.FIELDS);
        assertThat(req.resolveFormat()).isEqualTo(TraceFormat.MODERN);
        assertThat(req.resolveTypeStyle()).isEqualTo(TypeStyle.SIMPLE);

        BatchJobRequest blankReq = new BatchJobRequest(
                "id-blank", "class C {}", "   ", null, null,
                null, null, null, null, null, "   ", null, null);
        assertThat(blankReq.resolveFormat()).isEqualTo(TraceFormat.PYTUTOR);
        assertThat(blankReq.resolveTypeStyle()).isEqualTo(TypeStyle.FQN);

        BatchJobRequest pytutorFqnReq = new BatchJobRequest(
                "id-pytutor", "class D {}", "pytutor", null, null,
                null, null, null, null, null, "fqn", null, null);
        assertThat(pytutorFqnReq.resolveFormat()).isEqualTo(TraceFormat.PYTUTOR);
        assertThat(pytutorFqnReq.resolveTypeStyle()).isEqualTo(TypeStyle.FQN);

        BatchJobRequest invalidFmtReq = new BatchJobRequest(
                "id-inv-fmt", "class E {}", "invalid-format", null, null,
                null, null, null, null, null, null, null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(invalidFmtReq::resolveFormat)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported trace format");

        BatchJobRequest invalidTsReq = new BatchJobRequest(
                "id-inv-ts", "class F {}", null, null, null,
                null, null, null, null, null, "invalid-style", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(invalidTsReq::resolveTypeStyle)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported type style");
        assertThat(req.resolveLimits()).isEqualTo(limits);
        assertThat(req.stdin()).isEqualTo("input");
        assertThat(req.breakpoints()).containsExactly("5");
        assertThat(req.allBreakpoints()).isTrue();
        assertThat(req.accumulateBreakpoints()).isTrue();
        assertThat(req.removeMainArgs()).isTrue();
        assertThat(req.inlineStrings()).isTrue();
        assertThat(req.removeMethodThis()).isTrue();
        assertThat(req.inspection()).isEqualTo(InspectionPolicy.FIELDS);
    } // testRequestExplicitValues

    @Test
    @DisplayName("BatchJobResponse and TraceResult factories")
    void testResponseAndTraceResultFactories() {
        TraceResult failed = TraceResult.failed("pytutor", "compile", "Syntax error");
        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.stopReason()).isEqualTo("compile_error");
        assertThat(failed.phase()).isEqualTo("compile");
        assertThat(failed.complete()).isFalse();
        assertThat(failed.diagnostics()).contains("Syntax error");

        TraceResult stopped = TraceResult.stopped("modern", "step_limit", "Too many steps");
        assertThat(stopped.status()).isEqualTo("stopped");
        assertThat(stopped.stopReason()).isEqualTo("step_limit");
        assertThat(stopped.complete()).isFalse();

        BatchJobResponse resp = new BatchJobResponse("req-99", failed);
        assertThat(resp.id()).isEqualTo("req-99");
        assertThat(resp.result()).isSameAs(failed);
    } // testResponseAndTraceResultFactories
}
