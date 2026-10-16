/**
 * Effective job choices, execution budgets, inspection policies, snapshot retention, and structured job results.
 *
 * <p>{@link cs1302.tracer.internal.execution.TraceJob} resolves caller-specific policy without owning guest execution.
 * {@link cs1302.tracer.internal.execution.TraceSession} coordinates the lifecycle and accounting of a bounded trace
 * job.
 * {@link cs1302.tracer.api.TraceLimits} defines its resource budgets, while
 * {@link cs1302.tracer.internal.execution.TraceResult} represents completion, failure, or a partial result.
 * These limits control tracing resources; they do not provide a security sandbox for guest programs.</p>
 */
package cs1302.tracer.internal.execution;

import cs1302.tracer.api.TraceLimits;
