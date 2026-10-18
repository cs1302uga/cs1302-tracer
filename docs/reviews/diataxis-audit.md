# Diátaxis documentation audit

> Historical review. Findings and recorded execution claims apply to the revisions below. The inventory is a baseline, not a statement that every issue still exists in a later checkout. See the remediation record for subsequent corrections.

## Scope and decisions

The consumer-documentation audit began at `08ccd72`. Its source reviews, original executions, aggregation recovery, and later checks retain their own recorded revisions. The branch was rebased from `64347dc` onto `origin/main` `dc8887f`, producing `0235c0f`. A separately approved Javadoc pass was committed as `88afe52`. No release, deployment, or hosted-isolation implementation is asserted by this review.

The scope covers consumer guides, tutorials, references, explanation, README, GLOSSARY, and historical-review findability. Existing URLs and published snapshots remain intact. Contributor prose and runtime implementation fixes are outside this pass. The selected help-description correction changes only the frame/thread descriptions in `JobOptions`; generated help must be refreshed and checked. Other generated help retains its existing wording, with qualifications in authored reference prose.

Readers are prioritized as JSON/batch integrators, instructors, then Java embedders. Incorrect contracts rank S1, blocked reader goals S2, missing topics S3, and reading friction S4. These are documentation impact priorities, not vulnerability classifications.

**Baseline: 130 active findings — 11 S1, 35 S2, 30 S3, and 54 S4.** The user selected the **46 S1/S2 findings** for the next correction pass. All selected work fits existing pages. New pages, page splits, and sidebar changes retain their separate outline checkpoint. The remaining lower-priority inventory is preserved for a later selection.

The original 181 raw findings reduce to 128 unique candidates; seven follow-ups bring the candidate inventory to 135. Three candidates merge, one contributor-only correction is deferred, and one is closed as already documented. Stable DX IDs never change after reprioritization or disposition. The earlier claim of 93 surviving findings was an unsupported bookkeeping error, now superseded.

## Evidence and verification

The [complete structured inventory](diataxis-audit/findings.json) preserves every candidate, absorbed ID, original problem and fix, historical evidence, reviewer votes, constraints, priority adjudications, and current Javadoc overlay. All original 128 findings have independent reproduce and scope reviews; the original 46 high-priority candidates also have impact reviews. Missing or compressed original reviewer reasons were preserved honestly instead of fabricated. [Priority adjudications](diataxis-audit/priority-adjudications.json) and the [rebase impact review](diataxis-audit/rebase-impact.json) explain changes separately from the original evidence.

The [follow-up reproduction review](diataxis-audit/followup-reproduction.json) and [raw text evidence archive](diataxis-audit/probe-evidence.json) preserve commands, inputs, stdout, stderr, and provenance. Follow-up probes ran sequentially on macOS aarch64 JDK 25 using the packaged `64347dc` JAR with SHA-256 `61efe29f5c5338cca6c8d748404b0e243ab0ba26cd2e3a18ee106e5179c9f370`. Java/resources/POM/assembly inputs were checked against the rebased revision before those probes; subsequent comment edits do not convert them into fresh final-revision executions. Serializer/extraction nesting variants were checked statically. The non-file CodeSource probe was synthetic, not a real Spring Boot deployment test.

Release metadata observed on 2026-10-18 listed 44 software releases, latest `v4.1.0`, and no Javadoc-named assets. Release assets can be edited, so this is dated evidence. Private Maven package availability was not established with the available credentials. The earliest GitHub CLI release supplying every verification flag was not established; a proposed 2.68.0 minimum must not be presented as verified.

The rebase preserved upstream's staged whitespace/Checkstyle hook and strict CI/release regression gates. Focused hook, aggregate-gate, and release-evidence suites passed 35 tests. The subsequent Javadoc pass passed 1,248 Java tests and normal coverage, public/private Javadoc generation, 2,529 relative HTML links across 58 pages, and a real run of its revised overview example compiled for Java 21 on JDK 25. [Javadoc impact](diataxis-audit/javadoc-impact.json) and [integrity evidence](diataxis-audit/javadoc-validation.json) distinguish its 17 partial remediations from still-open Markdown/runtime work. No fresh native Windows run, JDK 21 runtime run, whole-production strict coverage run, or complete site build is claimed by that baseline validation.

## Selected correction pass

