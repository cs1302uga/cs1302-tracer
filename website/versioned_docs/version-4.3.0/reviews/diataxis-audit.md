# Diátaxis documentation audit

> Historical review. Findings and recorded execution claims apply to the revisions below. The inventory is a baseline, not a statement that every issue still exists in a later checkout. See the remediation record for subsequent corrections.

## Scope and decisions

The consumer-documentation audit began at `08ccd72`. Its source reviews, original executions, aggregation recovery, and later checks retain their own recorded revisions. The branch was rebased from `64347dc` onto `origin/main` `dc8887f`, producing `0235c0f`. A separately approved Javadoc pass was committed as `88afe52`. No release, deployment, or hosted-isolation implementation is asserted by this review.

The scope covers consumer guides, tutorials, references, explanation, README, GLOSSARY, and historical-review findability. Existing URLs and published snapshots remain intact. Contributor prose and runtime implementation fixes are outside this pass. The first correction pass's approved Java edits covered only the frame/thread help descriptions in `JobOptions`, followed by help regeneration and checks. The user separately approved the remaining Python Tutor-only qualification in executable `--inline-strings` help and ten further existing-page documentation findings; their results and validation status appear in the follow-up record below.

Readers are prioritized as JSON/batch integrators, instructors, then Java embedders. Incorrect contracts rank S1, blocked reader goals S2, missing topics S3, and reading friction S4. These are documentation impact priorities, not vulnerability classifications.

**Baseline: 130 active findings — 11 S1, 35 S2, 30 S3, and 54 S4.** The user selected the **46 S1/S2 findings** for the next correction pass. All selected work fits existing pages. New pages, page splits, and sidebar changes retain their separate outline checkpoint. The remaining lower-priority inventory is preserved for a later selection.

The original 181 raw findings reduce to 128 unique candidates; seven follow-ups bring the candidate inventory to 135. Three candidates merge, one contributor-only correction is deferred, and one is closed as already documented. Stable DX IDs never change after reprioritization or disposition. The earlier claim of 93 surviving findings was an unsupported bookkeeping error, now superseded.

## Evidence and verification

The [complete structured inventory](diataxis-audit/findings.json) preserves every candidate, absorbed ID, original problem and fix, historical evidence, reviewer votes, constraints, priority adjudications, and current Javadoc overlay. All original 128 findings have independent reproduce and scope reviews; the original 46 high-priority candidates also have impact reviews. Missing or compressed original reviewer reasons were preserved honestly instead of fabricated. [Priority adjudications](diataxis-audit/priority-adjudications.json) and the [rebase impact review](diataxis-audit/rebase-impact.json) explain changes separately from the original evidence.

The [follow-up reproduction review](diataxis-audit/followup-reproduction.json) and [raw text evidence archive](diataxis-audit/probe-evidence.json) preserve commands, inputs, stdout, stderr, and provenance. Follow-up probes ran sequentially on macOS aarch64 JDK 25 using the packaged `64347dc` JAR with SHA-256 `61efe29f5c5338cca6c8d748404b0e243ab0ba26cd2e3a18ee106e5179c9f370`. Java/resources/POM/assembly inputs were checked against the rebased revision before those probes; subsequent comment edits do not convert them into fresh final-revision executions. Serializer/extraction nesting variants were checked statically. The non-file CodeSource probe was synthetic, not a real Spring Boot deployment test.

Release metadata observed on 2026-10-18 listed 44 software releases, latest `v4.1.0`, and no Javadoc-named assets. Release assets can be edited, so this is dated evidence. Private Maven package availability was not established with the available credentials. The earliest GitHub CLI release supplying every verification flag was not established; a proposed 2.68.0 minimum must not be presented as verified.

The rebase preserved upstream's staged whitespace/Checkstyle hook and strict CI/release regression gates. Focused hook, aggregate-gate, and release-evidence suites passed 35 tests. The subsequent Javadoc pass passed 1,248 Java tests and normal coverage, public/private Javadoc generation, 2,529 relative HTML links across 58 pages, and a real run of its revised overview example compiled for Java 21 on JDK 25. [Javadoc impact](diataxis-audit/javadoc-impact.json) and [integrity evidence](diataxis-audit/javadoc-validation.json) distinguish its 17 partial remediations from still-open Markdown/runtime work. No fresh native Windows run, JDK 21 runtime run, whole-production strict coverage run, or complete site build is claimed by that baseline validation.

## Selected correction pass

The [selection record](diataxis-audit/selection.json) lists all 46 selected IDs. Corrections document actual behavior and verified alternatives; they do not claim to fix guest-exception recognition, default main-exit capture, permissive request parsing, or caller-dependent output projection. Each theme commit cites DX IDs. The completed correction pass and its remaining work are recorded below.

## Ranked baseline inventory

Selected means included in this correction pass. Full problems, proposed actions, source references, votes, and limitations are in the structured inventory. Table anchors preserve every stable DX ID.

