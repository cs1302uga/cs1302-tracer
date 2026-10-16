/**
 * Supported Java interface for self-contained tracing, breakpoint discovery, and NDJSON batch processing.
 *
 * <p>{@link cs1302.tracer.api.Tracer} owns preparation and guest cleanup for one-shot jobs.
 * {@link cs1302.tracer.api.TraceOutcome} exposes detached metadata and JSON without parser, serializer, or debugger
 * objects. {@link cs1302.tracer.api.BatchTracer} owns reusable workers and must be closed by its caller.</p>
 *
 * <p>Signatures use project-owned and ordinary Java types; {@link org.apiguardian.api.API} supplies stability
 * metadata. Supported types are {@code STABLE} since 4.0.0, with compatibility throughout the 4.x series.
 * API Guardian is a transitive compile dependency. Implementation packages under
 * {@code cs1302.tracer.internal} are unsupported and may change independently. Guest execution is not a sandbox.</p>
 */
package cs1302.tracer.api;