The [selection record](diataxis-audit/selection.json) lists all 46 selected IDs. Corrections document actual behavior and verified alternatives; they do not claim to fix guest-exception recognition, default main-exit capture, permissive request parsing, or caller-dependent output projection. Each theme commit cites DX IDs. Remediation results are recorded below after serial executable-documentation and rendered-site checks.

## Ranked baseline inventory

Selected means included in this correction pass. Full problems, proposed actions, source references, votes, and limitations are in the structured inventory. Table anchors preserve every stable DX ID.

### S1: 11 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <span id="dx-043">DX-043</span> (F15-1) | integrator | Selected-breakpoint values become arrays when two files share a line number | `docs/reference/output.md` | Yes |
| <span id="dx-044">DX-044</span> (F15-2) | integrator | Multithread lifecycle steps have line 0 and no file, contradicting '1-based' line numbers | `docs/reference/output.md` | Yes |
| <span id="dx-053">DX-053</span> (F17-1) | integrator | Unrecognized inspection values such as "fields" silently run TRUSTED inspection | `docs/reference/batch.md` | Yes |
| <span id="dx-071">DX-071</span> (F22-1) | integrator | snapshot_byte_limit is described as a thread-capture stop reason but also stops single-stack jobs | `docs/BOUNDED_TRACING.md` | Yes |
| <span id="dx-091">DX-091</span> (F26-1) | integrator | Reusable-guest lifetime is documented in Javadoc but not Markdown contracts | `docs/reference/batch.md` | Yes |
| <span id="dx-093">DX-093</span> (F26-3) | integrator | Batch silently ignores unknown or misspelled request and limit keys; limit key names never listed | `docs/reference/batch.md` | Yes |
| <span id="dx-102">DX-102</span> (F28-1) | integrator | Breakpoint error classification is documented in Javadoc but conflicts with shared reference | `docs/BOUNDED_TRACING.md` | Yes |
| <span id="dx-115">DX-115</span> (F30-1) | integrator | CLI exit-status table says 1 means trace generation failed; envelope guest failures also exit 1 with a trace | `docs/reference/cli.md` | Yes |
| <span id="dx-129">DX-129</span> (FU1-1) | integrator | Runtime exception-recognition defect persists; Javadoc now documents it | `docs/reference/batch.md` | Yes |
| <span id="dx-116">DX-116</span> (F30-2) | instructor | --max-frames and --max-threads are described as general caps but apply only with --multithread | `docs/reference/cli.md` | Yes |
| <span id="dx-081">DX-081</span> (F24-1) | embedder | Migration omits that Tracer.trace returns failures/stops as status with finite default budgets | `docs/guides/java-api-migration.md` | Yes |

