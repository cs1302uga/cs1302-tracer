# Build and preview documentation

Use these commands to validate documentation locally, preview it while editing,
or reproduce the published version selection. See
[authoring guidance](documentation-authoring.md) for content and executable-example
checks, and the [release runbook](releases.md) for publication.

## Local checks

Use a full **JDK** and fetch release tags with `git fetch --tags`. From the repository
root, build and verify the complete site with **Maven**:

```sh
./mvnw -B -ntp -Pdocsite verify
```

The opt-in profile uses **frontend-maven-plugin 2.0.2** to install **Node.js 24.22.0 LTS**
and its bundled **npm 11.19.1** under `target/frontend/`. No global Node/npm is needed.
It runs `npm ci` against `website/package-lock.json`, packages the tracer, then
checks executable documentation and release metadata, generates **Javadoc** and the
site, and checks rendered links. Any failed step fails Maven. Ordinary Maven builds
leave the frontend toolchain alone. `clean` removes the local toolchain under
`target/`; downloaded distributions remain in Maven's cache.

CI documentation and deployment jobs use this profile with `-DskipTests`; the Java
matrix owns their Java tests. Locally, omit that flag for the complete build.
The existing Python orchestration remains responsible for release selection and
Javadoc generation. Nested Maven Javadoc invocations explicitly deactivate
`docsite` to prevent recursive frontend builds. The plugin puts its local Node/npm
on the child process PATH; `build:maven` enters the managed Python environment
through `scripts/uv`. Python 3.15 and locked tooling are provisioned by that wrapper.
The complete site workflow uses the repository's Unix shell tooling on Linux/macOS;
this profile does not establish native Windows docsite support.

For live editing, bootstrap the frontend without packaging, then use its tools:

```sh
./mvnw -B -ntp -Pdocsite generate-resources
export PATH="$PWD/target/frontend/node:$PATH"
./scripts/uv run --locked npm --prefix website start
```

An existing compatible global Node installation (22 or later) still works with the
manual npm workflow. Run npm through `./scripts/uv run --locked` so its Python
children use the managed interpreter. To serve the completed build without Node,
run `./scripts/uv run --locked python scripts/serve_docs.py --port 3100`.
Open the printed URL under `/cs1302-tracer/`.
The build wrapper owns `website/build`; custom output directories, site roots,
and configuration overrides are rejected before generation. `start` accepts
`--host`, `--port` (or `-p`), and `--no-open`; both `start` and `build` accept
`--no-minify`.

Both `start` and `build` generate Javadoc first; `./scripts/uv run npm --prefix website run api`
generates it separately. Fetch release tags with `git fetch --tags` before building.
For a direct Maven invocation, run `./mvnw -B -ntp validate javadoc:javadoc`.
The wrapper includes `validate` so the build-helper execution sets `currentYear`
before Javadoc resolves the copyright footer.
The published API reference intentionally excludes `cs1302.tracer.internal` and
its subpackages. When internal Javadoc changes, also generate a local reference
that includes private declarations, then review the affected generated pages:

```sh
./mvnw -B -ntp package -DskipTests
javadoc -quiet -private --release 21 -Xdoclint:all,-missing \
  -classpath target/code-tracer.jar \
  -sourcepath src/main/java -subpackages cs1302.tracer \
  -overview src/main/javadoc/overview.html \
  -d target/internal-apidocs
```

This local reference is for contributor review; internal declarations remain
outside the supported Java API. Both generation commands check Javadoc syntax
and links, but claims about defaults, ownership, and behavior still need to be
checked against the implementation and tests.

## Version selection and build modes

The **API Reference** navigation link follows the selected documentation version.
Javadoc keeps its own navigation, search, and styling under `api/next/` for development
and `api/<version>/` for releases, beneath the site's `/cs1302-tracer/` base path.

