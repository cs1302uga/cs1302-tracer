# Scripts quality audit — October 2026

> Historical audit of the scripts cleanup beginning at `5ed0d82`, including
> `c8d3c21` and the follow-up corrections recorded below. Measurements and source
> locations describe the October 16, 2026 working snapshot, not later revisions.

## Scope and outcome

The balanced review covered the eight production Python scripts, Python tests,
`uvw.sh` and its uv/uvx entry points, Python configuration and lockfile, affected
CI workflows, and current tooling documentation. Release selection, subprocess
arguments, build ownership, temporary files, HTTP handling, shutdown, and coverage
collection were traced through their callers. No actionable findings remained
following the two corrections below and the final scan. Java tracing behavior,
serialized output formats, and committed trace fixtures were unchanged.

The initial checks reported 171 Ruff findings, 11 files needing formatting, and
8 ty diagnostics. Tests were moved under `scripts/tests/`. Annotations, explicit
release metadata types, readable malformed-input diagnostics, and formatting
resolved these findings without weakening lint or type rules. Current guides,
agent instructions, and the pending 4.0.0 documentation snapshot were updated.

## Confirmed review findings

### SQ-BR-001 — Documentation CI must select the declared Python runtime

Remediation: `5f317eb` (separate commit).

The project requires managed Python 3.15, but documentation CI still invoked
ambient `python3`, directly and through npm. Running `docs_site.py --help` under
Python 3.12 reproduced a `NameError` for the deferred `ReleasePlan` annotation.
Documentation build, deploy, and link workflows now run through
`./scripts/uv run --locked`; npm child processes inherit that managed interpreter.
A real wrapper regression supplies an incompatible ambient interpreter and an
external npm stand-in: the bare command fails and the wrapped command succeeds.
The complete local documentation build also succeeded through this entry point.
Hosted GitHub Actions execution was not performed by this audit.

### SQ-BR-002 — Encoded paths must stay inside the rendered build

Remediation: `1d7408f` (separate commit).

A link to `/project/%2e%2e/outside.txt` passed validation when a neighboring file
existed outside `website/build`. The CLI regression failed before the correction.
The checker now resolves decoded destinations and rejects paths outside its build
root before testing file existence. The regression passes, and validation of the
actual built site still passes for 1,158 HTML pages. Normal valid-link diagnostics
and output formats remain unchanged; the previously missed invalid link now fails.

## CodeQL configuration and extraction

Two fresh Python databases were created, with the final database covering all
20 Python source files under `scripts/`, including tests. Archived source bytes
were compared with the final working files. The final extraction reported 1,419
baseline lines, 20 project files, and zero extractor errors. Local virtualenv,
wrapper caches, and bytecode caches were excluded from extraction.

CodeQL 2.27.2 ran 233 queries: official Python 1.8.12 security-and-quality plus
experimental suites and installed GitHub Security Lab Python queries 0.8.2,
with its Python extension models 0.8.2. Default remote sources and additional
local sources were enabled. No demonstrated model gap required a project model.
There was no available Trail of Bits Python pack in the installed catalog.
Bash was reviewed manually and checked with ShellCheck; CodeQL did not scan Bash
or rerun Java/JavaScript analysis for this scripts-scoped audit.

The final SARIF contains 96 records: 32 review alerts and 64 source/sink/path
diagnostics. No result was hidden with a suppression.

### SQ-CQ-001 — Intentional single Python command argument

Resolve `py/implicit-string-concatenation-in-list` at
`scripts/tests/test_input_diagnostics.py:39` as a false positive. Adjacent string
literals intentionally compose one `python -c` argument importing all eight
production modules. Inserting the suggested comma would turn the second import
statement into an unused process argument and weaken the test. The regression
asserts successful import, no stdout, and no files created in a temporary working
directory. Keep the intentional concatenation; no suppression is necessary.

### Revalidated DA-CQ-002 and DA-CQ-003

The existing [documentation audit](documentation-audit.md) dispositions still
apply. The one insufficient-logging alert is the deliberate quiet Ctrl-C exit in
`serve_docs.py`; context managers close the server and remove the temporary mount.
The HTTP integration test exercises a real interruption and clean exit.

All 30 command-line audit locations were inspected. These queries enumerate
command sinks without establishing an untrusted input-to-command exploit:
production Javadoc generation uses fixed Maven/Git/tar programs and argument
arrays; release selection validates versions and resolves commit IDs; site builds
validate forwarded options; rehearsal commands operate in disposable projects.
The documentation checker intentionally executes trusted repository quickstart
shell text. Test helpers use explicit Python/Git commands and controlled external
tool stand-ins. The two additional managed-runtime test sinks exercise bare and
wrapped external commands, not remote inputs. These are expected operations
within the existing trusted repository tooling boundary. No new injection finding
was established, and no previous resolution required reopening.

## Validation and remaining limits

- Ruff lint, Ruff format check, and ty pass.
- All 45 Python behavioral tests pass. Each of the eight production scripts has
  100% line and branch coverage: 477 statements and 134 branches, none missed.
  Child processes and identical copies in temporary projects are combined;
  production exclusions were not introduced. Tests use the agreed CLI/callable
  seams, real files/Git/local HTTP, and external-boundary substitutes.
- Scripts quality CI installs locked tools and enforces the same checks, with
  release creation depending on that job. Existing Java Git hooks are unchanged.
- The full local site build generated API references for seven documentation
  versions, and its rendered links/fragments passed validation after the fix.
- ShellCheck passes for `scripts/uvw.sh`. Executable documentation passes CLI
  help, quickstart, JSON and batch examples; release snapshot agreement passes.
- Commit hooks validate the staged tree with Checkstyle, 793 Java tests, and
  the strict 100% production line/branch coverage gate.

The final review rechecked the fixes and their interactions with runtime selection,
subprocess coverage, URL decoding, release routes, and existing test contracts.
Passing static analysis and coverage do not certify arbitrary guest code isolation.
The audit did not publish a release or deploy the documentation site.

Local evidence is under ignored `static_analysis_codeql_4/`: baseline diagnostics,
red/green regressions, full coverage report, build/check logs, commit-hook logs,
and both fresh databases with unfiltered SARIF. These local artifacts are not
required by consumers or committed as generated source.