### S1: 11 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <a id="dx-043">DX-043</a> (F15-1) | integrator | Selected-breakpoint values become arrays when two files share a line number | `docs/reference/output.md` | Yes |
| <a id="dx-044">DX-044</a> (F15-2) | integrator | Multithread lifecycle steps have line 0 and no file, contradicting '1-based' line numbers | `docs/reference/output.md` | Yes |
| <a id="dx-053">DX-053</a> (F17-1) | integrator | Unrecognized inspection values such as "fields" silently run TRUSTED inspection | `docs/reference/batch.md` | Yes |
| <a id="dx-071">DX-071</a> (F22-1) | integrator | snapshot_byte_limit is described as a thread-capture stop reason but also stops single-stack jobs | `docs/BOUNDED_TRACING.md` | Yes |
| <a id="dx-091">DX-091</a> (F26-1) | integrator | Reusable-guest lifetime is documented in Javadoc but not Markdown contracts | `docs/reference/batch.md` | Yes |
| <a id="dx-093">DX-093</a> (F26-3) | integrator | Batch silently ignores unknown or misspelled request and limit keys; limit key names never listed | `docs/reference/batch.md` | Yes |
| <a id="dx-102">DX-102</a> (F28-1) | integrator | Breakpoint error classification is documented in Javadoc but conflicts with shared reference | `docs/BOUNDED_TRACING.md` | Yes |
| <a id="dx-115">DX-115</a> (F30-1) | integrator | CLI exit-status table says 1 means trace generation failed; envelope guest failures also exit 1 with a trace | `docs/reference/cli.md` | Yes |
| <a id="dx-129">DX-129</a> (FU1-1) | integrator | Runtime exception-recognition defect persists; Javadoc now documents it | `docs/reference/batch.md` | Yes |
| <a id="dx-116">DX-116</a> (F30-2) | instructor | --max-frames and --max-threads are described as general caps but apply only with --multithread | `docs/reference/cli.md` | Yes |
| <a id="dx-081">DX-081</a> (F24-1) | embedder | Migration omits that Tracer.trace returns failures/stops as status with finite default budgets | `docs/guides/java-api-migration.md` | Yes |

### S2: 35 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <a id="dx-007">DX-007</a> (F4-1) | all | Required JAVA_HOME selection gives no way to find the JDK root; checkpoint fails for PATH-only setups | `docs/guides/installation.md` | Yes |
| <a id="dx-024">DX-024</a> (F10-1) | integrator | Isolation disclaimer covers only reused workers, implying dedicated or fresh guests isolate jobs | `docs/tutorials/batch-jobs.md` | Yes |
| <a id="dx-028">DX-028</a> (F12-1) | all | Exit-3 row says to tune a budget, but some exit-3 stops are fixed checks no option or --unlimited lifts | `docs/guides/troubleshooting.md` | Yes |
| <a id="dx-029">DX-029</a> (F12-2) | all | Repeated-output row calls every snapshot a cumulative prefix; selected-breakpoint hits show final output | `docs/guides/troubleshooting.md` | Yes |
| <a id="dx-033">DX-033</a> (F13-1) | integrator | Says diagnostics always go to tracer stderr, but --result-envelope puts them in stdout JSON | `docs/explanation/trace-model.md` | Yes |
| <a id="dx-037">DX-037</a> (F14-2) | integrator | CLI source discovery from the -i path and the stdin working directory is undocumented | `docs/reference/cli.md` | Yes |
| <a id="dx-045">DX-045</a> (F15-5) | integrator | Every multithread trace includes a DestroyJavaVM thread despite 'JVM service threads are excluded' | `docs/reference/output.md` | Yes |
| <a id="dx-050">DX-050</a> (F16-1) | integrator | Envelope, batch, and Java modern payloads carry explicit null properties the page never mentions | `docs/reference/output.md` | Yes |
| <a id="dx-054">DX-054</a> (F17-3) | integrator | The page never fully says which failures lose the id or how to correlate null-id responses | `docs/reference/batch.md` | Yes |
| <a id="dx-072">DX-072</a> (F22-3) | integrator | Null vs empty trace rule is caller-dependent, and the page says 'failed' where stopped jobs also apply | `docs/BOUNDED_TRACING.md` | Yes |
| <a id="dx-074">DX-074</a> (F23-1) | integrator | Item 3 names 'tracer and compiler' but omits the guest JVM, which keeps running after Tracer is killed | `docs/RUNNER_CONTRACT.md` | Yes |
| <a id="dx-092">DX-092</a> (F26-2) | integrator | Batch Markdown still hides persistent JVM state after Javadoc clarification | `docs/reference/batch.md` | Yes |
| <a id="dx-095">DX-095</a> (F27-1) | all | Markdown and CLI help still imply modern string inlining after Javadoc correction | `docs/guides/tracing.md` | Yes |
| <a id="dx-096">DX-096</a> (F27-2) | all | No page explains how an uncaught guest exception appears; 'inspect the trace's events' cannot detect it | `docs/reference/cli.md` | Yes |
| <a id="dx-117">DX-117</a> (F30-3) | integrator | Markdown guest-mode terminology remains inconsistent after Javadoc improvements | `docs/BOUNDED_TRACING.md` | Yes |
| <a id="dx-118">DX-118</a> (F30-4) | integrator | Stop-code table omits 8 of 24 codes, misfiles snapshot_byte_limit, and gives no status per code | `docs/BOUNDED_TRACING.md` | Yes |
| <a id="dx-124">DX-124</a> (F31-2) | integrator | Single-thread stacks include JDK frames whose `file` is not in `sources`; the reference implies otherwise | `docs/reference/output.md` | Yes |
| <a id="dx-131">DX-131</a> (FU2-1) | integrator | Unreadable --stdin-file throws before runBounded, emitting no JSON envelope on exit 1 | `docs/reference/cli.md` | Yes |
| <a id="dx-133">DX-133</a> (FU2-3) | integrator | CLI trace --result-envelope omits final-output projection onto earlier selected breakpoints | `docs/reference/output.md` | Yes |
| <a id="dx-004">DX-004</a> (F2-1) | instructor | Release users are told to use matching docs, but the 4.1.0 first trace needs a checkout | `README.md` | Yes |
| <a id="dx-017">DX-017</a> (F9-1) | instructor | Default main-exit capture fails with an internal error when main ends via a JDK-thrown exception | `docs/guides/tracing.md` | Yes |
| <a id="dx-018">DX-018</a> (F9-2) | instructor | Neighbor-discovery sentence misstates when on-disk sources are compiled | `docs/guides/tracing.md` | Yes |
| <a id="dx-038">DX-038</a> (F14-3) | instructor | Default final-state capture fails with an internal error when main ends with System.exit | `docs/reference/cli.md` | Yes |
| <a id="dx-097">DX-097</a> (F27-3) | instructor | Troubleshooting omits exit status 1 and the most common first-run source failures | `docs/guides/troubleshooting.md` | Yes |
| <a id="dx-098">DX-098</a> (F27-4) | instructor | Requirements for submitted programs are undocumented (public type, one main, empty args) | `docs/guides/tracing.md` | Yes |
| <a id="dx-059">DX-059</a> (F19-1) | embedder | Java guide batch/discovery isolation guidance lags corrected Javadoc | `docs/reference/java-api.md` | Yes |
| <a id="dx-060">DX-060</a> (F19-2) | embedder | Java guide still omits debug-path matching and silent empty selections | `docs/reference/java-api.md` | Yes |
| <a id="dx-068">DX-068</a> (F21-1) | embedder | No way given to obtain the required release commit SHA; the obvious gh lookup returns the tag object | `docs/guides/offline-javadoc.md` | Yes |
| <a id="dx-069">DX-069</a> (F21-2) | embedder | Offline verification recipe omits the GitHub CLI feature/version prerequisite | `docs/guides/offline-javadoc.md` | Yes |
| <a id="dx-083">DX-083</a> (F24-3) | embedder | getGson() row offers json()/writeJson (envelope) as equal to traceJson() (3.x payload) | `docs/guides/java-api-migration.md` | Yes |
| <a id="dx-084">DX-084</a> (F24-4) | embedder | Page omits that Tracer.trace drops 3.x breakpoint-keyed map results for selected breakpoints | `docs/guides/java-api-migration.md` | Yes |
| <a id="dx-085">DX-085</a> (F24-5) | embedder | Table leaves out documented 3.x entry points: TraceSession, cancel(), PersistentGuestSession, 2-arg BatchTraceService | `docs/guides/java-api-migration.md` | Yes |
| <a id="dx-103">DX-103</a> (F28-2) | embedder | Release Maven integration has no documented resolvable repository and complete consumer recipe | `docs/tutorials/java-integration.md` | Yes |
| <a id="dx-104">DX-104</a> (F28-4) | embedder | Java Markdown batch defaults remain implicit after Javadoc clarification | `docs/reference/java-api.md` | Yes |
| <a id="dx-105">DX-105</a> (F28-5) | embedder | Markdown Java paths lack shared contract links now present in Javadoc | `docs/reference/java-api.md` | Yes |

