# cs1302-tracer

Compiles Java programs, traces their execution through JDI, and emits Python Tutor
or modern JSON. It supports direct CLI use and batch jobs with reusable guest JVMs.

## Preserve the contracts

- Preserve serialized output compatibility unless a format change is explicitly
  requested. Java API changes and JSON format changes are separate decisions.
- For tracing changes, account for CLI and batch callers, dedicated and persistent
  guests, single-thread and multithread capture, and both serializers. Identify
  which paths are affected and validate those paths before declaring completion.
- Keep output, input observation, retained snapshots, and type evidence scoped to
  their owning job. Exercise consecutive jobs when changing reusable guest state.
- The tracer executes guest code; it is not a sandbox. For hosted submissions or
  isolation changes, read [the runner contract](docs/RUNNER_CONTRACT.md). Tracing
  budgets do not replace whole-job isolation.

## Find the relevant context

- **Architecture or Java interfaces:** read [GLOSSARY.md](GLOSSARY.md) for shared
  terminology and [the architecture guide](docs/contributing/architecture.md) for
  subsystem responsibilities. Keep lifecycle and state rules with their owners.
- **Output or protocol changes:** read [JSON output](docs/reference/output.md),
  [batch protocol](docs/reference/batch.md), and, for limits or partial results,
  [bounded tracing](docs/BOUNDED_TRACING.md).
- **Python or shell tooling:** read [scripts/AGENTS.md](scripts/AGENTS.md), including
  when invoking scripts from the repository root. Use the local `scripts/uv` and
  `scripts/uvx` wrappers as described there.
- **Site, Javadoc, guide archives, or releases:** read [documentation maintenance](docs/contributing/documentation.md)
  before changing release selection, snapshots, routes, archive publication, or deployment mode.
- **Build, style, or coverage:** read [the development guide](docs/contributing/development.md).
  `pom.xml` and `src/main/resources/cs1302_checks_extended.xml` are authoritative
  for compiler and Checkstyle configuration.