### S2: 35 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <span id="dx-007">DX-007</span> (F4-1) | all | Required JAVA_HOME selection gives no way to find the JDK root; checkpoint fails for PATH-only setups | `docs/guides/installation.md` | Yes |
| <span id="dx-024">DX-024</span> (F10-1) | integrator | Isolation disclaimer covers only reused workers, implying dedicated or fresh guests isolate jobs | `docs/tutorials/batch-jobs.md` | Yes |
| <span id="dx-028">DX-028</span> (F12-1) | all | Exit-3 row says to tune a budget, but some exit-3 stops are fixed checks no option or --unlimited lifts | `docs/guides/troubleshooting.md` | Yes |
| <span id="dx-029">DX-029</span> (F12-2) | all | Repeated-output row calls every snapshot a cumulative prefix; selected-breakpoint hits show final output | `docs/guides/troubleshooting.md` | Yes |
| <span id="dx-033">DX-033</span> (F13-1) | integrator | Says diagnostics always go to tracer stderr, but --result-envelope puts them in stdout JSON | `docs/explanation/trace-model.md` | Yes |
| <span id="dx-037">DX-037</span> (F14-2) | integrator | CLI source discovery from the -i path and the stdin working directory is undocumented | `docs/reference/cli.md` | Yes |
| <span id="dx-045">DX-045</span> (F15-5) | integrator | Every multithread trace includes a DestroyJavaVM thread despite 'JVM service threads are excluded' | `docs/reference/output.md` | Yes |
| <span id="dx-050">DX-050</span> (F16-1) | integrator | Envelope, batch, and Java modern payloads carry explicit null properties the page never mentions | `docs/reference/output.md` | Yes |
| <span id="dx-054">DX-054</span> (F17-3) | integrator | The page never fully says which failures lose the id or how to correlate null-id responses | `docs/reference/batch.md` | Yes |
| <span id="dx-072">DX-072</span> (F22-3) | integrator | Null vs empty trace rule is caller-dependent, and the page says 'failed' where stopped jobs also apply | `docs/BOUNDED_TRACING.md` | Yes |
| <span id="dx-074">DX-074</span> (F23-1) | integrator | Item 3 names 'tracer and compiler' but omits the guest JVM, which keeps running after Tracer is killed | `docs/RUNNER_CONTRACT.md` | Yes |
| <span id="dx-092">DX-092</span> (F26-2) | integrator | Batch Markdown still hides persistent JVM state after Javadoc clarification | `docs/reference/batch.md` | Yes |
| <span id="dx-095">DX-095</span> (F27-1) | all | Markdown and CLI help still imply modern string inlining after Javadoc correction | `docs/guides/tracing.md` | Yes |
| <span id="dx-096">DX-096</span> (F27-2) | all | No page explains how an uncaught guest exception appears; 'inspect the trace's events' cannot detect it | `docs/reference/cli.md` | Yes |
| <span id="dx-117">DX-117</span> (F30-3) | integrator | Markdown guest-mode terminology remains inconsistent after Javadoc improvements | `docs/BOUNDED_TRACING.md` | Yes |
| <span id="dx-118">DX-118</span> (F30-4) | integrator | Stop-code table omits 8 of 24 codes, misfiles snapshot_byte_limit, and gives no status per code | `docs/BOUNDED_TRACING.md` | Yes |
| <span id="dx-124">DX-124</span> (F31-2) | integrator | Single-thread stacks include JDK frames whose `file` is not in `sources`; the reference implies otherwise | `docs/reference/output.md` | Yes |
| <span id="dx-131">DX-131</span> (FU2-1) | integrator | Unreadable --stdin-file throws before runBounded, emitting no JSON envelope on exit 1 | `docs/reference/cli.md` | Yes |
| <span id="dx-133">DX-133</span> (FU2-3) | integrator | CLI trace --result-envelope omits final-output projection onto earlier selected breakpoints | `docs/reference/output.md` | Yes |
| <span id="dx-004">DX-004</span> (F2-1) | instructor | Release users are told to use matching docs, but the 4.1.0 first trace needs a checkout | `README.md` | Yes |
| <span id="dx-017">DX-017</span> (F9-1) | instructor | Default main-exit capture fails with an internal error when main ends via a JDK-thrown exception | `docs/guides/tracing.md` | Yes |
| <span id="dx-018">DX-018</span> (F9-2) | instructor | Neighbor-discovery sentence misstates when on-disk sources are compiled | `docs/guides/tracing.md` | Yes |
| <span id="dx-038">DX-038</span> (F14-3) | instructor | Default final-state capture fails with an internal error when main ends with System.exit | `docs/reference/cli.md` | Yes |
| <span id="dx-097">DX-097</span> (F27-3) | instructor | Troubleshooting omits exit status 1 and the most common first-run source failures | `docs/guides/troubleshooting.md` | Yes |
| <span id="dx-098">DX-098</span> (F27-4) | instructor | Requirements for submitted programs are undocumented (public type, one main, empty args) | `docs/guides/tracing.md` | Yes |
| <span id="dx-059">DX-059</span> (F19-1) | embedder | Java guide batch/discovery isolation guidance lags corrected Javadoc | `docs/reference/java-api.md` | Yes |
| <span id="dx-060">DX-060</span> (F19-2) | embedder | Java guide still omits debug-path matching and silent empty selections | `docs/reference/java-api.md` | Yes |
| <span id="dx-068">DX-068</span> (F21-1) | embedder | No way given to obtain the required release commit SHA; the obvious gh lookup returns the tag object | `docs/guides/offline-javadoc.md` | Yes |
| <span id="dx-069">DX-069</span> (F21-2) | embedder | Offline verification recipe omits the GitHub CLI feature/version prerequisite | `docs/guides/offline-javadoc.md` | Yes |
| <span id="dx-083">DX-083</span> (F24-3) | embedder | getGson() row offers json()/writeJson (envelope) as equal to traceJson() (3.x payload) | `docs/guides/java-api-migration.md` | Yes |
| <span id="dx-084">DX-084</span> (F24-4) | embedder | Page omits that Tracer.trace drops 3.x breakpoint-keyed map results for selected breakpoints | `docs/guides/java-api-migration.md` | Yes |
| <span id="dx-085">DX-085</span> (F24-5) | embedder | Table leaves out documented 3.x entry points: TraceSession, cancel(), PersistentGuestSession, 2-arg BatchTraceService | `docs/guides/java-api-migration.md` | Yes |
| <span id="dx-103">DX-103</span> (F28-2) | embedder | Release Maven integration has no documented resolvable repository and complete consumer recipe | `docs/tutorials/java-integration.md` | Yes |
| <span id="dx-104">DX-104</span> (F28-4) | embedder | Java Markdown batch defaults remain implicit after Javadoc clarification | `docs/reference/java-api.md` | Yes |
| <span id="dx-105">DX-105</span> (F28-5) | embedder | Markdown Java paths lack shared contract links now present in Javadoc | `docs/reference/java-api.md` | Yes |