### S3: 30 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <a id="dx-008">DX-008</a> (F4-2) | integrator | Launcher contract and CODE_TRACER_JAR_DIR live only in a Get-started how-to; no reference entry | `docs/guides/installation.md` | Later cutoff |
| <a id="dx-012">DX-012</a> (F6-1) | integrator | Tutorial says output reference defines all fields, but it lacks heap kinds, object fields, frame fields | `docs/tutorials/read-a-trace.md` | Later cutoff |
| <a id="dx-015">DX-015</a> (F8-1) | integrator | The list-breakpoints --json output fields are described only in this tutorial, and incompletely | `docs/tutorials/trace-a-loop.md` | Later cutoff |
| <a id="dx-025">DX-025</a> (F10-2) | integrator | No how-to shows how to turn existing .java files into NDJSON batch requests | `docs/tutorials/batch-jobs.md` | Later cutoff |
| <a id="dx-034">DX-034</a> (F13-3) | integrator | Explains primitive and object locals but not null references, which modern JSON shows by omitting value | `docs/explanation/trace-model.md` | Later cutoff |
| <a id="dx-039">DX-039</a> (F14-4) | all | CLI reference omits trace option interactions and the inspection and enum-hash defaults | `docs/reference/cli.md` | Later cutoff |
| <a id="dx-055">DX-055</a> (F17-4) | integrator | batch-trace process exit statuses are not documented, though other pages defer to this page | `docs/reference/batch.md` | Later cutoff |
| <a id="dx-065">DX-065</a> (F20-1) | all | Neither licensing page says which dependencies ship inside code-tracer.jar or where their notices are | `docs/reference/dependencies.md` | Later cutoff |
| <a id="dx-073">DX-073</a> (F22-5) | integrator | Envelope Markdown lacks a complete counter reference despite improved outcome Javadoc | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <a id="dx-076">DX-076</a> (F23-3) | integrator | 'Minimal environment' omits Tracer's JDK and JAVA_HOME/PATH preconditions; a cleared env fails every job | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-077">DX-077</a> (F23-4) | integrator | 'Necessary intra-job JDI transport' is never specified (loopback TCP on an ephemeral port) | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-078">DX-078</a> (F23-5) | integrator | Contract omits that Tracer prepares sources in java.io.tmpdir, which survives a kill | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-119">DX-119</a> (F30-6) | integrator | Multi-file source format is a shared contract with six names and no reference definition | `docs/reference/batch.md` | Later cutoff |
| <a id="dx-120">DX-120</a> (F30-7) | all | GLOSSARY.md defines only internal ownership terms and is not published or linked | `GLOSSARY.md` | Later cutoff |
| <a id="dx-125">DX-125</a> (F31-3) | all | Trust model is stated only as rules; no page explains what an unsandboxed guest can access | `docs/explanation/trace-model.md` | Later cutoff |
| <a id="dx-126">DX-126</a> (F31-4) | all | TRUSTED vs FIELDS trade-off is buried in the limits page and never explained | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <a id="dx-127">DX-127</a> (F31-6) | integrator | No page explains why two JSON formats exist or how to choose between pytutor and modern | `docs/reference/output.md` | Later cutoff |
| <a id="dx-132">DX-132</a> (FU2-2) | integrator | Envelope phase on normal completion and source_file_limit differs between CLI and batch/Java | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <a id="dx-019">DX-019</a> (F9-4) | instructor | Budgets section never shows how to raise a limit or use --unlimited for a complete trace | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-030">DX-030</a> (F12-4) | instructor | No entry for `trace` silently waiting for source on stdin when --input is omitted | `docs/guides/troubleshooting.md` | Later cutoff |
| <a id="dx-099">DX-099</a> (F27-6) | instructor | No guidance on getting from a trace file to a visualization; Python Tutor output is never walked through | `docs/index.md` | Later cutoff |
| <a id="dx-100">DX-100</a> (F27-8) | instructor | No how-to for tracing a multithreaded program; single-thread capture silently swaps stacks | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-128">DX-128</a> (F31-7) | instructor | Trace model promises input observation but never explains it; meaning of stdinConsumed varies by reader | `docs/explanation/trace-model.md` | Later cutoff |
| <a id="dx-057">DX-057</a> (F18-2) | embedder | Java guide omits breakpoint syntax now documented in request Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-061">DX-061</a> (F19-3) | embedder | Markdown discovery omits the entry-point prerequisite now in Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-079">DX-079</a> (F23-6) | embedder | Job interface covers the CLI only; Java API callers sent here are not told their own JVM must be per-job | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-106">DX-106</a> (F28-6) | embedder | Markdown batch failures lag the corrected processStream/close Javadoc | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-107">DX-107</a> (F28-7) | embedder | No Java how-to guides; the BatchTracer and common-task recipes are missing or buried in reference | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-108">DX-108</a> (F28-8) | embedder | Embedder lifecycle Javadoc improved; Markdown explanation and concurrency contract remain missing | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-134">DX-134</a> (FU3-1) | embedder | Java guide omits runtime prerequisites now documented in Javadoc | `docs/reference/java-api.md` | Later cutoff |

