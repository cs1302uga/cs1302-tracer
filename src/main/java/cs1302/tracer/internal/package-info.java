/**
 * Command-line entry points and source preparation for Java execution tracing.
 *
 * <p>{@link cs1302.tracer.internal.App} routes tracing, batch processing, breakpoint discovery, and license commands.
 * {@link cs1302.tracer.internal.SourcePreparation} owns compiled guest classes and the source evidence used to
 * interpret runtime values, plus one prepared class path. Close it when the trace job finishes to release
 * compiled output, then its dependency owner, which closes archive readers before removing owned copies.</p>
 *
 * <p>{@link cs1302.tracer.internal.SourceDelimiters} recognizes the shared source-stream grammar.
 * {@link cs1302.tracer.internal.SourceNames} supplies source-name inference to compilation and the supported
 * source-bundle builder, preserving the stricter public-type requirement for legacy undelimited input.
 * {@link cs1302.tracer.internal.SourceGlob} owns portable path-pattern syntax and matching;
 * {@link cs1302.tracer.internal.FileSelection} shares traversal, link policy, and ordering through named source
 * and dependency operations, preserving each operation's selected path identity.
 * Bulk captures commit atomically through the source-bundle builder after all selected files validate.
 * Bundle framing and captured-file ownership remain with {@link cs1302.tracer.api.SourceBundle}; compilation
 * retains destination validation and temporary-file ownership.</p>
 *
 * <p>Binary dependency preparation lives in {@code cs1302.tracer.internal.classpath}. It shares portable
 * file selection with source collection but owns manifest traversal, copy, and duplicate policies.
 * Binary dependencies do not broaden source discovery or add library source-line breakpoints.</p>
 */
package cs1302.tracer.internal;
