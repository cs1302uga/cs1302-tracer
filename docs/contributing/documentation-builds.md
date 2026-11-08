# Build and preview documentation

Current prose lives in `docs/`. The site uses **Apache Maven Site Plugin 3.22.0**
with **Supa Maven Skin 0.2.0**. Retained release guides live in
`docs/releases/VERSION/`; the current-document inventory excludes that tree.
The common descriptor, navigation, static resources and project landing source
live in `src/site/`. Generated staging and the assembled site live under `target/`.

## Local checks

Select a full **JDK 21 or later** and fetch release tags before building.
From the repository root:

```sh
./mvnw -B -ntp -Pdocsite verify
./scripts/uv run --locked python scripts/serve_docs.py --port 3100
```

The opt-in profile packages the tracer, then invokes the managed Python wrapper
through the existing Maven Antrun Plugin. Python runs dependency-inventory,
executable-documentation, release-metadata, Javadoc, Maven rendering, and rendered
link/fragment checks serially. Any failure fails the build. Ordinary Maven
packaging does not build the site. The profile needs Unix shell tooling on
Linux/macOS or Windows through WSL; native Windows docsite support is not implied.
No Node.js, npm installation, frontend dependency tree or automatic Maven site
deployment is part of this workflow.

The wrapper stages all selected guide Markdown and resources in
`target/docsite/renderer/`, invokes only `site:site` in that isolated Maven project,
and assembles the complete `target/pages/` tree. If source inputs change during
generation, the build fails before marking the output ready for deployment. Its POM uses the current renderer
configuration even for software tags that predate the site migration. Historical
software identity and current documentation-builder identity remain separate.
Nested Javadoc Maven commands deactivate `docsite`, preventing recursive lifecycles.
The site owns its output directory; output, configuration and site-root overrides
are argument errors.

To build or preview directly after packaging:

```sh
./scripts/uv run --locked python scripts/docs_site.py build
./scripts/uv run --locked python scripts/docs_site.py start --host 127.0.0.1 --port 3100
./scripts/uv run --locked python scripts/docs_site.py api
```

`build` assembles the site; `start` builds it and serves the resulting static tree;
`api` generates only API output; `verify` performs the full documentation checks
around a build. `start` accepts `--host` and `--port` (default `127.0.0.1:3000`).
The deployment-only `check-inputs` action reads the existing
`target/pages/release-manifest.json` and compares current source bytes, builder
commit, selected release identities and fresh release asset records. It performs
no generation and fails on stale inputs; pass the same `--mode published` and
fresh `--releases` options used by the deployment workflow.
The former bundler flags `--no-minify`, `--no-open` and `-p`, and the `--guides`
all/active switch, have been removed. Use the static preview server for an existing
build. Open its printed URL under `/cs1302-tracer/`. Static serving preserves
Javadoc filenames and the deployed project-base paths.

For direct public Javadoc generation, run:

```sh
./mvnw -B -ntp validate javadoc:javadoc
```

The `validate` phase sets the copyright year and checks the wrapper toolchain.
The public API excludes `cs1302.tracer.internal` and its subpackages. For internal
Javadoc changes, also generate and review the private contributor reference:

```sh
./mvnw -B -ntp package -DskipTests
javadoc -quiet -private --release 21 -Xdoclint:all,-missing \
  -classpath target/code-tracer.jar \
  -sourcepath src/main/java -subpackages cs1302.tracer \
  -overview src/main/javadoc/overview.html \
  -d target/internal-apidocs
```

## Version selection and build modes

The project root is a landing page. `docs/` redirects to `docs/latest/`, which is
a complete copy of the selected stable guide. The `docs/` redirect covers that
entry point only; it is not a wildcard rewrite. Each stable document has one
canonical URL under `docs/VERSION/`; the latest copy declares that version URL
as canonical. Development pages canonically identify `docs/dev/`, and legacy
redirect pages declare their corresponding canonical destination. `docs/dev/` describes development;
`docs/VERSION/` retains each selected release guide. Every guide has an explicit
channel label, version-specific sidebar, links to all versions, and a matching API
link. Switching versions keeps the current page when that version has it and
otherwise opens its index. The former `/next/` and `/VERSION/` deep links redirect
to their corresponding channels and preserve heading fragments. Legacy Markdown
heading IDs coexist with Doxia anchors without duplicate IDs. The old root
`404.html` and `all-versions/` routes remain available.

Sidebar links name the existing `index.html` resources explicitly so the shipped
skin can index headings and content across guide pages. Canonical URLs remain
directory URLs. Previous/next cards follow the same frozen navigation order.
The deterministic `sitemap.xml` lists canonical current/retained guide and landing
pages; it excludes latest copies, compatibility redirects and API output. Rendered
validation checks this scope and rejects missing, extra or duplicate entries.

