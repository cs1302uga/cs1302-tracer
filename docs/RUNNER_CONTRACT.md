# Contract for a separate Linux submission runner

Status: integration requirements implemented and exercised in
[`cs1302-tracer-runner`](https://github.com/cs1302uga/cs1302-tracer-runner).
This repository supplies tracing and checkpoint production. Container lifecycle,
admission, artifact recovery, and Linux containment tests live in the runner
repository. Automated tests do not replace independent deployment review.

Trusted instructor examples may use Tracer directly on supported desktop/server
platforms. Treat hosted student submissions as malicious by default. Isolate the
whole job, including source parsing and compilation, on Linux before starting
Tracer. Tracing limits and `FIELDS` inspection are useful controls inside that
boundary; they are not the boundary itself.

## Backend responsibilities

Both a disposable container/VM backend and direct Linux execution must provide:

1. A private, disposable workspace with no concurrent writers during source
   preparation. Validate file destinations and avoid shared writable mounts.
2. A nonprivileged execution identity, a minimal environment without secrets, and
   access only to required runtime files and submitted sources. Do not expose
   host credentials, application state, container-management sockets, or other jobs.
3. Enforced CPU, memory, process/thread, disk, and output/artifact limits for the
   entire job and its descendants, including the tracer and compiler.
4. A whole-job deadline independent of Tracer's threads and guest debugger state.
   Bound queue time, input transport, compilation, execution, and result handling
   according to the service's lifecycle policy.
5. Filesystem and network restrictions appropriate to student code, including
   restrictions on communication with the host, other jobs, and control services.
   Permit necessary intra-job JDI transport without exposing debugger endpoints
   outside the containment unit.
6. Reliable termination of the entire containment unit on timeout, cancellation,
   crash, disconnect, or service shutdown, followed by workspace/artifact cleanup.
7. Resource admission control so many individually bounded jobs cannot exhaust
   the server. Enforce per-user and service-wide concurrency/queue budgets.

A direct backend needs an independently reviewed OS-enforced sandbox satisfying
these requirements. Running an ordinary Java process under the web server's
account is not a supported alternative. Container/VM deployment also requires
correct configuration; its name alone is not evidence of isolation.

## Job interface

- Accept a bounded UTF-8 source bundle and an allowlisted configuration. Students
  must not select arbitrary CLI flags, host source roots, executables, environment
  variables, mounts, or output locations.
- Run one disposable Tracer process per job. Prefer `--result-envelope`, `FIELDS`,
  and explicit finite values for all limits described in
  [bounded tracing](BOUNDED_TRACING.md).
- Mount/provide only submitted source dependencies. The runner should stage input
  fully before launching Tracer; do not leave unbounded source streams open.
- Keep runner status and artifacts outside the writable guest containment unit.
  Since Tracer and guest execute within the same job boundary, treat the entire
  job's stdout and result JSON as untrusted data, not an attestation of success.
- Bound result size while reading, validate JSON/schema/version, and sanitize
  source/output text in downstream visualizers. Do not interpret result text as
  HTML, shell commands, paths, or runner instructions.
- Record the runner's own exit/termination cause. It takes precedence over any
  claimed Tracer completion status.

## Result handling

A normally returned v1 envelope may contain a partial trace with an explicit stop
reason. Preserve it when valid and permitted by the service's artifact policy.

If the job is killed, runs out of memory, produces invalid/truncated JSON, or loses
its connection, the runner must create its own failure record. Do not fabricate a
successful Tracer envelope or promise a partial trace when no valid artifact exists.
Use separate runner reason codes, such as `runner_deadline` or
`runner_memory_limit`, rather than attributing unobserved reasons to Tracer.

## Backend acceptance gate

Before enabling student submissions, demonstrate that both supported backends
contain attempts to access host/other-job files, use forbidden networking, create
unbounded processes/threads, exhaust memory/disk/output, and survive cancellation
or runner restart without leaving processes or workspaces behind. Run concurrency
and teardown tests. Review the boundary independently of trace-format tests.

The runner's implementation, deployment settings, and isolation test suite belong
to the follow-up project. No hosted isolation claim is made by this Tracer release.