### S4: 54 findings

| ID | Reader | Finding | Page | Selected |
| --- | --- | --- | --- | --- |
| <a id="dx-002">DX-002</a> (F1-2) | integrator | JSON-consumer row starts single-trace integrators in the NDJSON batch tutorial | `docs/index.md` | Later cutoff |
| <a id="dx-005">DX-005</a> (F2-2) | all | Documentation list omits the shared-references tutorial, Java API reference, and migration guides | `README.md` | Later cutoff |
| <a id="dx-006">DX-006</a> (F2-3) | integrator | Class-path paragraph names CLI and batch options but links only the Java API and a how-to | `README.md` | Later cutoff |
| <a id="dx-011">DX-011</a> (F5-1) | all | Text-editor checkpoint prepares learners for the \n escape only, not = or Windows \r\n | `docs/tutorials/first-trace.md` | Later cutoff |
| <a id="dx-026">DX-026</a> (F10-4) | all | Separate unchecked PowerShell pipeline where the --input option works in every shell | `docs/tutorials/batch-jobs.md` | Later cutoff |
| <a id="dx-031">DX-031</a> (F12-5) | all | Running-JDK mismatch row links back to the checkpoint that sent the reader here | `docs/guides/troubleshooting.md` | Later cutoff |
| <a id="dx-032">DX-032</a> (F12-6) | all | "Report a reproducible problem" lists what to include but not where to report it | `docs/guides/troubleshooting.md` | Later cutoff |
| <a id="dx-035">DX-035</a> (F13-4) | all | State model covers callStack and heap but not statics or multithread threads | `docs/explanation/trace-model.md` | Later cutoff |
| <a id="dx-040">DX-040</a> (F14-6) | all | Binary-dependency semantics are restated in four pages instead of linked | `docs/reference/cli.md` | Later cutoff |
| <a id="dx-041">DX-041</a> (F14-7) | integrator | The batch-trace section has no link to the batch protocol | `docs/reference/cli.md` | Later cutoff |
| <a id="dx-042">DX-042</a> (F14-8) | all | Version-output migration note refers to an unnamed 'previous' release | `docs/reference/cli.md` | Later cutoff |
| <a id="dx-046">DX-046</a> (F15-7) | all | Example JSON sits under the multithread heading, far from 'Complete chronological examples' | `docs/reference/output.md` | Later cutoff |
| <a id="dx-047">DX-047</a> (F15-8) | all | 'Examples 34–41' and the primitive-bits example are unlinked repository paths | `docs/reference/output.md` | Later cutoff |
| <a id="dx-048">DX-048</a> (F15-9) | integrator | Source-identity and guest wording uses non-glossary terms and internal lifecycle details | `docs/reference/output.md` | Later cutoff |
| <a id="dx-049">DX-049</a> (F15-10) | integrator | Unversioned 3.2.2 migration note embedded in the generic type-label reference | `docs/reference/output.md` | Later cutoff |
| <a id="dx-051">DX-051</a> (F16-3) | integrator | 'Selected breakpoints and envelopes' restates Result contracts vaguely and receives the inbound link | `docs/reference/output.md` | Later cutoff |
| <a id="dx-052">DX-052</a> (F16-4) | integrator | Limit names `traceBytes`/`snapshotBytes` are called 'counters', but the envelope counter is `retainedBytes` | `docs/reference/output.md` | Later cutoff |
| <a id="dx-056">DX-056</a> (F17-7) | integrator | Missing or imprecise links to the bundle syntax, the envelope section, and the Java BatchTracer | `docs/reference/batch.md` | Later cutoff |
| <a id="dx-062">DX-062</a> (F19-4) | integrator | Glob grammar shared by CLI, batch, and Java is defined only inside the SourceBundle section | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-066">DX-066</a> (F20-2) | integrator | Says retained release snapshots preserve their own inventory; none of the seven retained snapshots has one | `docs/reference/dependencies.md` | Later cutoff |
| <a id="dx-075">DX-075</a> (F23-2) | integrator | Finite limits are only 'preferred', but the recommended --result-envelope makes every omitted limit unlimited | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-080">DX-080</a> (F23-7) | integrator | Result handling leaves 'normally returned' undefined and doesn't link exit statuses, envelope, or flags | `docs/RUNNER_CONTRACT.md` | Later cutoff |
| <a id="dx-109">DX-109</a> (F29-3) | integrator | No index row or prominent sidebar entry for hosting untrusted submissions | `docs/index.md` | Later cutoff |
| <a id="dx-110">DX-110</a> (F29-4) | integrator | 'Reference and integration' mixes references, how-tos, and licensing; envelope page sits apart | `website/sidebars.js` | Later cutoff |
| <a id="dx-111">DX-111</a> (F29-5) | integrator | Batch and Java tutorials are listed among how-tos with task-style titles | `website/sidebars.js` | Later cutoff |
| <a id="dx-112">DX-112</a> (F29-6) | all | CLI reference opens with a maintainer regeneration instruction, not a page summary | `docs/reference/cli.md` | Later cutoff |
| <a id="dx-113">DX-113</a> (F29-7) | all | Seven historical review pages are built, in the sitemap, and reachable from no navigation | `website/sidebars.js` | Later cutoff |
| <a id="dx-121">DX-121</a> (F30-8) | integrator | Output reference calls Java and batch envelopes separate contracts; they share one v1 schema | `docs/reference/output.md` | Later cutoff |
| <a id="dx-122">DX-122</a> (F30-9) | integrator | Markdown limit duplication remains after Java default clarification | `docs/BOUNDED_TRACING.md` | Later cutoff |
| <a id="dx-123">DX-123</a> (F30-11) | all | The tested-JDK statement is repeated on four pages and will drift when the CI matrix changes | `docs/guides/installation.md` | Later cutoff |
| <a id="dx-009">DX-009</a> (F4-4) | instructor | Binary-distribution path sends readers to the first trace and skips the installation checkpoint | `docs/guides/installation.md` | Later cutoff |
| <a id="dx-013">DX-013</a> (F6-3) | instructor | Closing H2s look like lesson steps but only link out; how-to and entry-point tutorials missing | `docs/tutorials/read-a-trace.md` | Later cutoff |
| <a id="dx-014">DX-014</a> (F7-1) | instructor | Checkpoints name source lines only by number, but the rendered source block shows no line numbers | `docs/tutorials/trace-shared-references.md` | Later cutoff |
| <a id="dx-016">DX-016</a> (F8-2) | instructor | The failure and exit-status checkpoint comes after all four commands, not after the first | `docs/tutorials/trace-a-loop.md` | Later cutoff |
| <a id="dx-020">DX-020</a> (F9-6) | instructor | Copied selection table omits the main-exit step that restricted chronological capture adds | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-021">DX-021</a> (F9-7) | instructor | Breakpoint discovery has no command and no warning that bad lines silently select nothing | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-022">DX-022</a> (F9-8) | instructor | How-to embeds CLI-reference catalogues of class-path and presentation rules | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-023">DX-023</a> (F9-9) | instructor | Tracing recipes never link to the troubleshooting guide | `docs/guides/tracing.md` | Later cutoff |
| <a id="dx-036">DX-036</a> (F13-5) | instructor | Uses only modern field names and never links the Python Tutor format, the CLI default | `docs/explanation/trace-model.md` | Later cutoff |
| <a id="dx-067">DX-067</a> (F20-3) | instructor | The "Dependency reference" title collides with the guest "binary dependencies" contract in the same sidebar group | `docs/reference/dependencies.md` | Later cutoff |
| <a id="dx-101">DX-101</a> (F27-9) | instructor | The 42-program teaching example catalog is unreachable from the docs and the release download | `docs/index.md` | Later cutoff |
| <a id="dx-003">DX-003</a> (F1-3) | embedder | 'API reference guide' labels java-api.md, while 'API Reference' elsewhere means the Javadoc | `docs/index.md` | Later cutoff |
| <a id="dx-010">DX-010</a> (F4-5) | embedder | Offline API documentation subsection duplicates offline-javadoc.md and adds a navigation hop | `docs/guides/installation.md` | Later cutoff |
| <a id="dx-027">DX-027</a> (F11-4) | embedder | Says API Guardian is 'needed' to compile; javac succeeds without it, with warnings | `docs/tutorials/java-integration.md` | Later cutoff |
| <a id="dx-058">DX-058</a> (F18-5) | embedder | No direct route to member-level Javadoc; page points to the version menu instead | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-063">DX-063</a> (F19-5) | embedder | Element budget described as cumulative without its per-snapshot scope | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-064">DX-064</a> (F19-6) | embedder | Builder and batch sections mix change history and internal-API pointers into the reference | `docs/reference/java-api.md` | Later cutoff |
| <a id="dx-070">DX-070</a> (F21-3) | embedder | Release-availability text is imprecise: no release ships the bundle, and the fallback only covers 3.2.1+ | `docs/guides/offline-javadoc.md` | Later cutoff |
| <a id="dx-082">DX-082</a> (F24-2) | embedder | SourcePreparation is listed as a 3.x integration point but no 3.x release contained it | `docs/guides/java-api-migration.md` | Later cutoff |
| <a id="dx-086">DX-086</a> (F24-6) | embedder | Migration page links to no API reference, output contract, or Java tutorial | `docs/guides/java-api-migration.md` | Later cutoff |
| <a id="dx-087">DX-087</a> (F24-7) | embedder | Contributor JPMS and build-check rationale takes space the migrator needs for behavior changes | `docs/guides/java-api-migration.md` | Later cutoff |
| <a id="dx-088">DX-088</a> (F25-1) | embedder | Contributor-only internals page sits in user guides/, appears in no sidebar, and links back to nothing | `docs/guides/java-module-migration.md` | Later cutoff |
| <a id="dx-090">DX-090</a> (F25-3) | embedder | Link labeled 'Java integration and 4.0 migration' points to a reference whose migration content moved | `docs/guides/java-module-migration.md` | Later cutoff |
| <a id="dx-114">DX-114</a> (F29-12) | embedder | Offline Javadoc link targets an installation summary instead of the dedicated guide | `docs/reference/java-api.md` | Later cutoff |

