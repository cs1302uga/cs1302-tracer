/**
 * Debugger execution, source analysis, value extraction, and job-scoped observation.
 *
 * <p>{@link cs1302.tracer.internal.trace.DebugTraceHelper} coordinates dedicated JDI event handling.
 * The package-private snapshot capture module assembles complete
 * {@link cs1302.tracer.internal.trace.ExecutionSnapshot} values for both guest paths, hiding type prepass ordering
 * and shared heap traversal. Sessions retain budget and admission ownership.
 * Source analysis supplies evidence for runtime type
 * recovery and
 * lambda reconstruction. {@link cs1302.tracer.internal.trace.ReifiedTypeMetadata} owns type evidence for one snapshot,
 * while
 * {@link cs1302.tracer.internal.trace.JobOutput} owns each job's captured output and finalization.</p>
 *
 * <p>Dedicated and persistent guest execution share capture and observation policies. Retained snapshots must remain
 * valid after guest completion or reuse, without depending on mutable buffers from later jobs.</p>
 */
package cs1302.tracer.internal.trace;