Javadoc stays under `api/next/` and `api/VERSION/`, including its own search,
filenames, relative assets and styling. Javadoc source/cache verification remains
independent of guide rendering. Missing release tags, mismatched POM versions,
invalid required artifacts, and generation failures remain build failures.

Build mode defaults to `preview`, showing all prepared snapshots. Pending untagged
snapshots retain an unreleased banner and never become `latest`. Published mode
selects only non-draft, non-prerelease GitHub releases, ordered numerically rather
than by publication date. It requires an explicit JSON array of release records:

```sh
./mvnw -B -ntp -Pdocsite -Ddocsite.mode=published -Ddocsite.releases=/path/to/releases.json verify
```

Keep full asset records, including names, URLs, sizes and SHA-256 digests, so
published Javadoc artifacts can be authenticated. An untagged snapshot may use
current Javadoc only when its stable target matches the current POM with or
without the `-SNAPSHOT` suffix. After signed release preparation advances the POM,
`--pending-commit FULL_SHA` or Maven `-Ddocsite.pendingCommit=FULL_SHA` previews the
exact stable ancestor. The committed current docs, prepared snapshot, version list
and navigation must agree. Published mode rejects this override.

The Python options `--mode`, `--releases`, `--pending-commit`, `--javadoc-source`
and `--javadoc-cache` override the corresponding `DOCSITE_MODE`,
`DOCSITE_RELEASES`, `DOCSITE_PENDING_COMMIT`, `DOCSITE_JAVADOC_SOURCE` and
`DOCSITE_JAVADOC_CACHE` environment defaults. Invalid options exit with status 2;
build and validation failures return a nonzero status. The legacy
`docs_release.py published` command exports `target/docsite/published-versions.json`
for inspection;
that file does not choose a build mode.

Release provenance is retained in `src/site/release-provenance.json`, pinning
software commits and exact frozen prose/navigation digests. A published release
cannot silently disappear or retarget through mutable release records. Historical
content corrections require a reviewed change to the affected snapshot and its
provenance; software tags remain unchanged. See [retained guide maintenance](guide-archives.md).

## Documentation build cache and timing

Every selected guide renders locally on every build. Guide ZIP downloads and
archive composition have been retired. Optional caching applies only to verified
Javadoc artifacts and historical source generation, under ignored
`.cache/docsite/release-javadoc/` and `.cache/docsite/javadoc/`. Maven `clean`
removes generated staging/output but leaves these replaceable caches available.

Published `auto` Javadoc selection prefers verified release artifacts and rejects
missing required companions, invalid checksums and untrusted attestations. Older
releases without the artifact capability retain exact tagged-source generation.
Use `--javadoc-source source` or Maven `-Ddocsite.javadocSource=source` to force
source generation. See [Javadoc artifact maintenance](javadoc-artifacts.md).

Historical source cache modes are `use` (verify and reuse, otherwise rebuild),
`refresh` (rebuild and replace), and `off` (neither read nor write). Select them
with `--javadoc-cache` or `-Ddocsite.javadocCache`. Development and pending output
are regenerated each time. Cache entries bind source, toolchain, configuration,
platform and year, and verify complete output inventories. Cache failures cause
regeneration; generation failures preserve previous complete API output.

`target/docsite/build-timings.json` records stage durations and status, including
per-version Javadoc selection/cache outcomes and `maven-site` rendering. It excludes
the outer Maven lifecycle, cache transport and artifact upload. Timing reports
are diagnostics; all correctness gates still apply. CI retains reports separately
from the site for seven days. Shared caches are saved only by main pushes or
manual main deployments; PR builds and automatic deployment retries only restore.

### Compare cold and warm builds

Use identical source, release selection, JDK/Maven, mode and Java-test options.
Force source Javadoc in both runs. First use `javadocCache=refresh`, preserve its
timing report, then repeat with `javadocCache=use`. Confirm historical hits and
fresh development generation, compare manifests and routes, and require both
runs to pass the same executable and complete rendered checks. Report Javadoc
stage time separately from the whole build; retained guide rendering, full-tree
validation and upload still grow with retained content. No speed claim follows
from a cache hit alone.

## Repository rollout

GitHub Pages uses Actions as its source and permits main deployments to the
`github-pages` environment. Require both **Documentation** and **Regression gates**
for main, preserving the existing matrix and full software-release validation.
PR workflows have read-only repository permissions.

The deployment workflow explicitly handles successful `CI / Release`
`workflow_run` events and manual main dispatches. It rebuilds all current-main
channels on every retry and selects fresh published releases. Immediately before
`deploy-pages`, a fresh main checkout and release query must match the built
manifest's source digest, builder commit and release identities; stale artifacts
are rejected. Maven itself does not deploy the site.

After external publication, inspect the real landing page, channel titles,
version switching, Mermaid diagrams, dependency disclosures and a nested guide
at the Pages URL. HTML/link verification is separate from human visual acceptance.
Repository settings and live publication must be verified remotely; tracked YAML
and a successful local build alone do not establish them.