Release API references are resolved from their exact **Git** tags on every build,
including all previously published documentation versions. Published builds prefer
verified release Javadoc JARs; source builds can reuse validated historical output
from the generated-output cache. Missing tags, mismatched Maven versions, invalid
release artifacts, and generation errors fail the build. Deployment selects the same
published releases for guides and API references. Local builds preview all prepared
snapshots; an untagged snapshot matching the current Maven version, or its target
version after removing a trailing `-SNAPSHOT`, uses working source until release.
Such a snapshot has an unreleased banner in preview mode. Published mode still
requires a release tag whose Maven version exactly matches the stable snapshot;
a `-SNAPSHOT` build cannot substitute for a published release.
After release preparation advances the POM to the next development version, CI
can preview the still-untagged release with
`-Ddocsite.pendingCommit=<full-release-commit-SHA>`. The corresponding Python option
is `--pending-commit`, defaulting to `DOCSITE_PENDING_COMMIT`. This preview-only
input must name a stable ancestor of the checked-out revision whose version is
the newest prepared snapshot. Its committed docs, snapshot, version list, and
sidebars must agree with the checkout. Javadoc then comes from that exact commit,
and the snapshot retains its unreleased banner. Published builds reject this
override and continue to require release tags. Historical snapshots never fall
back to an arbitrary working tree.
The documentation CI job bootstraps the pinned frontend toolchain with
`-Pdocsite initialize` and adds its Node directory to `GITHUB_PATH` before
running the release-context check. The subsequent `verify` invocation runs
`npm ci` once; the early bootstrap only needs Node to inspect the sidebar.
The local editing recipe still uses `generate-resources` to install the site's
dependencies before starting the development server. The context check handles
both `release/X.Y.Z` and the next-development commit on `main` during the interval
before the tag push.
It requires the untagged stable release to be that commit's direct parent; it
does not infer an arbitrary release source from the available history.
Generated HTML lives in ignored `website/generated-api/` and
is copied into the site build, rather than committed.

Build mode is explicit: the Maven property `docsite.mode` defaults to `preview`.
Set it to `published` with `docsite.releases` pointing to an absolute path to a JSON
array of GitHub release records. These properties reach the Python process as
`DOCSITE_MODE` and `DOCSITE_RELEASES`, avoiding shell interpolation of paths.
The Python actions `start`, `build`, `api`, and `verify` default to preview when
those environment variables are absent; explicit `--mode` and `--releases` options
override their corresponding environment defaults. `verify` runs the complete
checks around a build and accepts no forwarded **Docusaurus** flags.
A leftover `published-versions.json` never changes their behavior. To reproduce a
published build, save the GitHub release records as a JSON array and run:

```sh
./mvnw -B -ntp -Pdocsite -Ddocsite.mode=published -Ddocsite.releases=/path/to/releases.json verify
```

For automatic Javadoc artifact selection, retain the full release API records,
including each asset's name, size, download URL, and SHA-256 digest. A list of
version names alone is insufficient to verify a published Javadoc bundle.

The Python documentation release module validates selection, tag provenance, and
routes once. It generates ignored `website/release-manifest.json` for Javadoc,
Docusaurus, and navigation. The completed build includes its own copy of the
manifest so validation and local serving use the decisions belonging to that build.
The legacy `docs_release.py published` command still exports a selected-version list
for inspection; that file no longer selects the build mode.

## Documentation build cache and timing

