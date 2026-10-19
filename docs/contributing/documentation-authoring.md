# Author documentation

README is the project introduction and quickstart; HACKING is the contributor
entry point. Detailed content lives in `docs/` and is rendered directly by
**Docusaurus**. Keep examples and links useful when reading Markdown on GitHub too.

Bold the first prose or table mention of a named program or tool on each page,
including an adjacent version requirement. Leave later mentions plain and preserve
headings, inline commands, and code blocks. This convention does not apply to
operating systems, language names, libraries, or file formats. Apply it to current
guides and entry points; preserve historical reviews and published snapshots.

The Scripts quality CI job separately enforces Python lint, formatting, types,
and 100% production line/branch coverage, including child processes. See the
[development guide](development.md#python-script-quality) for the complete local
gate. The documentation job still checks real tracer output and builds the site;
external-tool stand-ins in the Python suite do not replace that integration check.

## Choose the right page

Keep installation and first-use guidance in the consumer guides and tutorials.
Tutorials teach one concrete outcome through a complete example and checkpoints.
How-to guides provide self-contained recipes for readers who already know their
task; specify required files, working directory, shell, command, and expected
result. References define options and result contracts, while explanations connect
concepts such as snapshots, references, and heap objects. Link between these page
types instead of making each page teach, explain, and catalogue everything.
Put contributor workflows in the development and documentation guides. Use the
[documentation index](documentation.md) to find build, archive, artifact, and
release procedures instead of copying those procedures into multiple pages.

Use the [local build and preview commands](documentation-builds.md#local-checks)
to review changes in the rendered site. Review affected **Javadoc** when descriptions
of Java usage, responsibilities, ownership, dependencies, or stability change;
the build guide includes the public and internal API generation commands.

## Check executable examples and CLI documentation

`check_docs.py` compares CLI help to the executable, runs the marked quickstart
blocks, checks Markdown file destinations, compares both JSON examples after
normalizing heap identities, and runs the documented batch request. It also extracts
`target/code-tracer-bin.zip` into a temporary directory with spaces, executes the
first-trace tutorial with its own source file, compiles and runs the complete Java
tutorial, and checks both jobs from the batch tutorial. Package the project first;
a JAR-only build is insufficient. These checks run the marked POSIX-shell commands
with real Java tools. `check_docs.py` does not execute native **PowerShell** or
**Command Prompt** alternatives. Python unit tests substitute external tools to
test checker failures and do not replace the integration run.

The installation guide's marked JDK inspection, selection, tool-verification, and
installation-checkpoint snippets have shell-specific checks. `check_docs.py` runs
the **Bash** snippets; a separate **Pester** suite runs the native PowerShell and
Command Prompt snippets in Windows CI with JDK 21 and 25. The suite is
[`WindowsDocumentation.Tests.ps1`](https://github.com/cs1302uga/cs1302-tracer/blob/3c9c8a794293cf5e5cb60fceb7060c2a3b70f0cf/scripts/tests/WindowsDocumentation.Tests.ps1).
Keep those markers and their validation in agreement when changing the recipes.
This Windows coverage applies to the JDK recovery snippets; other native Windows
tutorial and recipe commands still need separate review and validation when changed.

Keep tutorial and recipe marker names stable when editing examples. The reading
check compares marked JSON excerpts with actual locals and fields, follows the
local-to-heap reference, and checks the assignment's before/after state and
cumulative output without pinning heap IDs or snapshot indices. The loop check
executes discovery, latest-hit, accumulated, and chronological commands and checks
the documented loop values. The tracing recipes cover final and chronological
capture, guest input, multiple source files, compiled dependencies, presentation,
multithread capture that keeps the `main` frame in `threads` while the worker runs, and
a controlled budget stop with a partial envelope. Java and batch checks inspect
completion and failure outcomes. The README quickstart remains a separate
checkout-based example.

The shared-references tutorial check follows two locals into one heap object
before and after a field mutation, verifies the unchanged primitive copy, and
compares the final guest output with the marked lesson block. It locates states
by source file, line, and the main frame, with no fixed heap identities or snapshot
positions.

Refresh help using `./scripts/uv run python scripts/check_docs.py --update-cli`;
review the resulting diff.
The CLI reference keeps generated help and budget links above
`<!-- cli-reference:manual -->`. Maintain behavioral notes below that marker;
help regeneration preserves those notes, and normal checks still require an exact
match for the generated prefix. Link checks cover the entire document. Review
authored notes against executable behavior whenever arguments change.
When behavior changes, update JSON examples intentionally and review the contract.
The site build and `check_site.py` check rendered internal links, fragments, and
project-base-path handling. Prose accuracy still requires review.

The scheduled External documentation links workflow checks remote links and
uploads a report. It is not a required PR check because remote availability is
outside this repository's control. A newly configured site can return 404 until
its first deployment.

## Maintain license and dependency pages

The [project license](../reference/licenses.md) reproduces the repository's
`LICENSE` text exactly. The [dependency reference](../reference/dependencies.md)
lists resolved **Maven** project dependencies, including transitive dependencies,
with exact versions, artifact types, effective scopes, descriptions, declared
licenses, and a dependency tree. It separates dependencies shipped at runtime
from test dependencies. Build plugins and the frontend and Python toolchains are
outside this Maven project-dependency inventory. Packaged runtime notices and
the CLI's `show-licenses` command remain a separate source of bundled notices.

The reference uses each dependency's `artifactId` as its primary label, linked to
the declared project website when available, with its version below. Repeated
artifact IDs also show their `groupId`. Runtime and test sections place direct
dependencies before transitive dependencies, with subgroup counts; entries in
each subgroup are alphabetical by artifact ID, with the full resolved coordinate
breaking ties. Compact scope and relationship labels, such as `compile · Direct`,
leave all declared licenses visible.

Expand **Details** to read the exact upstream project name, complete description,
resolved coordinate, artifact type, optional classifier, and any license-correction
evidence. The full-width native HTML disclosure works without JavaScript and
keeps the generated Markdown readable on GitHub. Responsive entries, desktop
table headers that remain below the navigation bar while scrolling, and bulk
controls are site enhancements.

When changing the layout, review the desktop table and narrow-screen entries,
including a dependency with a long description and one with several licenses.
Confirm that subgroup order and counts are clear, that desktop headers stay
readable below the navigation bar, and that each disclosure works independently.
Check the site's **Expand all** and **Collapse all** controls and confirm that
printing includes all metadata and then restores the previous open/closed states.
Generated-content checks detect drift; they do not replace this review of rendered
behavior. GitHub does not use the site's stylesheet or bulk controls.

After changing dependencies, their metadata, or `LICENSE`, run these commands
from the repository root with a full **JDK**:

```sh
./scripts/uv run python scripts/dependency_docs.py update
./scripts/uv run python scripts/dependency_docs.py check
```

Both commands freshly resolve the Maven dependency graph and metadata.
`update` writes the generated license and dependency pages; review their diff
before committing. `check` compares freshly generated content with the checked-in
pages and fails on drift without rewriting those pages. It does not rely on a
previously saved dependency report. Maven resolution can require network access
and populate local caches in either mode.

Missing license names or URLs stop validation. When an upstream POM omits a
declaration or leaves one incomplete, add a reviewed correction to
`scripts/dependency-license-overrides.json`, keyed by the exact
`groupId:artifactId:version`. Each entry has this shape:

```json
{
  "groupId:artifactId:version": {
    "licenses": [
      {"name": "Verified license name", "url": "https://example.org/license"}
    ],
    "source": "https://example.org/upstream-license-evidence",
    "reason": "Explain why this exact artifact needs the correction."
  }
}
```

Replace the example with the artifact's actual coordinates, licenses, and HTTPS
source evidence. An override can fill a missing name or URL while preserving every
known name and URL. Include all upstream declarations in its `licenses` array,
including any that are already complete; an override cannot drop a declaration
or change its known fields. Stale entries for artifacts no longer resolved, and
redundant entries whose upstream declarations are now complete, fail validation.
Recheck the evidence when upgrading the artifact. Missing descriptions remain
explicitly identified in the generated inventory rather than being invented.

The complete `docs_site.py verify` workflow runs the no-write check in its
`dependency-inventory` stage before the other documentation checks. The same gate
runs through Maven's `docsite` profile in CI and release validation.

Generated content omits timestamps and uses a version-independent project root
label while preserving exact dependency versions. Project-version-only release
commits therefore do not make these pages stale. Update current pages and include
them in future release snapshots; keep a pending snapshot in agreement with
current docs. Preserve published snapshots and their own release's dependency
information instead of regenerating them from the development checkout.

## Keep release snapshots accurate

Current guides describe the development checkout. Published snapshots describe
their own releases; do not copy unreleased behavior into them. If a release
snapshot is pending, keep it and its sidebar in agreement with current docs and
run the snapshot check described in
[release preparation](releases.md#select-versions-and-prepare-documentation).
For an already archived version, follow the
[guide correction procedure](guide-archives.md#roll-out-archived-guides) so the
published artifact and reviewed source stay in agreement.
