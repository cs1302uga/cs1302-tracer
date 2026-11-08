---
slug: /
---

# cs1302-tracer

Compile and trace Java programs to inspect their execution, stack frames, and heap.
The tracer emits JSON for tools such as code visualizers; it does not display an
interactive visualization itself.

Start with [installation](guides/installation.md) and [your first trace](tutorials/first-trace.md).
Use [tracing options](guides/tracing.md) to select snapshots and provide program input.

For integrations, read the [output reference](reference/output.md),
[bounded result contract](BOUNDED_TRACING.md), and [runner contract](RUNNER_CONTRACT.md).
For contributions, see [development](contributing/development.md) and
[architecture](contributing/architecture.md).

The version menu distinguishes development documentation from released versions.
Release history begins with the first release prepared using this documentation
workflow; earlier releases are not backfilled.

First-party code and documentation use the MIT license. Dependency notices retain
their original licenses; the CLI's `show-licenses` command displays bundled notices.
