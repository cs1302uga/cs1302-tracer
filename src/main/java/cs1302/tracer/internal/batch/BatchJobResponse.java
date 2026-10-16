package cs1302.tracer.internal.batch;

import org.apiguardian.api.API;

import cs1302.tracer.internal.execution.TraceResult;

/**
 * Envelope for a single batch tracing job response.
 *
 * @param id Unique job correlation identifier.
 * @param result Standard trace result envelope.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record BatchJobResponse(String id, TraceResult result) {} // BatchJobResponse
