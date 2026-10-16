/**
 * Streaming batch requests, responses, and worker orchestration for independent trace jobs.
 *
 * <p>{@link cs1302.tracer.internal.batch.BatchTraceService} processes newline-delimited JSON with bounded admission and
 * worker
 * concurrency. Workers coordinate per-job execution and serialization, reusing persistent guest sessions where
 * supported. Typed Java callers also use worker execution through resolved
 * {@link cs1302.tracer.internal.execution.TraceJob} choices, without constructing wire requests.
 * Job results and captured input/output remain scoped to their originating request.</p>
 */
package cs1302.tracer.internal.batch;