The full documentation verification workflow retains every selected documentation
version, generates development Javadoc, and runs the existing executable, release,
and rendered-link checks.
The generated-output cache applies only when Javadoc is built from validated
release source, after release-artifact selection. Current source and
untagged pending-release Javadoc are regenerated on each invocation. Guide compilation
is controlled separately by `docsite.guides`: `all` compiles every selected guide
together, while `active` compiles the bounded selection and composes verified
[archived guide applications](guide-archives.md#archived-guide-applications). Both modes retain
historical versions and routes. Active mode bounds guide compilation; archive
downloads and verification, complete rendered-link checks, and deployment uploads
still process retained content, so total deployment cost is not constant.

The ignored cache lives at `website/.cache/javadoc/`, outside Maven's `target/`,
so `mvnw clean` leaves it available. Cache mode defaults to `use`:

| Mode | Historical source-build behavior |
| --- | --- |
| `use` | Reuse a valid entry; otherwise generate and cache the result. |
| `refresh` | Regenerate historical Javadoc and replace its cache entries. |
| `off` | Generate historical Javadoc without reading or writing the cache. |

Select the mode with Maven's `docsite.javadocCache` property:

```sh
./mvnw -B -ntp -Pdocsite -Ddocsite.javadocSource=source -Ddocsite.javadocCache=refresh verify
```

These cache modes do not override a verified release artifact selected by `auto`.
Use `docsite.javadocSource=source` when the intent is to regenerate release API
pages, or combine it with `docsite.javadocCache=off` to bypass both forms of reuse.

For direct Python use, all four actions accept `--javadoc-cache use|refresh|off`.
An explicit option overrides `DOCSITE_JAVADOC_CACHE`, which otherwise supplies the
mode. Other values are argument errors and exit with status 2. Maven passes its
property through that environment variable. For example,
after frontend bootstrap:

```sh
./scripts/uv run --locked python scripts/docs_site.py api --javadoc-source source --javadoc-cache off
```

Release selection and tag/POM agreement are checked before looking in the cache.
Entries identify the immutable source commit, current wrapper and actual
Maven/JDK toolchain, generator and configuration digests, artifact-resolution
settings, platform, and UTC year used in the copyright footer. An entry includes
the complete generated file inventory and checksums, including the API index,
search page, and element list. An incompatible, incomplete, or corrupt entry
causes regeneration. Complete
entries are written atomically; a cache-write failure is reported without
discarding successfully generated documentation. A generation failure remains a
build failure and preserves the previous complete generated API output.

The cache is replaceable build output, not release evidence or durable storage.
CI can restore generated entries, but shared entries are saved only by pushes to
`main` and manually dispatched deployments from `main`. Automatic `workflow_run`
deployments restore entries without saving them. A newly published release may
therefore regenerate source-built Javadoc until a later `main` push or manual
deployment warms the shared cache. Verified release artifacts bypass that
generated-output cache. A restored entry must still pass local input and integrity
validation; PR-produced output does not become trusted publication input.
Use `source` with `refresh` when investigating source-generation discrepancies or
deliberately refreshing externally fetched documentation resources. Removing the
generated-output cache makes the next source build regenerate historical Javadoc;
it does not remove a published release artifact.

`website/build-timings.json` records the Python documentation run's applicable
stages: release selection, executable documentation, release metadata, per-version
Javadoc with output-selection/cache outcomes, Docusaurus, and rendered-link checks.
The report includes the selected `javadocSource`; `release-asset` and
`release-asset-cache` identify verified downloaded and locally retained bundles.
The separate `website/npm-ci-timing.json` records dependency installation with its
own timestamps.
These are ignored local reports; use their invocation timestamps to distinguish
current results from an earlier run. Dependency installation is not folded into a
later Python run's duration. Neither report measures the outer Maven packaging,
frontend bootstrap, cache transfer, or artifact upload. **GitHub Actions** step
durations provide those workflow-level measurements. CI / Release and Deploy
documentation upload the
available reports in a timing artifact, separate from the site, with seven-day
retention. Timing reports are diagnostic;
existing validation gates determine whether the build passes.

### Compare cold and warm builds

Use the same checkout, release selection, toolchain, site mode, and Maven options
for both runs. Set `-Ddocsite.javadocSource=source` in both to measure the generated
cache rather than release-artifact reuse. Keep Java tests either enabled for both
or explicitly skipped for both, and state that choice with the results. Run the
checks serially and preserve each
timing report before the next invocation overwrites it.

1. Run with `-Ddocsite.javadocCache=refresh` to regenerate historical Javadoc and
   populate entries. Record this as a Javadoc-cache-cold run; it does not imply
   that Maven dependencies or npm download caches were cold.
2. Repeat with `-Ddocsite.javadocCache=use`, confirming historical cache hits and
   fresh development generation in the report. Compare per-stage durations and
   repeated runs before attributing a speedup to the cache.
3. Compare release manifests, selected routes, and generated content between the
   runs. Both must pass the same executable documentation and complete rendered
   link/fragment checks. Investigate output differences before claiming equivalence.

Report whole-build and stage timings separately. Historical generation can shrink
while Docusaurus compilation, full-tree validation, and published site size still
grow with the number of retained versions. No performance threshold replaces a
correctness gate. Measure release-artifact download and verification separately
when evaluating published `auto` builds; that is a different comparison from
generated-cache reuse.

## Repository rollout

Required repository settings: Pages uses GitHub Actions as its source;
`main` can deploy to the `github-pages` environment; and **Documentation** is a
required GitHub Actions status check for `main`. These are configured in GitHub,
not by the workflow YAML. Preserve other existing protection settings when
recreating or modifying them.
The check is defined in CI / Release and runs on every PR, including code-only PRs.

The workflow files define these checks and deployment triggers. After deployment,
verify the landing page, version menu, Mermaid diagrams, and a nested page at the
real GitHub Pages URL. Repository protection, Pages permissions, and live deployment
state must be verified in GitHub; their presence cannot be inferred from tracked
configuration or a successful local build.