### S3: 30 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <span id="dx-008">DX-008</span> (F4-2) | integrator | Launcher contract and CODE_TRACER_JAR_DIR live only in a Get-started how-to; no reference entry | `docs/guides/installation.md` | Later cutoff |
| <span id="dx-012">DX-012</span> (F6-1) | integrator | Tutorial says output reference defines all fields, but it lacks heap kinds, object fields, frame fields | `docs/tutorials/read-a-trace.md` | Later cutoff |
| <span id="dx-015">DX-015</span> (F8-1) | integrator | The list-breakpoints --json output fields are described only in this tutorial, and incompletely | `docs/tutorials/trace-a-loop.md` | Later cutoff |
| <span id="dx-025">DX-025</span> (F10-2) | integrator | No how-to shows how to turn existing .java files into NDJSON batch requests | `docs/tutorials/batch-jobs.md` | Later cutoff |
| <span id="dx-034">DX-034</span> (F13-3) | integrator | Explains primitive and object locals but not null references, which modern JSON shows by omitting value | `docs/explanation/trace-model.md` | Later cutoff |
| <span id="dx-039">DX-039</span> (F14-4) | all | CLI reference omits trace option interactions and the inspection and enum-hash defaults | `docs/reference/cli.md` | Later cutoff |
| <span id="dx-055">DX-055</span> (F17-4) | integrator | batch-trace process exit statuses are not documented, though other pages defer to this page | `docs/reference/batch.md` | Later cutoff |
| <span id="dx-065">DX-065</span> (F20-1) | all | Neither licensing page says which dependencies ship inside code-tracer.jar or where their notices are | `docs/reference/dependencies.md` | Later cutoff |
| <span id="dx-073">DX-073</span> (F22-5) | integrator | Envelope Markdown lacks a complete counter reference despite improved outcome Javadoc | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <span id="dx-076">DX-076</span> (F23-3) | integrator | 'Minimal environment' omits Tracer's JDK and JAVA_HOME/PATH preconditions; a cleared env fails every job | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-077">DX-077</span> (F23-4) | integrator | 'Necessary intra-job JDI transport' is never specified (loopback TCP on an ephemeral port) | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-078">DX-078</span> (F23-5) | integrator | Contract omits that Tracer prepares sources in java.io.tmpdir, which survives a kill | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-119">DX-119</span> (F30-6) | integrator | Multi-file source format is a shared contract with six names and no reference definition | `docs/reference/batch.md` | Later cutoff |
| <span id="dx-120">DX-120</span> (F30-7) | all | GLOSSARY.md defines only internal ownership terms and is not published or linked | `GLOSSARY.md` | Later cutoff |
| <span id="dx-125">DX-125</span> (F31-3) | all | Trust model is stated only as rules; no page explains what an unsandboxed guest can access | `docs/explanation/trace-model.md` | Later cutoff |
| <span id="dx-126">DX-126</span> (F31-4) | all | TRUSTED vs FIELDS trade-off is buried in the limits page and never explained | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <span id="dx-127">DX-127</span> (F31-6) | integrator | No page explains why two JSON formats exist or how to choose between pytutor and modern | `docs/reference/output.md` | Later cutoff |
| <span id="dx-132">DX-132</span> (FU2-2) | integrator | Envelope phase on normal completion and source_file_limit differs between CLI and batch/Java | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <span id="dx-019">DX-019</span> (F9-4) | instructor | Budgets section never shows how to raise a limit or use --unlimited for a complete trace | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-030">DX-030</span> (F12-4) | instructor | No entry for `trace` silently waiting for source on stdin when --input is omitted | `docs/guides/troubleshooting.md` | Later cutoff |
| <span id="dx-099">DX-099</span> (F27-6) | instructor | No guidance on getting from a trace file to a visualization; Python Tutor output is never walked through | `docs/index.md` | Later cutoff |
| <span id="dx-100">DX-100</span> (F27-8) | instructor | No how-to for tracing a multithreaded program; single-thread capture silently swaps stacks | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-128">DX-128</span> (F31-7) | instructor | Trace model promises input observation but never explains it; meaning of stdinConsumed varies by reader | `docs/explanation/trace-model.md` | Later cutoff |
| <span id="dx-057">DX-057</span> (F18-2) | embedder | Java guide omits breakpoint syntax now documented in request Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-061">DX-061</span> (F19-3) | embedder | Markdown discovery omits the entry-point prerequisite now in Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-079">DX-079</span> (F23-6) | embedder | Job interface covers the CLI only; Java API callers sent here are not told their own JVM must be per-job | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-106">DX-106</span> (F28-6) | embedder | Markdown batch failures lag the corrected processStream/close Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-107">DX-107</span> (F28-7) | embedder | No Java how-to guides; the BatchTracer and common-task recipes are missing or buried in reference | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-108">DX-108</span> (F28-8) | embedder | Embedder lifecycle Javadoc improved; Markdown explanation and concurrency contract remain missing | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-134">DX-134</span> (FU3-1) | embedder | Java guide omits runtime prerequisites now documented in Javadoc | `docs/reference/java-api.md` | Later cutoff |

