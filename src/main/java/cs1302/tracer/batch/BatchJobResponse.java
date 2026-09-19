package cs1302.tracer.batch;

import cs1302.tracer.execution.TraceResult;

/**
 * Envelope for a single batch tracing job response.
 *
 * @param id Unique job correlation identifier.
 * @param result Standard trace result envelope.
 */
public record BatchJobResponse(
        String id,
        TraceResult result) {} // BatchJobResponse
