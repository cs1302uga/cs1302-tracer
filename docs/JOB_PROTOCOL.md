# Supervised job and checkpoint protocol

Status: implementation contract for the separate `cs1302-tracer-runner` project.
Tracer currently implements qualified selectors; whole-job supervision, isolation,
and checkpoint transport are not yet implemented. This contract extends the
[runner requirements](RUNNER_CONTRACT.md) without changing Tracer envelope v1.

## Job requests

One request describes one disposable worker. The public service accepts bounded
source files and allowlisted trace options; the operator controls executable,
image, mounts, environment, artifact locations, and maximum budgets. Clients may
request tighter budgets but cannot override operator ceilings. The worker receives
only staged submitted files and input, never host source roots or arbitrary flags.

An illustrative internal request after admission is:

```json
{
  "schemaVersion": 1,
  "jobId": "example-001",
  "sources": [{"path": "Main.java", "content": "public class Main { public static void main(String[] a) {} }"}],
  "stdin": "",
  "trace": {
    "format": "modern",
    "inspection": "FIELDS",
    "capture": "latest",
    "breakpoints": [{"sourcePath": "Main.java", "line": 1}]
  },
  "limits": {
    "queueTimeoutMillis": 10000,
    "jobTimeoutMillis": 15000,
    "traceTimeoutMillis": 5000,
    "sourceBytes": 262144,
    "sourceFiles": 32,
    "inputBytes": 1048576,
    "snapshots": 1000,
    "outputBytes": 65536,
    "heapObjects": 1000,
    "elements": 10000,
    "traceBytes": 33554432,
    "artifactBytes": 67108864
  },
  "checkpoint": {"enabled": false}
}
```

Values are examples, not capacity recommendations. CPU, memory, process, scratch
space, and per-user/service concurrency limits are also mandatory operator policy.
Reject unknown schema versions/options and invalid source destinations. Validate
UTF-8 bytes and file counts before storing unbounded request data. Sources use
normalized relative paths with no parent traversal, absolute paths, or duplicates.
The runner translates this structured request into allowlisted Tracer arguments.

## Lifecycle and deadlines

Queue time begins at admission and has its own finite deadline. The monotonic
whole-job deadline begins when work leaves the queue, before staging, and includes
staging, worker startup, parsing, compilation, tracing, serialization, artifact
collection, and validation. HTTP/upload transport limits are enforced before
admission. Result delivery to clients has a separate bounded service timeout.

The supervisor owns the deadline independently of worker threads. On deadline,
cancellation, disconnect according to service policy, or shutdown, terminate the
entire job containment unit. Give teardown a separate short, finite grace budget;
report timeout at expiry of the job budget, even if teardown is still finishing.
Release concurrency capacity only after termination and cleanup are confirmed.
Recovery after supervisor restart must reconcile persisted job identities against
live containment units and remove orphaned units/workspaces.

`--timeout-ms` remains the existing narrower Tracer deadline. It does not become
an alias for whole-job supervision. A local process-only supervisor is suitable
for trusted development and must not be advertised as the hosted isolation backend.

## Runner results

Runner metadata is stored outside the guest-writable containment unit. A result
has its own `schemaVersion`, `jobId`, `status`, `reason`, observed worker exit code,
runner timings, optional validated `tracerResult`, and optional `recoveredTrace`.
Do not embed runner status into or fabricate a successful Tracer v1 envelope.

- `succeeded`: no external failure, exit code zero, and a schema-valid Tracer
  result claiming completion. This is not an attestation that guest-provided
  content or educational state is truthful.
- `stopped`: a runner resource limit/cancellation or a valid Tracer controlled stop.
- `failed`: launch, worker, protocol, schema, isolation, storage, or tracer failure.

Runner reasons include `runner_deadline`, `runner_cancelled`,
`runner_memory_limit`, `runner_output_limit`, `runner_artifact_limit`,
`runner_worker_exit`, `runner_protocol_error`, and `runner_cleanup_error`.
A normally observed Tracer failure/stop uses `tracer_failed`/`tracer_stopped`,
with its original details preserved in `tracerResult`. Externally observed
termination takes precedence over any worker claim. Preserve the first observed
termination reason and append cleanup failures as separate diagnostics.

A killed job with no valid artifact has `tracerResult: null` and
`recoveredTrace: null`. Never parse an arbitrary truncated stdout document as a
completed result. Validate versions, sizes, types, and allowed fields before
retaining any worker-provided artifact.

## Checkpoint stream v1

Checkpointing is an opt-in side channel; ordinary stdout remains one completed
JSON document. The runner owns transport and persistence outside the guest's
writable filesystem. Its size limits apply during reading, not after buffering
an entire record. Worker records remain untrusted.

Use newline-terminated UTF-8 JSON records with a finite byte limit per record and
per job. Each contains `schemaVersion`, `jobId`, `sequence`, and `kind`. Sequence
numbers increase strictly, starting with a header identifying format, encoding,
source bundle digest, and capture policy. Job/source identity must match the
runner's admitted request; a matching digest is not authentication.

Record kinds:

- `output`: stream (`stdout`/`stderr`), generation, byte offset, and Base64 bytes.
  Chunks append contiguously. A changed sanitized prefix begins a new generation.
- `snapshot`: a complete format-specific step without cumulative output strings,
  plus stdout/stderr generation-and-end-offset references. Every referenced byte
  must already exist in a validated output record. Snapshots are self-contained
  with respect to heap references; half-extracted state is never emitted.
- `checkpoint`: commits a validated prefix of records and identifies the active
  snapshot set. Latest-per-location mode includes replacement information;
  suppressed snapshots never become retained snapshots during recovery.
- `complete`: an optional normal completion marker, never a substitute for the
  supervisor's observed process outcome and validated final result.

The supervisor validates records before persistence and acknowledges a checkpoint
only after referenced data and its manifest have reached the configured durability
level. Recovery uses the last acknowledged checkpoint, discards an incomplete tail,
and never follows offsets into missing chunks. Validate after restart as well.
Published recovery includes format, source identity, checkpoint sequence, a valid
partial trace, and `complete: false`; the runner termination reason remains intact.

Checkpoint interval, maximum record size, artifact cap, and durability level are
operator-controlled and reported in the job's effective policy. Process-kill
recovery is the initial guarantee; host/power-loss durability requires separately
validated persistence behavior. If requested checkpoint guarantees cannot be met,
stop with an explicit runner failure rather than silently degrading durability.

## Acceptance gates

1. Numeric selector compatibility and qualified `(source path, line)` retention
   pass on JDK 21/25 and both output formats.
2. A supervised worker hanging in staging, startup, parse/compile, trace, serialize,
   or artifact handling reaches a bounded outcome without orphaned descendants.
3. Every shipped Linux isolation backend independently passes the hostile workload,
   cross-job, concurrency, cancellation, and restart tests in the runner contract.
4. Termination during every checkpoint record kind recovers only a complete,
   validated prefix; malformed, oversized, wrong-job, out-of-order, and dangling
   records are rejected. Limits and recovered state agree with live retention.
5. Existing Tracer fixtures and strict production coverage remain enforced. Runner
   lifecycle/isolation tests are separate from trace-format compatibility tests.