### S4: 54 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <span id="dx-002">DX-002</span> (F1-2) | integrator | JSON-consumer row starts single-trace integrators in the NDJSON batch tutorial | `docs/index.md` | Later cutoff |
| <span id="dx-005">DX-005</span> (F2-2) | all | Documentation list omits the shared-references tutorial, Java API reference, and migration guides | `README.md` | Later cutoff |
| <span id="dx-006">DX-006</span> (F2-3) | integrator | Class-path paragraph names CLI and batch options but links only the Java API and a how-to | `README.md` | Later cutoff |
| <span id="dx-011">DX-011</span> (F5-1) | all | Text-editor checkpoint prepares learners for the \n escape only, not = or Windows \r\n | `docs/tutorials/first-trace.md` | Later cutoff |
| <span id="dx-026">DX-026</span> (F10-4) | all | Separate unchecked PowerShell pipeline where the --input option works in every shell | `docs/tutorials/batch-jobs.md` | Later cutoff |
| <span id="dx-031">DX-031</span> (F12-5) | all | Running-JDK mismatch row links back to the checkpoint that sent the reader here | `docs/guides/troubleshooting.md` | Later cutoff |
| <span id="dx-032">DX-032</span> (F12-6) | all | "Report a reproducible problem" lists what to include but not where to report it | `docs/guides/troubleshooting.md` | Later cutoff |
| <span id="dx-035">DX-035</span> (F13-4) | all | State model covers callStack and heap but not statics or multithread threads | `docs/explanation/trace-model.md` | Later cutoff |
| <span id="dx-040">DX-040</span> (F14-6) | all | Binary-dependency semantics are restated in four pages instead of linked | `docs/reference/cli.md` | Later cutoff |
| <span id="dx-041">DX-041</span> (F14-7) | integrator | The batch-trace section has no link to the batch protocol | `docs/reference/cli.md` | Later cutoff |
| <span id="dx-042">DX-042</span> (F14-8) | all | Version-output migration note refers to an unnamed 'previous' release | `docs/reference/cli.md` | Later cutoff |
| <span id="dx-046">DX-046</span> (F15-7) | all | Example JSON sits under the multithread heading, far from 'Complete chronological examples' | `docs/reference/output.md` | Later cutoff |
| <span id="dx-047">DX-047</span> (F15-8) | all | 'Examples 34–41' and the primitive-bits example are unlinked repository paths | `docs/reference/output.md` | Later cutoff |
| <span id="dx-048">DX-048</span> (F15-9) | integrator | Source-identity and guest wording uses non-glossary terms and internal lifecycle details | `docs/reference/output.md` | Later cutoff |
| <span id="dx-049">DX-049</span> (F15-10) | integrator | Unversioned 3.2.2 migration note embedded in the generic type-label reference | `docs/reference/output.md` | Later cutoff |
| <span id="dx-051">DX-051</span> (F16-3) | integrator | 'Selected breakpoints and envelopes' restates Result contracts vaguely and receives the inbound link | `docs/reference/output.md` | Later cutoff |
| <span id="dx-052">DX-052</span> (F16-4) | integrator | Limit names `traceBytes`/`snapshotBytes` are called 'counters', but the envelope counter is `retainedBytes` | `docs/reference/output.md` | Later cutoff |
| <span id="dx-056">DX-056</span> (F17-7) | integrator | Missing or imprecise links to the bundle syntax, the envelope section, and the Java BatchTracer | `docs/reference/batch.md` | Later cutoff |
| <span id="dx-062">DX-062</span> (F19-4) | integrator | Glob grammar shared by CLI, batch, and Java is defined only inside the SourceBundle section | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-066">DX-066</span> (F20-2) | integrator | Says retained release snapshots preserve their own inventory; none of the seven retained snapshots has one | `docs/reference/dependencies.md` | Later cutoff |
| <span id="dx-075">DX-075</span> (F23-2) | integrator | Finite limits are only 'preferred', but the recommended --result-envelope makes every omitted limit unlimited | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-080">DX-080</span> (F23-7) | integrator | Result handling leaves 'normally returned' undefined and doesn't link exit statuses, envelope, or flags | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <span id="dx-109">DX-109</span> (F29-3) | integrator | No index row or prominent sidebar entry for hosting untrusted submissions | `docs/index.md` | Later cutoff |
| <span id="dx-110">DX-110</span> (F29-4) | integrator | 'Reference and integration' mixes references, how-tos, and licensing; envelope page sits apart | `website/sidebars.js` | Later cutoff |
| <span id="dx-111">DX-111</span> (F29-5) | integrator | Batch and Java tutorials are listed among how-tos with task-style titles | `website/sidebars.js` | Later cutoff |
| <span id="dx-112">DX-112</span> (F29-6) | all | CLI reference opens with a maintainer regeneration instruction, not a page summary | `docs/reference/cli.md` | Later cutoff |
| <span id="dx-113">DX-113</span> (F29-7) | all | Seven historical review pages are built, in the sitemap, and reachable from no navigation | `website/sidebars.js` | Later cutoff |
| <span id="dx-121">DX-121</span> (F30-8) | integrator | Output reference calls Java and batch envelopes separate contracts; they share one v1 schema | `docs/reference/output.md` | Later cutoff |
| <span id="dx-122">DX-122</span> (F30-9) | integrator | Markdown limit duplication remains after Java default clarification | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <span id="dx-123">DX-123</span> (F30-11) | all | The tested-JDK statement is repeated on four pages and will drift when the CI matrix changes | `docs/guides/installation.md` | Later cutoff |
| <span id="dx-009">DX-009</span> (F4-4) | instructor | Binary-distribution path sends readers to the first trace and skips the installation checkpoint | `docs/guides/installation.md` | Later cutoff |
| <span id="dx-013">DX-013</span> (F6-3) | instructor | Closing H2s look like lesson steps but only link out; how-to and entry-point tutorials missing | `docs/tutorials/read-a-trace.md` | Later cutoff |
| <span id="dx-014">DX-014</span> (F7-1) | instructor | Checkpoints name source lines only by number, but the rendered source block shows no line numbers | `docs/tutorials/trace-shared-references.md` | Later cutoff |
| <span id="dx-016">DX-016</span> (F8-2) | instructor | The failure and exit-status checkpoint comes after all four commands, not after the first | `docs/tutorials/trace-a-loop.md` | Later cutoff |
| <span id="dx-020">DX-020</span> (F9-6) | instructor | Copied selection table omits the main-exit step that restricted chronological capture adds | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-021">DX-021</span> (F9-7) | instructor | Breakpoint discovery has no command and no warning that bad lines silently select nothing | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-022">DX-022</span> (F9-8) | instructor | How-to embeds CLI-reference catalogues of class-path and presentation rules | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-023">DX-023</span> (F9-9) | instructor | Tracing recipes never link to the troubleshooting guide | `docs/guides/tracing.md` | Later cutoff |
| <span id="dx-036">DX-036</span> (F13-5) | instructor | Uses only modern field names and never links the Python Tutor format, the CLI default | `docs/explanation/trace-model.md` | Later cutoff |
| <span id="dx-067">DX-067</span> (F20-3) | instructor | The "Dependency reference" title collides with the guest "binary dependencies" contract in the same sidebar group | `docs/reference/dependencies.md` | Later cutoff |
| <span id="dx-101">DX-101</span> (F27-9) | instructor | The 42-program teaching example catalog is unreachable from the docs and the release download | `docs/index.md` | Later cutoff |
| <span id="dx-003">DX-003</span> (F1-3) | embedder | 'API reference guide' labels java-api.md, while 'API Reference' elsewhere means the Javadoc | `docs/index.md` | Later cutoff |
| <span id="dx-010">DX-010</span> (F4-5) | embedder | Offline API documentation subsection duplicates offline-javadoc.md and adds a navigation hop | `docs/guides/installation.md` | Later cutoff |
| <span id="dx-027">DX-027</span> (F11-4) | embedder | Says API Guardian is 'needed' to compile; javac succeeds without it, with warnings | `docs/tutorials/java-integration.md` | Later cutoff |
| <span id="dx-058">DX-058</span> (F18-5) | embedder | No direct route to member-level Javadoc; page points to the version menu instead | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-063">DX-063</span> (F19-5) | embedder | Element budget described as cumulative without its per-snapshot scope | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-064">DX-064</span> (F19-6) | embedder | Builder and batch sections mix change history and internal-API pointers into the reference | `docs/reference/java-api.md` | Later cutoff |
| <span id="dx-070">DX-070</span> (F21-3) | embedder | Release-availability text is imprecise: no release ships the bundle, and the fallback only covers 3.2.1+ | `docs/guides/offline-javadoc.md` | Later cutoff |
| <span id="dx-082">DX-082</span> (F24-2) | embedder | SourcePreparation is listed as a 3.x integration point but no 3.x release contained it | `docs/guides/java-api-migration.md` | Later cutoff |
| <span id="dx-086">DX-086</span> (F24-6) | embedder | Migration page links to no API reference, output contract, or Java tutorial | `docs/guides/java-api-migration.md` | Later cutoff |
| <span id="dx-087">DX-087</span> (F24-7) | embedder | Contributor JPMS and build-check rationale takes space the migrator needs for behavior changes | `docs/guides/java-api-migration.md` | Later cutoff |
| <span id="dx-088">DX-088</span> (F25-1) | embedder | Contributor-only internals page sits in user guides/, appears in no sidebar, and links back to nothing | `docs/guides/java-module-migration.md` | Later cutoff |
| <span id="dx-090">DX-090</span> (F25-3) | embedder | Link labeled 'Java integration and 4.0 migration' points to a reference whose migration content moved | `docs/guides/java-module-migration.md` | Later cutoff |
| <span id="dx-114">DX-114</span> (F29-12) | embedder | Offline Javadoc link targets an installation summary instead of the dedicated guide | `docs/reference/java-api.md` | Later cutoff |

