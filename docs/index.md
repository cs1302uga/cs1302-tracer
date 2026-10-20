---
slug: /
---

# cs1302-tracer

Compile and trace Java programs to inspect their execution, stack frames, and heap.
The tracer emits JSON for tools such as code visualizers; it does not display an
interactive visualization itself.

## Choose your path

| You want to… | Start here | Then read |
| --- | --- | --- |
| Decide whether a program is safe to trace | [Why the tracer is not a sandbox](explanation/trust-model.md) | [Runner contract](RUNNER_CONTRACT.md), [inspection policies](explanation/inspection-policies.md) |
| Run a Java example locally | [Install](guides/installation.md) → [First trace](tutorials/first-trace.md) | [Read a trace](tutorials/read-a-trace.md) |
| Teach or inspect shared references and mutation | [Trace shared references](tutorials/trace-shared-references.md) | [How a trace represents execution](explanation/trace-model.md) |
| Teach or inspect repeated execution | [Trace a loop](tutorials/trace-a-loop.md) | [How a trace represents execution](explanation/trace-model.md) |
| Trace your own program | [Tracing recipes](guides/tracing.md) | [Troubleshooting](guides/troubleshooting.md), [CLI defaults and exit statuses](reference/cli.md#trace-defaults-at-a-glance) |
| Consume JSON or process jobs | [Batch tutorial](tutorials/batch-jobs.md) | [JSON output](reference/output.md), [batch protocol](reference/batch.md), [bounded results](BOUNDED_TRACING.md) |
| Embed tracing in Java | [Java tutorial](tutorials/java-integration.md) | [API reference guide](reference/java-api.md), [3.x migration](guides/java-api-migration.md) |
| Read API documentation without a network connection | [Offline API documentation](guides/offline-javadoc.md) | Download and verify a release bundle before going offline. |
| Inspect licensing or project dependencies | [Project license](reference/licenses.md) | [Resolved dependencies and declared licenses](reference/dependencies.md) |
| Contribute a change | [Development workflow](contributing/development.md) | [Architecture](contributing/architecture.md), [documentation maintenance](contributing/documentation.md) |
| Prepare or publish a release | [Release runbook](contributing/releases.md) | [Guide archives](contributing/guide-archives.md), [**Javadoc** artifacts](contributing/javadoc-artifacts.md) |

The tracer executes guest programs. Use trusted programs locally; hosted untrusted
submissions require the separate [runner contract](RUNNER_CONTRACT.md).

The version menu distinguishes development documentation from released versions.
Documentation snapshots start at 3.2.1; earlier releases are not backfilled.
Development documentation follows the working source. A prepared release snapshot
can appear as unreleased in a local preview before that release is published.

First-party code and documentation use the [MIT license](reference/licenses.md).
The [dependency reference](reference/dependencies.md) separates runtime and test
dependencies and records their declared licenses. The CLI's `show-licenses`
command displays the separate bundled runtime notices, which retain their original
terms.