## Merged, deferred, and closed baseline candidates

These five candidates are retained for traceability and are not extra active items.

| ID | Disposition | Reason |
| --- | --- | --- |
| <a id="dx-001">DX-001</a> (F1-1) | merged | The existing trust/isolation link covers the safety contract; the actionable routing/clarity improvement duplicates F29-3. |
| <a id="dx-089">DX-089</a> (F25-2) | deferred-contributor-scope | Technically valid internal-policy wording, but this page declares itself contributor-only. Preserve the fact for a contributor pass; user-facing navigation remains F25-1. |
| <a id="dx-094">DX-094</a> (F26-5) | closed-already-documented | The behavior reproduces, but batch.md:52-54 already states the TRUSTED neighbor-discovery rule. It is not an additional documentation defect. |
| <a id="dx-130">DX-130</a> (FU1-2) | merged | Modern single-stack step_line exception representation is the same output-contract correction already covered by F27-2. |
| <a id="dx-135">DX-135</a> (FU3-2) | merged | Add the qualified nesting phase/counter detail to the existing complete stop-code table task; serializer/extraction variants were checked statically only. |

## Javadoc correction

JD-001 corrected `App.execute`'s false promise that it would not terminate the calling JVM. Error/controlled-stop handlers can call `System.exit`; the supported comments now direct embedders to `Tracer` or `BatchTracer`. This is a corrected documentation claim, separate from the original 135 candidates. Runtime behavior did not change.