## Merged, deferred, and closed baseline candidates

These five candidates are retained for traceability and are not extra active items.

| ID | Disposition | Reason |
| --- | --- | --- |
| <span id="dx-001">DX-001</span> (F1-1) | merged | The existing trust/isolation link covers the safety contract; the actionable routing/clarity improvement duplicates F29-3. |
| <span id="dx-089">DX-089</span> (F25-2) | deferred-contributor-scope | Technically valid internal-policy wording, but this page declares itself contributor-only. Preserve the fact for a contributor pass; user-facing navigation remains F25-1. |
| <span id="dx-094">DX-094</span> (F26-5) | closed-already-documented | The behavior reproduces, but batch.md:52-54 already states the TRUSTED neighbor-discovery rule. It is not an additional documentation defect. |
| <span id="dx-130">DX-130</span> (FU1-2) | merged | Modern single-stack step_line exception representation is the same output-contract correction already covered by F27-2. |
| <span id="dx-135">DX-135</span> (FU3-2) | merged | Add the qualified nesting phase/counter detail to the existing complete stop-code table task; serializer/extraction variants were checked statically only. |

## Javadoc correction

JD-001 corrected `App.execute`'s false promise that it would not terminate the calling JVM. Error/controlled-stop handlers can call `System.exit`; the supported comments now direct embedders to `Tracer` or `BatchTracer`. This is a corrected documentation claim, separate from the original 135 candidates. Runtime behavior did not change.

## Remediation record

The baseline report is committed before the selected consumer corrections. The final record will identify actual changes, evidence, remaining runtime defects, and any unverified paths. Unselected findings and historical evidence remain preserved.
