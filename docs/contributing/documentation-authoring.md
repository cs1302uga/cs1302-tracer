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
a JAR-only build is insufficient. These checks run the marked commands with real
Java tools. Python unit tests substitute those external tools to test checker
failures, and do not replace this integration run.

Keep tutorial marker names stable when editing examples. The first-trace check
asserts source identity, output, a local value, and its object-reference relationship
without pinning heap IDs. Java and batch checks inspect completion and failure
outcomes. The README quickstart remains a separate checkout-based example.

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

## Keep release snapshots accurate

Current guides describe the development checkout. Published snapshots describe
their own releases; do not copy unreleased behavior into them. If a release
snapshot is pending, keep it and its sidebar in agreement with current docs and
run the snapshot check described in
[release preparation](releases.md#select-versions-and-prepare-documentation).
For an already archived version, follow the
[guide correction procedure](guide-archives.md#roll-out-archived-guides) so the
published artifact and reviewed source stay in agreement.
