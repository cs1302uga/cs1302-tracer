/**
 * Effective job choices, execution budgets, inspection policies, snapshot retention, trace completion, and results.
 *
 * <p>{@link cs1302.tracer.internal.execution.TraceJob} resolves caller-specific policy and retains binary dependency
 * configuration without owning guest execution or filesystem preparation.
 * {@link cs1302.tracer.internal.execution.ClassPathOptions} adapts repeatable native command path lists
 * and glob syntax into immutable {@link cs1302.tracer.api.TraceClassPath} selections.
 * {@link cs1302.tracer.internal.execution.TraceSession} coordinates the lifecycle and accounting of a bounded trace
 * job.
 * {@link cs1302.tracer.internal.execution.TraceCompletion} selects result-payload snapshots and rendering-failure
 * precedence for envelope CLI and Java/NDJSON worker callers, preserving their resource scopes and failure rules.
 * Ordinary CLI retains direct rendering. Completion delegates presentation to retention and format conversion
 * to trace rendering; adapters retain guest cleanup, interruption, and final session-result construction.
 * Detached {@link cs1302.tracer.internal.SubmittedSources} remains usable during completion after source
 * preparation closes; source identity is passed per job rather than stored in session state.
 * {@link cs1302.tracer.api.TraceLimits} defines its resource budgets, while
 * {@link cs1302.tracer.internal.execution.TraceResult} represents completion, failure, or a partial result.
 * These limits control tracing resources; they do not provide a security sandbox for guest programs.</p>
 */
package cs1302.tracer.internal.execution;

import cs1302.tracer.api.TraceLimits;