## Remediation record

The [baseline commit](https://github.com/cs1302uga/cs1302-tracer/commit/0e760d7cab637c413f6eada784addbc592557390) precedes the consumer corrections. The original inventory, priorities, dispositions, reviewer votes, and execution evidence remain frozen. The [remediation overlay](diataxis-audit/remediation.json) records a separate status for all 135 candidates, with current correction references and residual reasons. These statuses describe documentation corrections, not runtime repairs.

### First correction pass

The first pass through `b42dad1` recorded the following documentation results.
The later approved follow-up below supplies the current totals.

| Baseline-active findings | Corrected | Partial | Pending |
| --- | --- | --- | --- |
| 46 selected S1/S2 findings | 45 | 1 | 0 |
| 84 lower-priority findings | 6 | 10 | 68 |
| Total | 51 | 11 | 68 |

That pass left 79 partial or pending findings in the audit. The five baseline merged, deferred, or already-documented candidates retained their dispositions and are outside these totals. No new page, page split, sidebar restructure, or published snapshot correction was included.

### Committed corrections

- [Javadoc contracts](https://github.com/cs1302uga/cs1302-tracer/commit/20e4a694c6b2bede067680e50a37507c644acab7): supported entry points, budgets, ownership, guest lifetime, result limitations, package summaries, and overview examples.
- [Result and integration contracts](https://github.com/cs1302uga/cs1302-tracer/commit/81c2217b9ad6bdc2dec911ce288be99ab36f94c9): selected-output shapes and projection, source-less thread events, null versus empty payloads, all 24 stop codes, batch parsing and correlation, reusable-guest state, and Java migration behavior.
- [CLI and user workflows](https://github.com/cs1302uga/cs1302-tracer/commit/830d01945fa645e1e1599bd8b6b500a66833129e): source selection, discovery, exit statuses, exceptions, JDK discovery, offline verification prerequisites, whole-job teardown, and a complete separate Maven consumer. The two approved frame/thread help descriptions and generated CLI reference now identify their multithread-only scope.

The first pass's same-page corrections also resolved lower-priority [DX-027](#dx-027), [DX-030](#dx-030), [DX-057](#dx-057), [DX-063](#dx-063), [DX-082](#dx-082), and [DX-121](#dx-121). Ten other lower-priority findings improved partially; the overlay preserves those first-pass statuses alongside later corrections.

### Verification of the correction pass

The [validation record](diataxis-audit/remediation-validation.json) ties the checked source paths to the correction commits and preserves environment, commands, coverage totals, and independent consumer inputs. On macOS aarch64 with JDK 25:

- `./mvnw -B -ntp -Ppre-commit-coverage package` passed all **1,248 tests**. Its aggregate Java coverage recorded **6,484 production lines and 3,684 branches, with none missed**. Execution data was retained between runs, so these totals do not establish coverage from that invocation alone; the earlier description of this data as fresh was incorrect. The upstream staged whitespace and Checkstyle hooks also passed for the correction commits; their implementation and the CI/release regression gates are unchanged.
- `check_docs.py --update-cli` regenerated the two approved help descriptions and passed executable documentation checks. The later `./mvnw -B -ntp -Pdocsite -DskipTests install` reran executable docs serially, checked dependency inventory and release metadata, generated current and seven historical API versions, built Docusaurus, and validated links and fragments across **1,280 rendered pages**. It reused the existing coverage data rather than rerunning tests. The first-pass JSON validation record preserves the original claims; this paragraph corrects their freshness qualification.
- A separate Maven project compiled the tutorial's complete POM and `TraceExample`, resolved the locally installed regular library and its transitive dependencies, and emitted a completed v1 envelope with guest stdout `hello\n`. This establishes the local-install workflow; it does not establish package-registry access.
- [Fresh contract probes](diataxis-audit/contract-probes.json) confirmed the ordinary CLI's bare-line-number output-projection buckets and the distinct zero-snapshot `System.exit(0)` results for ordinary CLI, CLI envelope, batch, and Java API callers. Inputs and captured text output are preserved alongside the summary.
- Rendered review covered installation, CLI, the Maven lesson, and bounded-result tables. At a 390 CSS-pixel viewport, long tables scroll within their containers without widening the page. Independent static review of the correction diff found no new actionable factual errors within the selected scope.
- Direct browser checks identified trailing-slash route rewriting on JSON evidence links. The site now treats generated file assets as file URLs while preserving ordinary routes and anchors. All 11 evidence downloads returned HTTP 200, parsed as JSON, and matched their source SHA-256 hashes; rendered link checks also passed. This site correction is separate from the 16-file consumer correction diff and does not alter the frozen audit inventory.

Native Windows commands received static review only. This pass did not repeat runtime tests on JDK 21, run a fresh CodeQL scan, verify registry access, certify hosted isolation, or deploy the site. Historical evidence retains its own revision and execution limits.

### Remaining decisions and runtime limitations

At the end of the first pass, [DX-095 / F27-1](#dx-095) remained partial: authored Markdown and Javadoc correctly said string inlining applied only to Python Tutor, but executable/generated help lacked that qualification. The original Java exception covered only the frame/thread descriptions. The separately approved help correction and its current status are recorded below.

The documented runtime limitations remain. In particular, single-stack Java/batch execution can report completion for an uncaught exception originating in JDK or precompiled dependency code; default main-exit capture can fail without an ordinary payload; output projection differs by caller; unknown request keys or inspection strings remain permissive; and an unreadable `--stdin-file` can fail before envelope construction. The docs now describe those behaviors and their supported alternatives. Any runtime correction needs its own compatibility decision and tests across the affected callers and guest modes.

The first pass's 79 residual entries supplied the later selection. Existing-page corrections were considered independently; new explanations, page splits, and navigation changes retain their outline checkpoint. No unselected finding is closed merely because a nearby paragraph changed.

### Approved follow-up corrections

After the first correction pass, the user approved the remaining string-inlining
help clarification and ten further findings on existing pages. The follow-up
records eleven additional documentation closures. The original 135 candidates,
stable DX IDs, priorities, baseline dispositions, and historical evidence remain
unchanged.

| Follow-up scope | Findings | Documentation change |
| --- | --- | --- |
| Complete the original selection | [DX-095](#dx-095) / F27-1 | Qualify executable `--inline-strings` help as Python Tutor-only, regenerate its CLI reference, and remove the superseded help-omission caveat. |
| Batch process and result metadata | [DX-055](#dx-055) / F17-4, [DX-073](#dx-073) / F22-5, [DX-132](#dx-132) / FU2-2 | Separate process exits from per-job outcomes; define counter presence, guest-exit interpretation, and caller-specific phases. |
| Modern fields and null references | [DX-012](#dx-012) / F6-1, [DX-034](#dx-034) / F13-3 | Complete the modern field reference and distinguish a visible null variable from an absent local, preserving caller-specific null encoding. |
| Breakpoint listing and instructor recovery | [DX-015](#dx-015) / F8-1, [DX-019](#dx-019) / F9-4, [DX-016](#dx-016) / F8-2 | Define listing fields and source-path/line-ending limitations, add budget-tuning recipes, and put recovery guidance immediately after the loop tutorial's first command. |
| Java discovery and stream failure | [DX-061](#dx-061) / F19-3, [DX-106](#dx-106) / F28-6 | Document discovery's entry-point prerequisite, stream I/O failure and late writes, writer ownership, and shutdown waits plus cleanup. |

Implementation is recorded in
[af12d9c](https://github.com/cs1302uga/cs1302-tracer/commit/af12d9c6b88e20662845718c5fbc7161db66b3ac),
[a04a2e2](https://github.com/cs1302uga/cs1302-tracer/commit/a04a2e2f4dbb0098b073ac422401eda52f93e7b6),
[93a5fb9](https://github.com/cs1302uga/cs1302-tracer/commit/93a5fb9af01c8f9aea8680f94eab27660fb67b47), and
[8b6b284](https://github.com/cs1302uga/cs1302-tracer/commit/8b6b2847a8cf342e0ce8b6e5eb099094f13c03b9). The follow-up validation record identifies the checked source commit and
its matching file hashes. These changes clarify existing behavior; they
preserve runtime tracing logic, Java signatures, CLI arguments and defaults,
JSON/batch schemas, published snapshots, and existing page routes.

The updated documentation status among the 130 baseline-active findings is:

| Baseline-active findings | Documentation corrected | Partial | Pending |
| --- | --- | --- | --- |
| 46 originally selected S1/S2 findings | 46 | 0 | 0 |
| 84 other baseline-active findings | 16 | 7 | 61 |
| Total | 62 | 7 | 61 |

All 46 originally selected findings are now corrected in documentation. The
remaining documentation inventory is **68 findings: seven partial and 61
pending**. The five merged, deferred, or already-documented baseline candidates
remain outside these active totals. New explanations, page splits, and sidebar
changes retain their separate outline checkpoint. Documentation closure does not
mean a documented runtime limitation has been repaired.

### Follow-up validation status

The [follow-up validation record](diataxis-audit/followup-validation.json)
identifies source commit `5796651`, checked file hashes, commands, environment,
and results separately from first-pass evidence. Its strict Java run passed
**1,248 tests**, with **6,484 production lines and 3,684 branches covered and none
missed**. Before that invocation, the old execution data was archived and absent
from `target/jacoco.exec`, and the old XML was moved away. The resulting execution
data, XML, and every Surefire report postdate the recorded start. This fresh run
supersedes the first-pass coverage-freshness claim without rewriting its saved
packet. No other guest-executing checks ran concurrently.

The 25 serial contract, recipe, and listing-path command checks cover
process/metadata distinctions, null representation, breakpoint listings, budget
recipes, discovery diagnostics, and writes after a stream failure. Their original
commands, inputs, JAR hashes where recorded, and text output remain in the new
packet. No Java execution logic changed between these probes and `5796651`.
The saved complete documentation workflow passed dependency inventory, executable
examples, release metadata, current and seven historical API versions, Docusaurus,
and links/fragments across 1,280 pages. It skipped Java tests and reused the
then-existing aggregate coverage; the separate fresh strict run supplies Java
coverage proof. The 18 existing website tests also passed; their measured modules
do not establish coverage for every frontend component.

PR #24's original macOS Java 25 job passed all tests but failed the strict line
gate. Two independent `clean package` runs at its exact source tree passed all
1,248 tests with zero missed lines or branches. The unchanged hosted macOS Java
25 rerun also passed. The cause of the original failure remains unidentified;
no speculative code/test fix, threshold change, or workflow change is claimed.
The unchanged hosted rerun subsequently passed all required job groups and the
Regression gates aggregate. The packet records both the original failure and the
dated successful rerun; the live run remains authoritative for CI readiness.

New native Windows prose received static review; no fresh local Windows/JDK 21
runtime run, CodeQL scan, registry verification, hosted-isolation certification,
or deployment is asserted here. Desktop and phone-width review found the new
field/counter tables readable without page-wide overflow. At that historical
preparation stage, PR creation retained the user's final review checkpoint.

### Pull-request dependency and remaining runtime decision

The audit pull request [#30](https://github.com/cs1302uga/cs1302-tracer/pull/30)
exists as a draft targeting `main`. Its prerequisite
[#24](https://github.com/cs1302uga/cs1302-tracer/pull/24) has merged. On 2026-10-19,
main fast-forwarded to changelog correction
[#34](https://github.com/cs1302uga/cs1302-tracer/pull/34), commit
[`2f46621`](https://github.com/cs1302uga/cs1302-tracer/commit/2f4662139eb3a52873e441eba83095b25191f063),
and the eleven audit commits were rebased onto that exact base. The
[post-release rebase record](diataxis-audit/post-release-rebase.json) maps the
rewritten implementation commits while preserving their original identities.
The published changelog sections and guide snapshots retain the new base's bytes;
the audit changes belong to Unreleased.

The correction totals above describe the original approved documentation scope.
The original findings, residual decisions, probe results, source hashes, and
validation packets remain frozen at their recorded revisions. Current-facing
implementation links above identify the rebased equivalents; historical
validation against `5796651` remains historical. Fresh local and hosted results
must identify the actual final audit head and are recorded in the pull request.
Passing prerequisite or earlier-head checks does not establish that head's
readiness. Keep #30 draft until separately authorized for merge.

A separate, unimplemented proposal addresses the runtime exception-recognition
defect documented in [DX-129 / FU1-1](#dx-129). It examines persistent-guest event
filters, escaped-entry-point evidence, job-thread attribution, valid partial
traces, output, and consecutive-job cleanup. Its proposed implementation boundary
comes from source inspection; the original reproduction retains its historical
revision and platform limits. Default versus opt-in behavior, the job-thread
boundary, and any terminal-snapshot expansion still require explicit decisions.
Preparing that proposal does not authorize runtime changes or close the defect.
