/**
 * Maven goal for launching the tracer CLI without a caller-owned project. The goal owns
 * argument tokenization and the CLI child process; the tracer owns compilation, guest
 * JVMs, tracing, serialization, and CLI validation. Maven resolves the pinned tracer
 * dependency independently of dependencies in the caller's project. This package is
 * plugin implementation, not part of the supported tracing Java API.
 */
package cs1302.tracer.maven;
