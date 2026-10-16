/**
 * Command-line entry points and source preparation for Java execution tracing.
 *
 * <p>{@link cs1302.tracer.internal.App} routes tracing, batch processing, breakpoint discovery, and license commands.
 * {@link cs1302.tracer.internal.SourcePreparation} owns compiled guest classes and the source evidence used to
 * interpret runtime
 * values; close it when the trace job finishes to release temporary compilation resources.</p>
 */
package cs1302.tracer.internal;
