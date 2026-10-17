/**
 * Streaming batch requests, responses, and worker orchestration for independent trace jobs.
 *
 * <p>{@link cs1302.tracer.internal.batch.BatchTraceService} processes newline-delimited JSON with bounded admission and
 * worker
 * concurrency, correlation, and response writing. Workers coordinate per-job execution and use
 * {@link cs1302.tracer.internal.execution.TraceCompletion} for success and recovery payload selection.
 * Completion delegates format conversion to {@link cs1302.tracer.internal.serialize.TraceRendering}, preserving
 * worker failure precedence and rendering scopes. Workers reuse persistent guest sessions where
 * supported. Typed Java callers also use worker execution through resolved
 * {@link cs1302.tracer.internal.execution.TraceJob} choices, without constructing wire requests.
 * Job results and captured input/output remain scoped to their originating request.</p>
 *
 * <p>Binary dependencies are selected per request through literal paths or explicit root/glob entries.
 * Workers prepare each job's selections and optional copies independently; reused guests receive fresh
 * application loaders. Dependency selection and copying errors retain compilation-phase reporting.</p>
 *
 * <p>The wire-request builder preserves omitted settings and detaches the immediate breakpoint and class-path
 * selection lists, including null entries whose acceptance is decided by their respective validators.
 * Existing request constructors retain caller collections. Interpretation and deferred
 * validation remain with trace-job resolution; the builder does not apply typed Java request defaults.</p>
 */
package cs1302.tracer.internal.batch;