- **Dependency, plugin, or Maven versions:** follow [dependency versions and upgrades](docs/contributing/development.md#dependency-versions-and-upgrades).
  `wrapper.maven.version` and `wrapper.maven.sha256sum` in `pom.xml` select the
  Maven distribution and its checksum; change them through
  [Maven wrapper maintenance](docs/contributing/development.md#maven-wrapper-maintenance),
  not by editing the wrapper files alone. `./mvnw versions:display-plugin-updates
  versions:display-dependency-updates` lists stable candidates only.

## Verify the change

Use a full JDK. `.java-version` selects the local JDK; Maven targets Java 21,
and CI exercises Java 21 and 25. Commands below run from the repository root.

Start with focused tests of observable behavior. For a focused Java test run, use
`./mvnw -B -ntp -Djacoco.skip=true -Dtest=TestClass test`; partial runs cannot establish
whole-project coverage. Use `./mvnw -B -ntp package` for the normal build gates.

For capture, serialization, or example-source changes, package the JAR, then run:

```sh
./scripts/uv run python -m unittest discover -s examples -p test_verify.py
./scripts/uv run python examples/verify.py
```

Example source line numbers are part of committed trace fixtures. Regenerate
fixtures only for an intended change and inspect the diff; updating expected
output is not a substitute for diagnosing a regression. Use structural assertions
for nondeterministic thread schedules.

After changing dependencies, their versions or metadata, or `LICENSE`, run
`./scripts/uv run python scripts/dependency_docs.py update`, review the generated
license and dependency pages, and confirm `dependency_docs.py check` passes.

Run guest-executing tests and documentation checks serially: shared temporary
compilation-directory checks can interfere across concurrent runs. Before using
Maven `clean`, preserve any needed audit evidence stored under `target/`.

The repository's pre-commit hooks test the staged index in a fresh temporary tree.
They run Checkstyle and enforce 100% production line and branch coverage, which is
stricter than the normal build's model/serializer coverage gate. Keep these gates
intact; a successful working-tree test does not validate different staged content.
Report the checks actually run and any unverified execution paths.

CI runs the same strict coverage profile across its Java matrix. Keep the
`Regression gates` aggregate dependent on successful Java, documentation, scripts,
and Windows job groups; failed, cancelled, or skipped groups must fail it. Verify
that the aggregate is required for `main` before reducing local commit checks.

For Python script changes, follow `scripts/AGENTS.md`: tests belong under
`scripts/tests/`, and completion requires Ruff lint/format, ty, and 100% production
line/branch coverage including subprocesses. Preserve the Scripts quality CI gate
and release dependency; keep existing Java Git hooks unchanged unless requested.

## Keep documentation useful

For every change, verify affected present-tense documentation against the current
implementation, configuration, and executable behavior. Update all affected guides,
references, diagrams, commands, and pending release snapshots in the same change;
completion requires agreement between those descriptions and the repository.
Clearly label historical reviews, migration examples, and measurements with their
revision or release scope. Preserve historical evidence and published-version
semantics; distinguish proposals and unverified deployment settings from implemented
behavior. Check prose accuracy as well as automated documentation checks.

Keep `src/main/javadoc/overview.html` and every `package-info.java` accurate for the
current project. For every change, check whether it affects their descriptions of
supported usage, package responsibilities, ownership, dependencies, or stability
contracts; update affected Javadoc in the same change. Completion requires these
descriptions to agree with the implementation. Validate changed Javadoc with
`./mvnw -B -ntp validate javadoc:javadoc` and review the generated documentation;
a successful build alone does not establish prose accuracy.

Update the existing guide when a user workflow or durable constraint changes.
Keep local implementation explanations near the code and shared terminology in
the glossary. Link to detailed guidance instead of duplicating it here. For
changes to current docs while a release snapshot is pending, follow the snapshot
agreement checks in the documentation-maintenance guide.

### Document every CLI argument change

Treat additions, removals, renames, aliases, defaults, accepted values, validation,
and argument interactions as documentation changes. Update executable help and
the CLI reference, plus every affected guide, example, and pending release
snapshot in the same change. Explain required versus optional arguments, value
syntax, defaults, mode-specific behavior, and relevant errors or exit statuses.
For incompatible changes, document the replacement and migration steps.

After packaging the updated executable, regenerate help with
`./scripts/uv run python scripts/check_docs.py --update-cli`, review the generated
diff, and run `./scripts/uv run python scripts/check_docs.py`. Completion requires
agreement between the implemented arguments, help, reference, and affected usage
examples; generated help alone does not document behavioral changes.

### Release and versioning workflow

For release preparation, publication, or recovery, follow the
[release runbook](docs/contributing/documentation.md#prepare-a-stable-release).
Use the pinned Maven Release Plugin through `scripts/release.py`: explicit stable
release and next `-SNAPSHOT` versions, `vX.Y.Z` tags, and signed version-transition
commits and annotated tags verified against trusted SSH signers. Classify changes
across the supported Java API, CLI, JSON/batch protocol, and runtime requirements;
incompatible changes require a major increment, compatible features a minor
increment, and compatible fixes a patch increment. Review the classification
against the actual release range; a preexisting POM version does not establish it.

Prepare documentation and changelog content before the plugin changes versions.
The local prepare phase uses a clean isolated checkout and the complete release
validation gate before committing and tagging. Publication is a separate explicit
operation: validate the stable and next-development commits on the dedicated
release branch, preserve their identities while fast-forwarding `main`, then push
only the verified release tag. An instruction to prepare and publish authorizes
both phases; otherwise stop at the requested phase. Preserve published tags and
inspect saved state before recovery. Report publication complete only after the
release assets and live versioned documentation are verified. Keep operational
commands, failure handling, and signing setup in the runbook.

### Maintain the release changelog

Maintain a top-level `CHANGELOG.md` with an `Unreleased` section and entries for
published releases, newest first. Each release entry must include its version,
release date, release link, comparison range, and links to individual commits.
Provide human-readable **Breaking changes** and **Non-breaking changes** summaries
that explain user or integrator impact; include migration guidance for breaking
changes. Distinguish Java API, CLI, JSON/protocol, and tooling compatibility.
State explicitly when a release has no breaking changes.

Bootstrap the changelog from the repository's actual Git and release history.
Identify published releases and their tags/commits, inspect each consecutive
release range (`PREVIOUS_RELEASE..RELEASE`), and account for every included commit
with an individual commit link. For the first release, use the available history
through its release commit. Use release metadata for publication dates; a Maven
version bump alone is not evidence of a published release. Inspect relevant diffs
and existing release notes to write the summaries rather than merely reproducing
commit subjects. Record unavailable history or uncertain classifications instead
of inventing details.

After bootstrapping, update `Unreleased` as changes land and reconcile it against
the actual commit range when preparing a release. Add final commit links once
their SHAs exist; move the verified summary into a dated release entry when it is
published. Keep existing release entries accurate without regenerating away
curated explanations. A release is fully documented only when every commit in
its range is linked and its breaking and non-breaking effects are summarized.
