# Changelog

Published releases are listed newest first. Publication dates are UTC dates from
[GitHub release records](https://github.com/cs1302uga/cs1302-tracer/releases), verified
on 2026-10-17. Version bumps without a published release are included in the next
published range, not presented as separate releases.

This history was reconstructed from release records, tagged source/documentation
diffs, and Git commits. Most historical release bodies are empty. Compatibility
notes describe identified changes, including changes shipped under patch versions;
"no breaking changes identified" is not a historical binary-compatibility certification.
Java API compatibility has not been exhaustively checked with a binary API comparison
tool. Additive JSON fields and corrected labels/values are called out separately
from changes to structure. Consumers should tolerate unknown additive fields.

Every commit reachable in each consecutive release range is linked, including
merge and release-preparation commits; overlapping work may therefore have more
than one historical commit. The first release includes all available ancestors.
The checkout is not shallow, all 44 published release tags match origin, and no
published release tag is missing. There are no published records for the skipped
version numbers (for example 2.2.0, 2.12.1, and 3.2.0).

Maintain curated summaries here as changes land. Add final commit links after
committing; at release time reconcile the range, use the actual publication date,
and move Unreleased into a release entry. Do not infer publication from pom.xml.

## Unreleased

[Changes since v4.1.0](https://github.com/cs1302uga/cs1302-tracer/compare/v4.1.0...HEAD).

Implementation: [caeaf76](https://github.com/cs1302uga/cs1302-tracer/commit/caeaf7650201fa8354d217d49e7caa77ae27cfae)
— Maven configuration, dependency updates, and portable launcher distributions.

### Breaking changes

No breaking changes.

### Non-breaking changes

- **Documentation:** add a download-compatible first trace, an output walkthrough,
  and runnable Java and batch integration tutorials. Reorganize installation,
  contributor onboarding, navigation, and migration guidance; verify tutorial
  examples against packaged artifacts. Runtime and serialized contracts are unchanged.
  Implementation: [4ca34cd](https://github.com/cs1302uga/cs1302-tracer/commit/4ca34cdaa4447d5edc1ac66fc3a128ebc5a4cbc6).

- **Source distribution:** include Maven wrapper scripts and configuration so the
  wrapper-derived Maven version check also works when building extracted sources.
  CI now packages the extracted source archive.
  Implementation: [d7ef76c](https://github.com/cs1302uga/cs1302-tracer/commit/d7ef76c084f4467e71e36929c50e305635ad0f26).

- **Bash launcher:** ignore shell `CDPATH` during internal path resolution so
  relative launcher paths and symlinks still locate their adjacent JAR.
  Implementation: [d7ef76c](https://github.com/cs1302uga/cs1302-tracer/commit/d7ef76c084f4467e71e36929c50e305635ad0f26).

- **Build configuration:** limit resource filtering to runtime version metadata,
  remove external dependency paths from the self-contained executable JAR's
  manifest, consolidate assembly naming defaults, and publish repository and issue
  metadata. Enforcer now checks production and test dependency upper bounds to
  reject stale version overrides. Artifact names, launchers, Java API, and trace
  formats remain unchanged.
  Implementation: [abde003](https://github.com/cs1302uga/cs1302-tracer/commit/abde003b58259e65ea4711553cb275e5640e273c).

- **Distribution formats:** add `code-tracer-bin.tar.xz` alongside the existing
  tar.gz and ZIP downloads. Both Unix tar formats contain the same launchers,
  executable JAR, license, permissions, and relative Bash symlink. Native Windows
  source builds continue to produce the ZIP.
  Implementation: [23f6949](https://github.com/cs1302uga/cs1302-tracer/commit/23f6949d285c58534727a98aa973f2c447ca38e3).

- **Launcher configuration:** `CODE_TRACER_JAR_DIR` selects the directory containing
  `code-tracer.jar` for Bash, PowerShell, and Command Prompt. Unset or empty values
  preserve the adjacent-JAR default; relative paths use the caller's working
  directory. Invalid explicit overrides fail without falling back.

- **CLI packaging:** add Bash (`code-tracer.sh`, also available as `code-tracer`), PowerShell 7.4+ (`code-tracer.ps1`),
  and standalone Command Prompt (`code-tracer.cmd`) launchers beside the executable
  JAR in `target/`. Both `code-tracer-bin.tar.gz` and `code-tracer-bin.zip` bundle
  all launchers, the JAR, and license. Unix builds produce a relative Bash symlink
  in the tar archive; the ZIP uses a regular copy. Native Windows builds produce
  the ZIP without requiring symlink privileges. Launchers honor `JAVA_HOME`, fall back to Java
  on `PATH`, and preserve the caller's working directory and Java exit code.
  Existing JAR invocation and Maven artifact identities remain supported.
  Windows CI packages and checks both Windows launchers on JDK 21 and 25.

- **Packaging:** provide `target/code-tracer.jar` as a convenience copy of the executable
  assembly. Current commands and tooling use the short filename; the original assembly
  path, Maven main artifact, and attached classifier remain available. Release uploads
  select the short executable and ordinary library JAR without a wildcard rename.

- **Build documentation:** document every POM dependency and plugin declaration and explain dependency roles,
  compilation, resources, documentation, packaging, quality gates, and publishing.
  Remove obsolete commented-out assembly settings without changing build behavior.

- **Build tooling:** derive the minimum supported Maven version from
  `.mvn/wrapper/maven-wrapper.properties`. Enforcer accepts that version or later;
  `./mvnw` and `mvnw.cmd` continue to select the pinned wrapper version.

- **Build repositories:** mirror the legacy JVNet releases repository to Maven Central
  in local setup and all Maven CI jobs, avoiding its expired certificate while
  preserving other repository routes and credential settings.

- **Development version:** advance the Maven project to `4.2.0-SNAPSHOT`.
- **Build tooling:** centralize explicitly selected Maven versions in properties,
  consolidate plugin version ownership, and enforce the convention in Scripts quality CI.
- **Tests and dependencies:** replace JSONassert with matching JsonUnit 6.3.0 APIs,
  preserve partial-object/ordered-array assertions, and remove the unused JSON-java
  override. Upgrade JavaParser's transitive Checker Qual annotations to 4.3.1.
  Java API, CLI, and JSON/protocol contracts are unchanged.
- **Release documentation:** record the 4.1.0 publication date and its squash-merged
  commit range.

## 4.1.0 — 2026-10-17

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v4.1.0) · [Compare v4.0.0...v4.1.0](https://github.com/cs1302uga/cs1302-tracer/compare/v4.0.0...v4.1.0)

### Breaking changes

- **Guest compilation (Java API, CLI, and batch):** disable annotation processing
  explicitly. Programs that relied on automatically discovered processors must now
  supply generated declarations as source files. Annotation types remain usable.
  Supported existing Java signatures, the `TraceRequest` record shape, trace JSON
  schemas, and result-envelope versions remain unchanged.
- **Source-path discovery:** neighboring source directories are no longer also
  treated as implicit binary dependency roots. Java source discovery still works;
  configure the directory explicitly as a class-path entry when relying on
  precompiled neighboring classes.

### Non-breaking changes

- **CLI:** preserve Windows drive-relative class-path glob roots when a literal
  filename stem precedes the first glob character, including `C:library-*.jar`.
  Keep the drive prefix in the native search root for `*`, `?`, character classes,
  and brace alternatives instead of leaving it in an unmatchable relative glob.
- **Contributor documentation:** define submitted source identity, logical and debug
  source paths, and reachable-heap inspection in the shared glossary.
- **Internal architecture:** retain detached submitted-source identity across
  preparation, rendering, recovery, and breakpoint listing; preserve deferred
  metadata validation and caller-specific path rules. Concentrate snapshot-local
  heap identity and JDI inspection outside captured value records. Supported Java,
  CLI, JSON/protocol, guest reuse, and tracing budget behavior remain unchanged.
- **Java API, CLI, and batch:** add per-job binary directory/JAR dependencies through
  immutable `TraceClassPath` configuration and tracing/discovery overloads, repeatable
  `--class-path`/`-classpath`/`-cp` options, and optional batch request fields. Support
  native path lists, immediate JAR wildcards, recursive globs, deterministic ordering,
  and local manifest dependencies. Optional copying captures the available transitive
  dependency set before compilation while preserving JAR contents and signatures.
  Dependencies are shared by compilation, type analysis, and execution without adding
  library source-line breakpoints or changing serialized output formats.
- **Java API:** add immutable `SourceBundle` with a reusable builder and copy builder
  for ordered, delimited source input. Accept explicitly named text or infer names
  from source declarations, and capture strictly decoded UTF-8 files when added.
  Expose source text, independent UTF-8 input streams, and output that leaves caller
  streams open. Reject duplicate paths and delimiter collisions; preserve source
  text except for a necessary newline before a following file delimiter. This adds
  no tracing overload, compilation guarantee, CLI syntax, or JSON/protocol change.
- **Java API:** add atomic glob-based `SourceBundle.Builder.addFiles` and
  `addInferredFiles` overloads with immediate UTF-8 capture and deterministic
  root-relative ordering. Portable patterns include `demo/**/*.java`, matching
  immediate and nested files. Discovered symbolic links are skipped by default;
  an explicit option follows them, including targets outside the root, and fails
  on encountered broken links or cycles. Aliases remain separate matches, and
  invalid matches or duplicate logical paths reject the entire addition.
- **Release preparation:** set Maven version `4.1.0`. Documentation preview
  accepts matching prepared, untagged snapshots with working Javadoc and an
  unreleased banner. Published documentation still requires exact stable release tags.
- **Contributor style:** require a blank line immediately inside each brace of
  nonempty class and interface bodies, including enums, records, annotation
  interfaces, and nested or local declarations. Empty bodies remain exempt.
- **Documentation:** clarify capture selection, resource accounting, builder and
  snapshot ownership, batch failures, and nullable manually constructed outcomes.
  Correct stale examples and Javadoc, use the managed Python wrapper in contributor
  commands and generated CLI guidance, and document validation of internal Javadoc.
- **Documentation tooling:** preserve authored CLI behavior notes when regenerating
  executable help, while checking the generated prefix for exact agreement.
- **Java API:** add nested builders for `TraceRequest`, `TraceLimits`, `TraceOutcome`,
  and `BatchTracer`, with copy builders for the three value records. Existing
  constructors and record shapes remain available. Request and limit defaults stay
  unchanged; batch construction requires explicit resource settings. Each batch
  build owns a separate closeable processor. Outcome builders preserve supplied JSON
  verbatim and do not synchronize it with manually edited metadata.
- **Internal construction:** add builders for modern `Step`, Python Tutor `TraceStep`,
  and `BatchJobRequest`. Builders detach immediate collections while preserving each
  model's null and ordering semantics; nested values may remain shared. Existing
  constructors, caller-profile interpretation, rendering policy, and wire output
  remain unchanged.
- **Internal values:** add shared `TraceValue.Primitive.Boolean.TRUE` and `FALSE`
  constants and reuse them when capturing JDI booleans. Direct construction and
  serialized boolean values remain unchanged.
- **CLI and JSON/protocol:** add opt-in `--include-primitive-bits` and batch
  `includePrimitiveBits`. Both serializers retain existing literals and add typed,
  fixed-width binary descriptors for primitives in locals, fields, arrays, and
  boxed payloads. Defaults, capture accounting, and envelope version are unchanged;
  enabled output is larger. Floating-point bits describe values received through
  JDI; boolean bits are a logical encoding, not a JVM storage-size claim.
- **Java API:** add `Tracer.trace(TraceRequest, boolean includePrimitiveBits)`.
  The original overload defaults the option to false. Request records, constructors,
  and record patterns remain compatible. See the
  [Java integration guide](docs/reference/java-api.md).
- **Internal architecture:** centralize ordinary, envelope, and batch JSON rendering;
  isolate complete snapshot capture from guest event loops. Preserve supported Java,
  CLI, JSON/protocol, session budgets, and guest reuse behavior. Test rendering through
  final JSON and type prepasses through completed captures.
- **Internal architecture:** give prepared class paths sole ownership of dependency
  files and archive views, share source/dependency file traversal, and centralize
  envelope CLI and Java/NDJSON completion rules. Preserve path identity, capture
  timing, cleanup order, caller-specific failure reporting, and supported Java,
  CLI, and JSON/protocol behavior.
- Add a runnable binary-metadata example and document placement, encodings,
  per-job settings, and serialization costs. Keep published documentation snapshots
  unchanged; development docs and the 4.1.0 snapshot describe the extension.
- **Documentation:** distinguish normative encoding specifications from informative
  explanations in a central primitive-binary reference, linked from guides, examples,
  and Javadoc. Clarify tracer-defined conventions alongside Java SE 21, IEEE 754-2019,
  and Unicode 15.0 references; Java API, CLI, and JSON/protocol behavior is unchanged.
- **Contributor documentation:** correct invalid parameter tags on the internal
  lambda heap-object factory so its Javadoc can be generated directly.

### Commits

- [`9119b20`](https://github.com/cs1302uga/cs1302-tracer/commit/9119b20d592016c60d0ff87467b8100d6f8fa662) — Add primitive binary metadata, dependency class paths, and source bundles (#18).

### Development history

The published `v4.0.0..v4.1.0` range contains only the squash commit `9119b20`.
The following original implementation commits are retained as development
provenance; they are not separate commits in that published range.

- [`95ea40c`](https://github.com/cs1302uga/cs1302-tracer/commit/95ea40cdafe376e36d8e2422c4ea9fb40c54ab08) — Primitive binary metadata, rendering/capture ownership, and release preparation.
- [`db9f770`](https://github.com/cs1302uga/cs1302-tracer/commit/db9f7702eac9d32623f7831cef482f550274c8cb) — Builders, source bundles, binary class-path support, and architecture/documentation updates.

## 4.0.0 — 2026-10-16

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v4.0.0) · [Compare v3.2.5...v4.0.0](https://github.com/cs1302uga/cs1302-tracer/compare/v3.2.5...v4.0.0)

### Breaking changes

- **Build tooling:** require Maven 3.10.0 or later; use the regenerated `mvnw`
  and `mvnw.cmd` wrappers. The Java runtime baseline remains 21.
- **Java API:** support `cs1302.tracer.api` and the stable `cs1302.tracer.App`
  launcher. Move parser/debugger/serializer machinery and wire models under
  `cs1302.tracer.internal`; replace exposed AST/Gson/JDI helpers with source-based
  tracing, detached outcomes, breakpoint discovery, and `BatchTracer`.
  Move budget and presentation types into `api`. See the
  [Java migration guide](docs/reference/java-api.md); no legacy helper aliases remain.
- **Module-path integration:** both JAR variants now declare the stable automatic
  module name `cs1302.tracer`. The bundled JAR excludes dependency module descriptors
  that would otherwise override this name. Consumers using a filename-derived or
  accidentally bundled dependency module name must
  update their `requires` directives or module launch configuration. Classpath
  and `java -jar` usage are unchanged.
- **Contributor tooling:** the scripts project now requires Python 3.15 and
  defaults to managed Python through `scripts/uv` and `scripts/uvx`. Follow
  [scripts setup](scripts/AGENTS.md) to provision it. The documentation build
  wrapper rejects custom output/site/configuration overrides; use its supported
  preview/published modes and standard output directory.

### Non-breaking changes

- Reject encoded links that resolve outside the documentation build instead of
  accepting undeployable assets from neighboring directories.

- **Python tooling:** migrate script tests to `scripts/tests/`, add typed release
  models and clearer invalid-input diagnostics, and enforce Ruff, ty, and 100%
  production line/branch coverage in a dedicated release-blocking CI job.
  Document managed tooling and coverage commands; retain existing Java Git hooks,
  CLI arguments, generated help text, and tracer output formats.
- Run documentation CI and deployment tooling through the managed Python wrapper
  so annotation evaluation and dependencies match the declared scripts runtime.

- **Internal tracing:** resolve Java, CLI, envelope, and NDJSON job policy through a
  shared typed module. Preserve supported Java signatures, CLI diagnostics and
  validation timing, source-discovery policies, and serialized output formats.
  Revalidate compatibility, strict coverage, and the existing CodeQL resolutions;
  see the [refactor review](docs/reviews/trace-job-resolution.md).

- Refresh current documentation for supported Java integration, internal ownership,
  managed tooling, and release workflows; label historical review evidence and
  require documentation accuracy as part of agent completion.

- Make API overview examples conform to Checkstyle and clarify that supported
  library calls return failure outcomes/diagnostics rather than exposing internal
  nesting exceptions. Record the complete repository audit and its revalidated
  CodeQL dispositions.

- Keep lambda-rendering parser state independent across concurrent batch jobs,
  preventing parser assertions and missing lambda metadata without changing output
  representations.

- Trace valid Java NaN and infinity values without snapshot-accounting failures.
  Modern non-finite floats use the existing special-double string convention;
  Python Tutor encodings, finite numbers, and output structures stay unchanged.
- Correct `BatchTracer` constructor documentation to require positive numeric
  settings; rejection of invalid settings is unchanged.

- Correct one-shot `Tracer.trace` source discovery to use only submitted sources,
  as documented, rather than discovering neighboring files in the caller's working
  directory. CLI/batch discovery and serialized output formats are unchanged.

- Update JavaParser's Guava and Javassist support libraries to 33.7.2-jre and
  3.33.0-GA, and align shared Error Prone/JSpecify annotations at 2.50.0/1.0.1.
  Keep the parser, serializers, CLI library, and output contracts unchanged.
- Update test-only JSON-java to 20260814 and Byte Buddy to 1.18.15 through
  dependency management, retaining JSONassert and AssertJ's current versions.
- Regenerate both official Maven Wrapper 3.3.4 scripts for Maven 3.10.0, pin
  the distribution checksum, and use the wrapper in CI and the pre-push hook.
  Enforce Maven/JDK prerequisites and add Windows wrapper compilation checks.
- Upgrade the Checkstyle engine to 14.3.0 and share its version across plugin
  configuration, retaining the existing style rules.
- Upgrade ASM to 9.11 for bytecode reading and validation fixes, preserving
  breakpoint discovery and serialized trace formats.
- Declare API stability with API Guardian: supported types and the launcher are
  `STABLE`, and public implementation types are `INTERNAL`, since 4.0.0. Add API
  Guardian as a transitive compile dependency and permit only its annotation
  metadata in the API exposure guard. Manual regular-JAR consumers must include
  API Guardian when compiling to avoid missing annotation warnings.
- Add a library-consumer Javadoc overview with a runnable tracing quickstart,
  API entry points, outcome and resource-ownership guidance, and integration links.
- Add a Javadoc copyright footer using the Git-history inception year (2025),
  the build-time current year, and a link to the MIT license.
- Enable the version-matched Picocli annotation processor for compile-time command
  checks and packaged CLI native-image metadata, without adding runtime dependencies
  or changing CLI arguments or output formats. Native-image execution is not validated.
- Preserve CLI and JSON contracts while introducing a supported
  Java interface, public Javadoc filtering, external-consumer compilation, and
  a compiled-declaration exposure check.
- Configure JavaParser core and symbol-solver Javadoc links matching the dependency
  version for contributor API builds.
- Document production Java packages with `package-info.java` summaries and links
  to their main types for generated Javadoc.
- Preserve Python Tutor JSON, modern JSON, and batch output contracts through the
  Java API refactors. Example reflow updates source line numbers and corresponding
  fixtures without changing the output schemas.
- Scope output finalization and logical input observation to individual jobs;
  centralize source evidence, lambda selection, snapshot retention, and rendering.
- Host versioned Javadoc through Docusaurus and unify guide/API release selection
  in a validated build manifest. Validate historical tags and reject unsupported
  build overrides before generation.
- Strengthen compiler/Checkstyle rules, regression coverage, and static-analysis
  evidence; pin the release action to a verified commit.
- Add pinned uv/uvx wrappers, inline PEP 723 dependency guidance, and repository
  instructions requiring complete CLI documentation and maintained release notes.
- Bootstrap this changelog from published release and Git history.

### Commits

- [`586522a`](https://github.com/cs1302uga/cs1302-tracer/commit/586522a00e347ca44a333f0470a1e4f127e9a4cc) — Prepare 4.0.0: supported Java API, trace-job ownership, and verified tooling (#17).

### Development history

The published `v3.2.5..v4.0.0` range contains only the squash commit `586522a`.
The following original development commits informed the curated summaries and
are retained as historical evidence; they are not separate commits in that
published range.

- [`0e82ee0`](https://github.com/cs1302uga/cs1302-tracer/commit/0e82ee0f2f95c8a7f62e70c75bbcaf12d2c1155e) Baseline type metadata and job output ownership refactors
- [`923d40d`](https://github.com/cs1302uga/cs1302-tracer/commit/923d40dea4eb033c54c2d7952ae5fae8e527d373) Cover frozen stderr through the public job output interface
- [`125eba9`](https://github.com/cs1302uga/cs1302-tracer/commit/125eba9011707f5f74588f394593c8b0789e4776) Remove unreachable receiver cache invalidation branch
- [`6f17548`](https://github.com/cs1302uga/cs1302-tracer/commit/6f175481d855d180c71b7c3d2e7e3846e049946c) Remove resolver parameters superseded by reified metadata
- [`cd9c794`](https://github.com/cs1302uga/cs1302-tracer/commit/cd9c794937b1c5fceebe0625efa9a200d612a9ad) Resolve intentional long output target comparison alert
- [`a745b17`](https://github.com/cs1302uga/cs1302-tracer/commit/a745b1793ee6bebf778ff92ad0fd29d1f351093c) Resolve worker pool offer alerts with ownership invariant
- [`0cc840d`](https://github.com/cs1302uga/cs1302-tracer/commit/0cc840daf5fd450063928fcb551adba9344b7c4b) Resolve required Java 21 pattern binding alerts
- [`2587bf2`](https://github.com/cs1302uga/cs1302-tracer/commit/2587bf2e9ac39ceb29371bbb1a8e18fba5f991b3) Resolve CLI path alerts against the caller input contract
- [`dea85e9`](https://github.com/cs1302uga/cs1302-tracer/commit/dea85e9d26ca2f306849332e252528a5d86eba23) Resolve JDI argument alert and record final review evidence
- [`abe4a86`](https://github.com/cs1302uga/cs1302-tracer/commit/abe4a863a428a639ae757b1d8900e7cd3fcad301) Own compiled programs and source evidence in source preparation
- [`ec2b0be`](https://github.com/cs1302uga/cs1302-tracer/commit/ec2b0bea75c37f11456792cc9704c292397158c7) Wake pipe readers before asserting finalized job output
- [`cf8fc55`](https://github.com/cs1302uga/cs1302-tracer/commit/cf8fc55c3749930122628a3ca3dca3c906623e15) Centralize snapshot retention accounting and presentation policies
- [`e736d82`](https://github.com/cs1302uga/cs1302-tracer/commit/e736d82246eff25b71f192cb1bf4e84271450e09) Separate trace dispatch from output rendering
- [`bbd69dd`](https://github.com/cs1302uga/cs1302-tracer/commit/bbd69dd586384e2870c824af968167c9140bf077) Own source observations and lambda selection in source analysis
- [`a1bac6e`](https://github.com/cs1302uga/cs1302-tracer/commit/a1bac6ec8bdb76291b9cf0a869445b642a55f99e) Own logical input observation for each guest job
- [`fa543e1`](https://github.com/cs1302uga/cs1302-tracer/commit/fa543e1478b8c0007a16537d490ceca4550386b7) Bump major version to 4.0.0
- [`8a5a30c`](https://github.com/cs1302uga/cs1302-tracer/commit/8a5a30c78a99faed7a4e74e1222239405a9c4746) Host versioned Javadoc with the documentation site
- [`a8f5eca`](https://github.com/cs1302uga/cs1302-tracer/commit/a8f5eca30bf84bf4b09fa655d30cf7a235d209ae) Own documentation release selection and build manifests
- [`53e0a6d`](https://github.com/cs1302uga/cs1302-tracer/commit/53e0a6de7dbb73af5e1987797cc1c054623268d7) Exercise debugger behavior through production seams
- [`2913520`](https://github.com/cs1302uga/cs1302-tracer/commit/29135205b2ca3518b133377c2f7c2f621215f054) Cover missing historical tags in documentation previews
- [`93afcc7`](https://github.com/cs1302uga/cs1302-tracer/commit/93afcc78a5bfa796e29e4d45aa452a7cd6b2d366) Remove unused imports reported by CodeQL
- [`49bd421`](https://github.com/cs1302uga/cs1302-tracer/commit/49bd421fb604e7e28dc9ef0355ff952f1b8f3f68) Document intentional preview interruption handling
- [`1898cd8`](https://github.com/cs1302uga/cs1302-tracer/commit/1898cd831c9c119a478131a1fada4f0c25b50452) Reject documentation output overrides before generation
- [`5d883f5`](https://github.com/cs1302uga/cs1302-tracer/commit/5d883f5f3118185f9098b6609e55b0f77d3bd31d) Pin the release action to its verified v3 commit
- [`5c1bc4d`](https://github.com/cs1302uga/cs1302-tracer/commit/5c1bc4d875b6636c72314b02e8aefc90ec79cbc4) Resolve command-execution audit records and record final validation
- [`c25f156`](https://github.com/cs1302uga/cs1302-tracer/commit/c25f156f5ea05611bef29ac1261a21f19ad46d00) Set up managed Python tooling and pinned uv wrappers for scripts
- [`3ecddf5`](https://github.com/cs1302uga/cs1302-tracer/commit/3ecddf5cac8086cb32c80ee29e9a4be41989db3e) Update scripts to Python 3.15 and document inline dependencies
- [`702e419`](https://github.com/cs1302uga/cs1302-tracer/commit/702e419c95c46a3f27020fb72bda105f4f57af78) Document repository workflows and changelog requirements
- [`d948fc0`](https://github.com/cs1302uga/cs1302-tracer/commit/d948fc0b4016601e35dff60e3d878399e94292bb) Establish supported Java API and library documentation
- [`9edd36a`](https://github.com/cs1302uga/cs1302-tracer/commit/9edd36a332b65dde03035096cd979e8430d9a199) Upgrade Maven toolchain and validated dependencies
- [`97eee09`](https://github.com/cs1302uga/cs1302-tracer/commit/97eee0931d5bbe9a2002b93b7bd2ed61ad72b173) Link completed API and dependency upgrades in changelog
- [`8dfd93f`](https://github.com/cs1302uga/cs1302-tracer/commit/8dfd93f67f22eb657c2e456e1b59a8949f44c7f1) Make API overview examples conform to Checkstyle
- [`b7dfad2`](https://github.com/cs1302uga/cs1302-tracer/commit/b7dfad251a2d5c6415e33bd7d9d08ceec0bf0169) Keep one-shot tracing limited to submitted sources
- [`b5edc57`](https://github.com/cs1302uga/cs1302-tracer/commit/b5edc57910731626e145614bab729c3cd289d91d) Document positive BatchTracer settings and test API validation
- [`2e47b1a`](https://github.com/cs1302uga/cs1302-tracer/commit/2e47b1a826362309c505ba9fe71f9ea65f0d0c6b) Trace non-finite floating-point values without invalid JSON
- [`830555d`](https://github.com/cs1302uga/cs1302-tracer/commit/830555da6fc84b11f327d02348f523d2ec7f40a9) Isolate lambda parsers across concurrent batch jobs
- [`7975458`](https://github.com/cs1302uga/cs1302-tracer/commit/7975458ca503af5e76b59ff983ff7ccef3fc105e) Clarify API failure contracts and record completed repository audit
- [`3d43db0`](https://github.com/cs1302uga/cs1302-tracer/commit/3d43db02c4e69a7484f77f87b7f4c3ebb3ee32f8) Refresh current documentation and project developer metadata
- [`fc0b82c`](https://github.com/cs1302uga/cs1302-tracer/commit/fc0b82c6e52f239e00a719c603265a10ce4e234a) Resolve trace job policy across Java and command callers
- [`5ed0d82`](https://github.com/cs1302uga/cs1302-tracer/commit/5ed0d8248ac9921a960d7c5aacee38211005e033) Record trace job resolution review and scan dispositions
- [`c8d3c21`](https://github.com/cs1302uga/cs1302-tracer/commit/c8d3c218a8c0170e761643ae4c6610ccdf1e24af) Enforce typed Python tooling and complete behavioral coverage
- [`5f317eb`](https://github.com/cs1302uga/cs1302-tracer/commit/5f317eb7639f4203950bdb86169737f1c99bd1e8) Use managed Python throughout documentation CI
- [`1d7408f`](https://github.com/cs1302uga/cs1302-tracer/commit/1d7408f0a08fa698e113dbfc534bad8ba9505181) Reject rendered links escaping the documentation build

## 3.2.5 — 2026-10-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.2.5) · [Compare v3.2.4…v3.2.5](https://github.com/cs1302uga/cs1302-tracer/compare/v3.2.4...v3.2.5)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Stabilize stream-drainer tests, remove unused private type-resolution parameters, and prepare the
release; no intended production behavior or output-format change.

### Commits

- [`d559faa`](https://github.com/cs1302uga/cs1302-tracer/commit/d559faaeda3e28f1a33e46140b2727d57f2dc844) Stabilize stream-drainer tests and prepare 3.2.5 (#16)

## 3.2.4 — 2026-10-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.2.4) · [Compare v3.2.3…v3.2.4](https://github.com/cs1302uga/cs1302-tracer/compare/v3.2.3...v3.2.4)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Bound invocation type inference and stabilize the JDK 25 stream-drainer regression test. Keep
serialized trace formats unchanged.

### Commits

- [`9edaf6b`](https://github.com/cs1302uga/cs1302-tracer/commit/9edaf6b4b7567b969058e1abcad6a584c9590e4c) Stabilize flaky JDK 25 stream-drainer test (#14)
- [`ecf851c`](https://github.com/cs1302uga/cs1302-tracer/commit/ecf851ccf9d22892db4aea2b5ad9c7fb2d09445f) Prepare 3.2.4 and bound invocation type inference (#15)

## 3.2.3 — 2026-10-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.2.3) · [Compare v3.2.2…v3.2.3](https://github.com/cs1302uga/cs1302-tracer/compare/v3.2.2...v3.2.3)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Make generic expression inference conservative when evidence is uncertain and fix a bounded-tracing
guest exit-status race.

### Commits

- [`00ce110`](https://github.com/cs1302uga/cs1302-tracer/commit/00ce110ffeb0ae7052338f3a404352e8c90aa55f) Prepare 3.2.3 with conservative generic expression inference (#12)
- [`a2954bb`](https://github.com/cs1302uga/cs1302-tracer/commit/a2954bb21d430f77f97bc3efb3d48f32c338a993) Fix guest exit status race in bounded tracing (#13)

## 3.2.2 — 2026-10-14

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.2.2) · [Compare v3.2.1…v3.2.2](https://github.com/cs1302uga/cs1302-tracer/compare/v3.2.1...v3.2.2)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Recover generic invocation type labels using source symbol resolution, improving labels without
changing the JSON structure.

### Commits

- [`fd2eb80`](https://github.com/cs1302uga/cs1302-tracer/commit/fd2eb80af42909e9ea01a4d53f75da1924be556f) Resolve generic invocation type labels for 3.2.2 (#11)

## 3.2.1 — 2026-10-01

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.2.1) · [Compare v3.1.2…v3.2.1](https://github.com/cs1302uga/cs1302-tracer/compare/v3.1.2...v3.2.1)

### Breaking changes

Input validation: hardened source handling rejects unsafe source paths rather than accepting them.
Supply valid contained source paths. Multithread tracing requires modern JSON; callers opting into
it must use `--format=modern` and handle thread metadata.

### Non-breaking changes

Add opt-in multithread capture and thread limits, preserve exception output in batch jobs, and
introduce versioned Docusaurus documentation and release workflows. Update build/actions
dependencies and documentation licensing. This range includes the unpublished 3.2.0 preparation.

### Commits

- [`63fc098`](https://github.com/cs1302uga/cs1302-tracer/commit/63fc098ac4d7ac2a10c7883173b2d40633803bcf) Fix redundant worker close warning and bump version to 3.1.2 (#5)
- [`4c2ab2b`](https://github.com/cs1302uga/cs1302-tracer/commit/4c2ab2bdf1edb7ce8ec60636b5fbfcadb355436c) Add global code owner
- [`f5d00a6`](https://github.com/cs1302uga/cs1302-tracer/commit/f5d00a679127d2b42c02c581c4e79424c6aab0d7) Add versioned documentation site and release workflows
- [`5b3b0f1`](https://github.com/cs1302uga/cs1302-tracer/commit/5b3b0f13ce753f166cfe337eaaa8fa8f1c0f12d0) Add multithread tracing and harden source handling (3.2.0) (#7)
- [`020907c`](https://github.com/cs1302uga/cs1302-tracer/commit/020907c6da953e64a1a032e26b94b86b8bfdb774) Fix build deprecation warnings and update GitHub Actions (#8)
- [`2526d54`](https://github.com/cs1302uga/cs1302-tracer/commit/2526d5422ede24d9c2d0a902734a09b241133301) Release 3.2.1 with preserved batch exception output (#9)

## 3.1.2 — 2026-09-24

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.1.2) · [Compare v3.1.1…v3.1.2](https://github.com/cs1302uga/cs1302-tracer/compare/v3.1.1...v3.1.2)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Consolidate the source-map/entry-file work in release history and remove a redundant worker-close
warning. The consecutive tagged diff does not add another JSON schema change beyond 3.1.1.

### Commits

- [`dde5e2a`](https://github.com/cs1302uga/cs1302-tracer/commit/dde5e2afab85365b0b98bbabd7bfdb1486895949) Add source maps and entry files to both JSON trace formats (#4)
- [`705a102`](https://github.com/cs1302uga/cs1302-tracer/commit/705a102ed59b4f9d53fb4317d12b8e9a109368ca) Fix redundant worker close warning and bump version to 3.1.2

## 3.1.1 — 2026-09-24

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.1.1) · [Compare v3.1.0…v3.1.1](https://github.com/cs1302uga/cs1302-tracer/compare/v3.1.0...v3.1.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add `sources` and `entryFile` metadata to both JSON formats, preserving `code`. Include submitted
files and source text for multi-file viewers, reject duplicate source paths, and reuse metadata
across Python Tutor breakpoint snapshots. Consumers should tolerate the additive fields.

### Commits

- [`3c1aa3c`](https://github.com/cs1302uga/cs1302-tracer/commit/3c1aa3c2e2329a3ca8b850f97cd524f36286046b) Add source maps, close coverage gaps, and bump to 3.1.1
- [`a2ae4d4`](https://github.com/cs1302uga/cs1302-tracer/commit/a2ae4d43b73dba726e6c143418b7ec5dc5e175bf) Reuse source metadata across PythonTutor breakpoint snapshots

## 3.1.0 — 2026-09-18

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.1.0) · [Compare v3.0.0…v3.1.0](https://github.com/cs1302uga/cs1302-tracer/compare/v3.0.0...v3.1.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add the NDJSON `batch-trace` command with concurrent workers and reusable guest sessions, bounded
admission, per-job results, and worker recycling. Preserve direct tracing and existing trace
formats. Stabilize tag-triggered release checkout and document batch integration.

### Commits

- [`3a598fe`](https://github.com/cs1302uga/cs1302-tracer/commit/3a598fee5cc20018a39d072f448295ac06ca36c8) Stabilize release-job checkout for tag-triggered runs (#2)
- [`9553057`](https://github.com/cs1302uga/cs1302-tracer/commit/9553057c03bf68c6436be5c583bf09a3ebb707f8) docs: update README.md with CLI options and v3 features
- [`3abb17a`](https://github.com/cs1302uga/cs1302-tracer/commit/3abb17ac4ec8d23b7790c99ec00ca0bb44eb472d) feat: high-throughput batch-trace command with persistent guest sessions (#3)

## 3.0.0 — 2026-09-18

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v3.0.0) · [Compare v2.12.2…v3.0.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.12.2...v3.0.0)

### Breaking changes

Java API: `ExecutionSnapshot` record components use `OutputSlice` rather than raw stdout/stderr
arrays. Existing byte-array constructors/accessors provide bridges, but record-pattern/reflection
consumers must use the new component shape. CLI validation now rejects invalid breakpoint
specifications, including file-qualified `-1`; use bare `-1` for main exit.

### Non-breaking changes

Add file-qualified and comma-separated breakpoints, preserve same-line hits across different files,
cache source analysis, and reduce output copying. Keep serialized output representations while
improving snapshot retention and buffer safety.

### Commits

- [`2aafc12`](https://github.com/cs1302uga/cs1302-tracer/commit/2aafc12478a8eeac0b5799a0e91b1be871091434) feat: optimize AST caching and output buffering, add qualified breakpoints (#1)
- [`0f535b7`](https://github.com/cs1302uga/cs1302-tracer/commit/0f535b70b5d4242248fa5bd58547c41d7b96475e) build: bump version to 3.0.0

## 2.12.2 — 2026-09-17

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.12.2) · [Compare v2.12.0…v2.12.2](https://github.com/cs1302uga/cs1302-tracer/compare/v2.12.0...v2.12.2)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Fix test resource warnings and update build dependencies. The intermediate 2.12.1 version bump is
included here because no published release record exists for it.

### Commits

- [`8eccb8b`](https://github.com/cs1302uga/cs1302-tracer/commit/8eccb8b4d3fab2bba06e6ce1cac5438b730f234a) Fix test resource warnings and bump version to 2.12.1
- [`8890078`](https://github.com/cs1302uga/cs1302-tracer/commit/8890078fc00435c7c931c8dcf88d87ffbc3f01c0) chore: update build dependencies and release v2.12.2

## 2.12.0 — 2026-09-17

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.12.0) · [Compare v2.11.5…v2.12.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.5...v2.12.0)

### Breaking changes

CLI defaults: ordinary `trace` runs now have finite budgets, including a 10-second deadline. A limit
stop emits no trace on stdout, reports the reason on stderr, and exits 3. Use `--unlimited` to
restore unbounded defaults, set individual limits explicitly, or opt into `--result-envelope` for
partial results.

### Non-breaking changes

Harden instructor tracing, track logically consumed stdin without counting reader lookahead, and
improve supported Scanner/BufferedReader/JDK 25 IO reader chains. Enforce compiler/lint/coverage
gates and document UTF-16 input offsets.

### Commits

- [`26e3234`](https://github.com/cs1302uga/cs1302-tracer/commit/26e323451e8fee86524fb081001154a5558840bd) feat: harden instructor tracing and enforce lint and coverage gates

## 2.11.5 — 2026-09-16

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.5) · [Compare v2.11.4…v2.11.5](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.4...v2.11.5)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Prevent array types from being incorrectly assigned to class-instance allocations and update GitHub
Actions dependencies.

### Commits

- [`3fda8f6`](https://github.com/cs1302uga/cs1302-tracer/commit/3fda8f695b1a0bd0045144fad3947d64840af926) Updated GitHub Actions versions
- [`3fc51b5`](https://github.com/cs1302uga/cs1302-tracer/commit/3fc51b5f38b81f3298faefe047ca24a8933664b4) fix: prevent array types from misattributing to class instance allocations and release v2.11.5

## 2.11.4 — 2026-09-16

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.4) · [Compare v2.11.3…v2.11.4](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.3...v2.11.4)

### Breaking changes

Inspection behavior: lazy enum hash codes are evaluated by default, which may invoke guest behavior.
Use `--no-eval-enum-hash` to disable this; `--eval-enum-hash` explicitly enables it.

### Non-breaking changes

Expose CLI control over enum hash evaluation to improve enum visualization.

### Commits

- [`e6b54cf`](https://github.com/cs1302uga/cs1302-tracer/commit/e6b54cf0a0d88696571d28d1fdb64a2e72ec553f) feat: evaluate lazy enum hash codes by default with opt-out option

## 2.11.3 — 2026-09-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.3) · [Compare v2.11.2…v2.11.3](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.2...v2.11.3)

### Breaking changes

Display labels: enum heap type labels now include the constant name. Consumers parsing labels as
class names must separate the constant suffix or use structured information instead.

### Non-breaking changes

Identify enum constants directly in heap displays in both formats.

### Commits

- [`8534ee1`](https://github.com/cs1302uga/cs1302-tracer/commit/8534ee182c612dbb1fb4416129ef5bce3b9e6a7d) feat: enrich enum heap object labels with qualified constant names

## 2.11.2 — 2026-09-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.2) · [Compare v2.11.1…v2.11.2](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.1...v2.11.2)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Remove a duplicate chronological snapshot at main-method exit, so the final step is not shown twice.

### Commits

- [`7312864`](https://github.com/cs1302uga/cs1302-tracer/commit/731286433cd7c606d66a1222e3a856816fef92db) fix: deduplicate chronological snapshot on main method exit

## 2.11.1 — 2026-09-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.1) · [Compare v2.11.0…v2.11.1](https://github.com/cs1302uga/cs1302-tracer/compare/v2.11.0...v2.11.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Avoid rerunning tests during release packaging after validation has already completed; no tracer
behavior change.

### Commits

- [`1db84ec`](https://github.com/cs1302uga/cs1302-tracer/commit/1db84ece7edefff30d5997bf09fbe1e3d2597104) ci: skip test re-execution in release workflow and release v2.11.1

## 2.11.0 — 2026-09-15

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.11.0) · [Compare v2.10.1…v2.11.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.10.1...v2.11.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add cumulative `stdinConsumed` and `stdinOffset` metadata for terminal input highlighting. This is
an additive JSON change; consumers should tolerate the new fields.

### Commits

- [`b2eba47`](https://github.com/cs1302uga/cs1302-tracer/commit/b2eba47d60e968e34ad1712301f18d2ed7686dd7) feat: track consumed standard input for terminal simulation

## 2.10.1 — 2026-09-14

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.10.1) · [Compare v2.10.0…v2.10.1](https://github.com/cs1302uga/cs1302-tracer/compare/v2.10.0...v2.10.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Mark the internal ReferenceQueue queued field transient to eliminate a javac serialization warning.

### Commits

- [`158c1e3`](https://github.com/cs1302uga/cs1302-tracer/commit/158c1e3c083b57d96df4f8f65c18b4d341a8b0ae) fix: mark queued field in ReferenceQueue as transient to resolve javac serial warning

## 2.10.0 — 2026-09-14

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.10.0) · [Compare v2.9.1…v2.10.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.9.1...v2.10.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Supply guest input with mutually exclusive `--stdin` and `--stdin-file`, and support tracing Java
preview features with the corresponding JDK. Source input and guest stdin are separate inputs.

### Commits

- [`dd9fb95`](https://github.com/cs1302uga/cs1302-tracer/commit/dd9fb95586a7a9adf428b2e8ea5015d83f43a784) feat: support guest standard input and preview feature tracing

## 2.9.1 — 2026-09-14

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.9.1) · [Compare v2.9.0…v2.9.1](https://github.com/cs1302uga/cs1302-tracer/compare/v2.9.0...v2.9.1)

### Breaking changes

Guest execution environment: AWT runs headless. Programs requiring a graphical display must be
adapted to headless execution; the tracer does not provide an interactive desktop.

### Non-breaking changes

Suppress the leading `Picked up JAVA_TOOL_OPTIONS` JVM banner so captured program output is not
polluted.

### Commits

- [`a3d37eb`](https://github.com/cs1302uga/cs1302-tracer/commit/a3d37ebb1233b9fb521b262200a0251d252052bc) fix: suppress Picked up JAVA_TOOL_OPTIONS banner and enforce headless mode

## 2.9.0 — 2026-09-14

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.9.0) · [Compare v2.8.1…v2.9.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.8.1...v2.9.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Introduce opt-in bounded jobs through `--result-envelope`, explicit resource budgets, partial
results, and field-only inspection. Contain source writes, clean compilation resources, preserve
output before the first snapshot, report abnormal exits, and discover breakpoints without executing
guest code. Add `java.awt.Color` heap support, regression fixtures, and a separate hosted-runner
isolation contract. Existing non-envelope invocations retain their output contract.

### Commits

- [`10b620b`](https://github.com/cs1302uga/cs1302-tracer/commit/10b620b437c70c5bad7a7fdfdbe1411aa1cda65b) docs: record bounded tracing implementation plan
- [`f49cc40`](https://github.com/cs1302uga/cs1302-tracer/commit/f49cc4006fccac423a0880ce55dec43b200dc5b4) fix: contain source writes and clean compilation resources
- [`98c5756`](https://github.com/cs1302uga/cs1302-tracer/commit/98c5756758affbd5eda57866c40471affeda8482) feat: define opt-in trace budgets and result envelope
- [`9a6a096`](https://github.com/cs1302uga/cs1302-tracer/commit/9a6a096519e589e996f0ac1fda4b397e8b07d5e2) feat: bound tracing sessions and return partial job results
- [`e771f24`](https://github.com/cs1302uga/cs1302-tracer/commit/e771f244ea3bdc52f70cdbe2d5240ee2b14525dd) fix: discover breakpoints without executing guest code
- [`42b002a`](https://github.com/cs1302uga/cs1302-tracer/commit/42b002ae39dab88954b1560a3c7c3248bf04ce0f) fix: retain guest output when tracing stops before a snapshot
- [`5562de6`](https://github.com/cs1302uga/cs1302-tracer/commit/5562de6eb75fa5372a8f315e84a6ae97f58fc371) test: lock Python Tutor compatibility against main fixtures
- [`d03f0f3`](https://github.com/cs1302uga/cs1302-tracer/commit/d03f0f3918f92aa53fe05f850ca0e4751294abff) test: track reviewed regression expectations
- [`4f29e03`](https://github.com/cs1302uga/cs1302-tracer/commit/4f29e03b6b4d312d1dc4dfb124b8aa2a065bf7f4) fix: bound latest-hit retention and report abnormal guest exits
- [`6620f0a`](https://github.com/cs1302uga/cs1302-tracer/commit/6620f0a7b7efa37267cd79cfb3dc5f3f9d96693e) docs: define bounded job behavior and Linux runner contract
- [`ae63298`](https://github.com/cs1302uga/cs1302-tracer/commit/ae63298fe05d3e856db85cabf8f3c1ec8093e99f) docs: record completed milestone and cross-JDK validation
- [`16e0480`](https://github.com/cs1302uga/cs1302-tracer/commit/16e04809fe04c0b58a20991a68891ec82da38e87) feat: support java.awt.Color objects on the heap

## 2.8.1 — 2026-09-12

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.8.1) · [Compare v2.8.0…v2.8.1](https://github.com/cs1302uga/cs1302-tracer/compare/v2.8.0...v2.8.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add reference examples for numeric and non-numeric wrapper classes; no production behavior change.

### Commits

- [`bd7626f`](https://github.com/cs1302uga/cs1302-tracer/commit/bd7626fa22a28fb7a8a88da37907f525eec1a705) feat: add reference examples for numeric and non-numeric wrapper classes

## 2.8.0 — 2026-09-12

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.8.0) · [Compare v2.7.0…v2.8.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.7.0...v2.8.0)

### Breaking changes

Python Tutor JSON: boxed primitives on the heap become `INSTANCE` objects with a `value` field;
their type attributes describe that primitive field. Update consumers that expected a scalar heap
entry or a scalar wrapper type attribute.

### Non-breaking changes

Improve boxed-value visualization and add empty-string/empty-array reference examples.

### Commits

- [`32bbb4a`](https://github.com/cs1302uga/cs1302-tracer/commit/32bbb4a433312baf87780c92b6bc0997eb514811) feat(examples): add reference example 28 for empty string and array instances
- [`27208e0`](https://github.com/cs1302uga/cs1302-tracer/commit/27208e040e3c5e46b1edf3889bd89abc4080ad76) docs: update example28 documentation for length label format
- [`8b0145f`](https://github.com/cs1302uga/cs1302-tracer/commit/8b0145f7e5d35428212da910f40b4ebb23474bf9) feat: serialize boxed primitives on heap as INSTANCE with primitive field types

## 2.7.0 — 2026-09-11

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.7.0) · [Compare v2.6.3…v2.7.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.6.3...v2.7.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Improve static analysis and specification references, and add a multi-level inheritance example.

### Commits

- [`03e34f3`](https://github.com/cs1302uga/cs1302-tracer/commit/03e34f3f6cfedaa9da09f82eb917ab733b7be748) feat: modernize static analysis, normative specifications, and add example27 multi-level inheritance

## 2.6.3 — 2026-09-11

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.6.3) · [Compare v2.6.2…v2.6.3](https://github.com/cs1302uga/cs1302-tracer/compare/v2.6.2...v2.6.3)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Use concrete runtime classes in heap labels and resolve generic arguments inherited through
subclasses and interfaces. Preserve the older four-argument `ClassGenericInfo` constructor.

### Commits

- [`d422941`](https://github.com/cs1302uga/cs1302-tracer/commit/d42294194c38e8d1854f592c316bffe3985f9e15) feat: reify concrete runtime classes on heap objects and resolve generic subclasses

## 2.6.2 — 2026-09-11

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.6.2) · [Compare v2.6.1…v2.6.2](https://github.com/cs1302uga/cs1302-tracer/compare/v2.6.1...v2.6.2)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add a banking example for the teaching/reference collection; no production behavior change.

### Commits

- [`3daf6f7`](https://github.com/cs1302uga/cs1302-tracer/commit/3daf6f7496a466d7b58b08a05d653ffb927a3567) feat: add example25 (banking) and bump version to 2.6.2

## 2.6.1 — 2026-09-11

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.6.1) · [Compare v2.6.0…v2.6.1](https://github.com/cs1302uga/cs1302-tracer/compare/v2.6.0...v2.6.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Prevent a JDI cleanup deadlock when the target process terminates, allowing tracing jobs to finish
reliably.

### Commits

- [`ec5060a`](https://github.com/cs1302uga/cs1302-tracer/commit/ec5060a17734360986c4cf788d6269a3a1f5d065) fix: prevent JDI VM cleanup deadlock on target process termination

## 2.6.0 — 2026-09-11

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.6.0) · [Compare v2.5.0…v2.6.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.5.0...v2.6.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Avoid duplicate generic arguments in type labels and add an example covering the regression.

### Commits

- [`5d74838`](https://github.com/cs1302uga/cs1302-tracer/commit/5d74838b91f1be2e30d5d0e08dd332dd2e0e0544) chore: ignore .agents directory in .gitignore
- [`3f91416`](https://github.com/cs1302uga/cs1302-tracer/commit/3f914166b587d5b98df9898287025501a163ea44) feat: fix duplicate generic type arguments and add example24

## 2.5.0 — 2026-09-02

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.5.0) · [Compare v2.4.0…v2.5.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.4.0...v2.5.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Retain uncaught-exception stderr in traces and add an exception example so viewers can show the
failure output.

### Commits

- [`124a5b9`](https://github.com/cs1302uga/cs1302-tracer/commit/124a5b9c597a7422fbd5928785df860dec2094d7) feat: capture uncaught exception stderr in traces and add example23

## 2.4.0 — 2026-09-02

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.4.0) · [Compare v2.3.0…v2.4.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.3.0...v2.4.0)

### Breaking changes

CLI output formatting: trace JSON becomes compact by default. Add `--pretty` (or `-p`) to retain
indented output in scripts or saved examples; parsed JSON consumers keep the same structure.

### Non-breaking changes

Capture stdout/stderr at each trace step and expose explicit pretty-printing control.

### Commits

- [`a5c63fa`](https://github.com/cs1302uga/cs1302-tracer/commit/a5c63fa97d3d130d7f4b0851f001a62e5e95b869) feat: capture stdout/stderr at each step and add --pretty JSON option

## 2.3.0 — 2026-08-28

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.3.0) · [Compare v2.1.0…v2.3.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.1.0...v2.3.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Render reified generic arguments in object labels and add `--type-style=fqn|simple` (default `fqn`).
Existing three-argument serializer constructors remain available. Label text becomes more
informative without changing the enclosing JSON schema.

### Commits

- [`3dbec17`](https://github.com/cs1302uga/cs1302-tracer/commit/3dbec17483317c0a536f59c85f0647313547b902) feat: support reified generic types in object labels and add --type-style CLI option
- [`590e35e`](https://github.com/cs1302uga/cs1302-tracer/commit/590e35ea325efe7f867f2e98a848a95468977aff) build: bump version to 2.3.0

## 2.1.0 — 2026-08-28

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.1.0) · [Compare v2.0.0…v2.1.0](https://github.com/cs1302uga/cs1302-tracer/compare/v2.0.0...v2.1.0)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Improve AST-based generic type extraction and frame/field type recovery. Add the Maven wrapper and
align source formatting and Checkstyle rules.

### Commits

- [`5e71ea6`](https://github.com/cs1302uga/cs1302-tracer/commit/5e71ea6e9a2e1adc2f34d11baa4ce0be2c91c605) build: bump version to 2.0.1 and add maven wrapper
- [`c6a2632`](https://github.com/cs1302uga/cs1302-tracer/commit/c6a2632b50fbdf1fd881b11f310f0747666d3f26) feat: add AST generic type extraction and resolve Checkstyle closing brace rules
- [`f6c6bb9`](https://github.com/cs1302uga/cs1302-tracer/commit/f6c6bb9d6f2734b08d7a1559649bc2d7e01ffa92) build: bump version to 2.1.0

## 2.0.0 — 2026-08-27

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v2.0.0) · [Compare v1.0.8…v2.0.0](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.8...v2.0.0)

### Breaking changes

Java API: compilation, snapshot, and serialization models are reorganized, including dedicated
`model.pytutor` and `model.modern` packages and typed serializer results. Recompile integrations and
migrate model imports and serializer calls using the tagged source. Consumers choosing
`--format=modern` must implement its separate object-graph schema; it is not interchangeable with
Python Tutor JSON.

### Non-breaking changes

Add modern JSON, multi-file/package discovery, delimited source streams, chronological
`--all-breakpoints` tracing, source-path metadata, generic type recovery, lambda reconstruction, and
finality metadata. Include a documented example curriculum, distribution archives, and stronger
automated validation.

### Commits

- [`ca164ef`](https://github.com/cs1302uga/cs1302-tracer/commit/ca164ef674238cdeb8be07a8f5bec51c89759718) Use `google-java-format` to format files
- [`c287fd1`](https://github.com/cs1302uga/cs1302-tracer/commit/c287fd1cfb7ed99f4ffc040df525bcd90dcdb3da) Add final information into serialized trace
- [`fd47729`](https://github.com/cs1302uga/cs1302-tracer/commit/fd47729eb5cecaa3c3cb4149a06be0a8ead10d99) Add support for lambda reconstruction for statics and method variables
- [`2ee271f`](https://github.com/cs1302uga/cs1302-tracer/commit/2ee271fc94cc7bd5ff9b36cc5fd91686148654bc) Make tab width 2 spaces in editorconfig
- [`1b75fde`](https://github.com/cs1302uga/cs1302-tracer/commit/1b75fded9de72ca0057c6a2e315f657479ee2b28) Add some basic black-box testing for cs1302-tracer commands
- [`622ff9d`](https://github.com/cs1302uga/cs1302-tracer/commit/622ff9d7aece658ecc5fa863ca09282d9b3b99c6) Update some documentation
- [`91776da`](https://github.com/cs1302uga/cs1302-tracer/commit/91776da6883b3095998f33bfbbde6e8fc1e48063) Add some basic, helpful git hooks for others to use
- [`7661f30`](https://github.com/cs1302uga/cs1302-tracer/commit/7661f30f7ffb845e719e4d732608ae0f0cd2f2af) Clarify lambda limitations
- [`5ffe067`](https://github.com/cs1302uga/cs1302-tracer/commit/5ffe067820126ebc43034fdbcf7024227f1b6636) Delete program files after test instead of waiting for JVM exit
- [`1542fb2`](https://github.com/cs1302uga/cs1302-tracer/commit/1542fb2357d2f9d9a412df93d2ac152ec4fd946e) Improve some documentation/comments
- [`40f34d1`](https://github.com/cs1302uga/cs1302-tracer/commit/40f34d15ae42c811a8b37d3221ea9c6b1c71cd15) feat: modernize tracer with modern format, multi-file packages, reified generics, and textbook examples

## 1.0.8 — 2025-11-09

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.8) · [Compare v1.0.7…v1.0.8](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.7...v1.0.8)

### Breaking changes

JSON: replace `ordered_globals_types`, `ordered_varnames_types`, and `heap_types` lookups with
`globals_attrs`, `locals_attrs`, and `heap_attrs`. Java API: field records now carry finality, and
tracing requires parsed source and returns lists of snapshots per breakpoint. Supply a
`CompilationUnit`, handle each list of hits, and update direct field-record construction.

### Non-breaking changes

Add `--accumulate-breakpoints` to retain every hit and detect declarations marked `final`.

### Commits

- [`249149f`](https://github.com/cs1302uga/cs1302-tracer/commit/249149f8aeffc2141ebcb7b14da57028a7092eb5) Add option to save a trace each time a breakpoint is hit
- [`2b2d37a`](https://github.com/cs1302uga/cs1302-tracer/commit/2b2d37a9d9d78ee4612bb23b887718999bc812fd) Add ability to detect when fields are declared final
- [`9b4d028`](https://github.com/cs1302uga/cs1302-tracer/commit/9b4d028d2c0d5e1f213886a3c195eb6533426bc9) Place type information under new location in serialization
- [`a73348d`](https://github.com/cs1302uga/cs1302-tracer/commit/a73348d90389a2e69b3a33aafb68e4c735d3c636) bumped version

## 1.0.7 — 2025-10-17

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.7) · [Compare v1.0.6…v1.0.7](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.6...v1.0.7)

### Breaking changes

CLI: tracing is now the explicit `trace` subcommand. Insert `trace` before trace options in existing
commands, for example `java -jar tracer.jar trace -i Main.java`.

### Non-breaking changes

Improve command help wording and separate common command input handling.

### Commits

- [`a56359b`](https://github.com/cs1302uga/cs1302-tracer/commit/a56359ba7e73c45c9dffbb63eb41dc856d363d55) Move trace into its own subcommand
- [`c61f049`](https://github.com/cs1302uga/cs1302-tracer/commit/c61f0499f115b824848e5ee000015b0af75f73f3) Rewrite command help messages in sentence case
- [`a92f3f4`](https://github.com/cs1302uga/cs1302-tracer/commit/a92f3f43448522346ac00afd301c613f01cb8bab) Bump version for release

## 1.0.6 — 2025-10-10

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.6) · [Compare v1.0.5…v1.0.6](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.5...v1.0.6)

### Breaking changes

CLI: Picocli replaces the earlier option parser. Replace `--breakpoint` with `--breakpoints`,
breakpoint-listing flags with `list-breakpoints` (and `--json`), and `--show-licenses` with
`show-licenses`. The former `--output` option is absent; redirect stdout to save a trace. Review
subcommand help when migrating scripts.

### Non-breaking changes

Add list type information to traces, contextual CLI help, version/verbose options, and editor
configuration.

### Commits

- [`ea22772`](https://github.com/cs1302uga/cs1302-tracer/commit/ea227721c8c0d9ed2659de159582a3aa9059529d) Add list types to traces
- [`d46f10c`](https://github.com/cs1302uga/cs1302-tracer/commit/d46f10c1787eedf76fabc0a692043b4bd72a172d) Move to new picocli-based command line
- [`55cff37`](https://github.com/cs1302uga/cs1302-tracer/commit/55cff374a3c0e0db400a3b2aae4dbec73426592f) Add simple editorconfig
- [`049a250`](https://github.com/cs1302uga/cs1302-tracer/commit/049a25057b3bfee76b132eb569324fe8404cfbde) Update pom.xml for new version

## 1.0.5 — 2025-10-04

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.5) · [Compare v1.0.4…v1.0.5](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.4...v1.0.5)

### Breaking changes

Java API: serialization moves from the static `PyTutorSerializer.serialize(source, snapshot,
flags...)` call to a configured serializer instance. Construct `PyTutorSerializer(removeMainArgs,
inlineStrings, removeMethodThis)` and call its instance method; snapshot/field records also gain
type and output information.

### Non-breaking changes

Capture stdout/stderr and instance-method `this`; expose field type metadata, correct stack ordering
and serializer option ordering, remove breakpoint-listing debug output, and use the special string
label `___NO_LABEL!___`. Consumers should tolerate the added trace metadata.

### Commits

- [`3e4d155`](https://github.com/cs1302uga/cs1302-tracer/commit/3e4d155ad0b6155de430bb2b864586cc89f2d8a2) Code cleanup: add Javadoc comments, bring in line with check1302
- [`fe55896`](https://github.com/cs1302uga/cs1302-tracer/commit/fe558966aa2f2fe4aad1fa4d357604c9559051d9) Keep track of field types for future use
- [`a6ca349`](https://github.com/cs1302uga/cs1302-tracer/commit/a6ca3496c5b9677508ddd7daddee7c530c0c818f) Fix incorrect ordering of stack frames in snapshot
- [`d03c2bf`](https://github.com/cs1302uga/cs1302-tracer/commit/d03c2bf3bcb8a3daa2d6a7a1feb9ac9773085962) Add `this` to traced frame variables when in non-static/native methods
- [`7b8080a`](https://github.com/cs1302uga/cs1302-tracer/commit/7b8080abdf1eb8a150e3d8c2f6a4ff0259745389) Refactor how options are passed to serializer, add `this` toggle
- [`945dfb0`](https://github.com/cs1302uga/cs1302-tracer/commit/945dfb0f18555939399bdc28a352a0f08cf8735c) Add stdout/stderr collection to trace snapshots
- [`e2ef00d`](https://github.com/cs1302uga/cs1302-tracer/commit/e2ef00db8ed8a1eb45d433e25bf3713e948a2086) Add special `___NO_LABEL!___` label to string instances when serializing
- [`dd08e83`](https://github.com/cs1302uga/cs1302-tracer/commit/dd08e83cd7529640b1ca15ba0f2cdb93b0876f34) Expose type information in PythonTutor serialization
- [`bf8a392`](https://github.com/cs1302uga/cs1302-tracer/commit/bf8a392f0c5c380de910a9adcba95cd744c69424) Track type information with method `this` and put it with other locals
- [`c014844`](https://github.com/cs1302uga/cs1302-tracer/commit/c0148442c078bb2eab0982fe272590180efaa0ba) Fix incorrect option order in PyTutorSerializer constructor call
- [`110bad7`](https://github.com/cs1302uga/cs1302-tracer/commit/110bad713b317ee8907fc9e905168302fc099ee3) Update DebugTraceHelper.java
- [`1d0c090`](https://github.com/cs1302uga/cs1302-tracer/commit/1d0c090c5a0740fa5c58f0e4dfa149df6fb7679d) Update pom.xml

## 1.0.4 — 2025-08-18

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.4) · [Compare v1.0.3…v1.0.4](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.3...v1.0.4)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Add machine-readable breakpoint listing through `--list-available-breakpoints-json`, alongside the
existing console listing.

### Commits

- [`b06d5a5`](https://github.com/cs1302uga/cs1302-tracer/commit/b06d5a51e541d637270650c3e4bb6e19c9f65ff8) can now list breakpoints using JSON
- [`13b8749`](https://github.com/cs1302uga/cs1302-tracer/commit/13b87493006d83228964f29b19f89bde29675c26) bumped patch version
- [`f18cf76`](https://github.com/cs1302uga/cs1302-tracer/commit/f18cf76e0f8973416aef84550a0383d31c75ec0a) bumped patch version

## 1.0.3 — 2025-08-16

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.3) · [Compare v1.0.2…v1.0.3](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.2...v1.0.3)

### Breaking changes

JSON: finite floating-point values now use `NUMBER-LITERAL` strings instead of ordinary JSON
numbers; special double values retain their tagged handling. Update custom numeric decoders to
support these representations.

### Non-breaking changes

Improve float/double fidelity and reorganize source reading and serialization.

### Commits

- [`01366b6`](https://github.com/cs1302uga/cs1302-tracer/commit/01366b662014c9025a89ac5b6bc5333b6df5249f) fixed float and double values; refactored other places

## 1.0.2 — 2025-08-08

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.2) · [Compare v1.0.1…v1.0.2](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.1...v1.0.2)

### Breaking changes

JSON: characters now use Python Tutor `CHAR-LITERAL` values. Consumers that treated characters as
ordinary primitives must decode this tagged representation.

### Non-breaking changes

Correct Java character rendering in Python Tutor output.

### Commits

- [`c5f940e`](https://github.com/cs1302uga/cs1302-tracer/commit/c5f940e9d1cafb6431fb22bc5ea92946c0423833) Serialize chars as CHAR-LITERAL for OPT
- [`c043f0e`](https://github.com/cs1302uga/cs1302-tracer/commit/c043f0e44cc47fa0c647db1e06bd9d609e348162) bump patch version after char literal fix

## 1.0.1 — 2025-08-07

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.1) · [Compare v1.0.0…v1.0.1](https://github.com/cs1302uga/cs1302-tracer/compare/v1.0.0...v1.0.1)

### Breaking changes

No breaking changes identified.

### Non-breaking changes

Preserve null elements in traced arrays instead of dropping them, so array positions remain
accurate.

### Commits

- [`383c2e4`](https://github.com/cs1302uga/cs1302-tracer/commit/383c2e43cac8da9773209d2907d2c50b4371e6ee) Update TraceValue.java
- [`e8c22ef`](https://github.com/cs1302uga/cs1302-tracer/commit/e8c22efc651a3f758b133ada3d9d1048b1a61c88) bumped minor version

## 1.0.0 — 2025-08-01

[Release](https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.0) · [Initial history through v1.0.0](https://github.com/cs1302uga/cs1302-tracer/commits/v1.0.0)

### Breaking changes

Initial release; there is no earlier published compatibility baseline.

### Non-breaking changes

Initial release: compile Java source, discover breakpoints, capture stack/static/heap state, and
emit Python Tutor JSON. Supports selected breakpoints, optional inline strings and main arguments,
dependency license display, and JSON-library serialization.

### Commits

- [`cdfb6f2`](https://github.com/cs1302uga/cs1302-tracer/commit/cdfb6f29aceae5002914f7db8a1ec9c43ef6491f) Initial commit for new trace generator
- [`ab503e2`](https://github.com/cs1302uga/cs1302-tracer/commit/ab503e256321892b2f908dfa4065cc4e923d29af) Refactor compilation code into its own helper class
- [`db91ef9`](https://github.com/cs1302uga/cs1302-tracer/commit/db91ef9f4df39c7b869068afe15a6fddbe5c3584) Add basic tracer
- [`f5f5d8f`](https://github.com/cs1302uga/cs1302-tracer/commit/f5f5d8f4e781c7166a2abbd77dc44c50bf618fd1) Add breakpoint option to CLI arguments
- [`88fb909`](https://github.com/cs1302uga/cs1302-tracer/commit/88fb9093b44158f7221ed6df8ab623a83d2d2b4b) Add basic compilation and running instructions to README
- [`4c26a9a`](https://github.com/cs1302uga/cs1302-tracer/commit/4c26a9a2c4511734d482602b9d9cfdb88f7974b7) Take a snapshot at main exit if no snapshots were made before then
- [`2168649`](https://github.com/cs1302uga/cs1302-tracer/commit/2168649b912169026b57477706005cdf272dba19) Add option to list available breakpoints
- [`5eb9d40`](https://github.com/cs1302uga/cs1302-tracer/commit/5eb9d40a671d720bd029e7fca35ff379124916a9) First pass at terminal composite types
- [`de1eca0`](https://github.com/cs1302uga/cs1302-tracer/commit/de1eca08825dadb8227c64cf7e5f6aa0f65c91c5) Major refactor of trace value parsing
- [`1801715`](https://github.com/cs1302uga/cs1302-tracer/commit/18017150e2c803cfbedcc7813705265b5209e52e) Initial pass at OPT trace serialization
- [`7e6599d`](https://github.com/cs1302uga/cs1302-tracer/commit/7e6599db66f756e66fb9bcc91c6a50175664f3f5) Add option for inlining Strings
- [`960742d`](https://github.com/cs1302uga/cs1302-tracer/commit/960742d6fc753629b552bc170ebbfa8023ec3033) Use a proper JSON library instead of hand-making JSON strings
- [`4ef4c11`](https://github.com/cs1302uga/cs1302-tracer/commit/4ef4c11364197b8f8dcf30ea703e503a3e1f4506) Only highlight top-level stack frames
- [`e5c4354`](https://github.com/cs1302uga/cs1302-tracer/commit/e5c4354dfd3652079ed03a4dbf4dd6d44a49774a) Flatten code-tracer to project root
- [`0c86d6a`](https://github.com/cs1302uga/cs1302-tracer/commit/0c86d6ab46665e6b82bc082f2ee1334b0610582d) Add GitHub build action
- [`b543ed4`](https://github.com/cs1302uga/cs1302-tracer/commit/b543ed41481bfeb4a71e37846d231a3678439396) Add option to display dependency licenses
- [`841ed29`](https://github.com/cs1302uga/cs1302-tracer/commit/841ed296c38147cc8fb0eeec6f25c15bf30ffb57) Add GitHub link and initial version info to pom
- [`db3b7e1`](https://github.com/cs1302uga/cs1302-tracer/commit/db3b7e1e1be4b60c5a6c9b25f4d8b73fd8d4ed1a) Add support for outputting multiple breakpoint snapshots
- [`97935e9`](https://github.com/cs1302uga/cs1302-tracer/commit/97935e960479b3ef250929f9230eeb83a3ba7f91) Fix crash when encountering a null value in an array reference
- [`30da2d2`](https://github.com/cs1302uga/cs1302-tracer/commit/30da2d2eb858b0da7d2cf33af590724e2991babe) Use an instance with a single unnamed field when rendering strings on the heap
- [`a7e3670`](https://github.com/cs1302uga/cs1302-tracer/commit/a7e3670a29a04b97f6b08c9c72bd0e47040a178a) Don't serialize main's args argument
- [`93712b8`](https://github.com/cs1302uga/cs1302-tracer/commit/93712b84f451b0b0033f80af37931e314ae514e9) Don't embed non-primitives directly into statics
- [`a620564`](https://github.com/cs1302uga/cs1302-tracer/commit/a620564bf01d8bcf8756ba43e562f083b4a91d27) Improve project documentation
- [`29c7172`](https://github.com/cs1302uga/cs1302-tracer/commit/29c71725b4fee472e3097ffa009cbce82c6e9785) Stub out the fields of Java builtin types
- [`7631a69`](https://github.com/cs1302uga/cs1302-tracer/commit/7631a695bd47df27651db2bc004f0e6d9544deb1) Check parameters for String's FQN when identifying main
- [`9185836`](https://github.com/cs1302uga/cs1302-tracer/commit/91858367095e2d43da4f34e0d289781616d9f60c) Add toggle for removing main's args parameter
