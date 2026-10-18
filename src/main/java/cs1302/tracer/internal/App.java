package cs1302.tracer.internal;

import org.apiguardian.api.API;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.internal.CompilationHelper.CompilationResult;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.internal.execution.JobOptions;
import cs1302.tracer.internal.execution.ClassPathOptions;
import cs1302.tracer.internal.execution.TraceJob;
import cs1302.tracer.internal.execution.TraceCompletion;
import cs1302.tracer.api.TraceLimits;
import cs1302.tracer.internal.execution.TraceSession;
import cs1302.tracer.internal.execution.TraceResult;
import cs1302.tracer.api.InspectionPolicy;
import cs1302.tracer.internal.model.BreakpointEntry;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.serialize.PyTutorSerializer;
import cs1302.tracer.internal.serialize.TraceRendering;
import cs1302.tracer.internal.trace.BreakpointSpec;
import cs1302.tracer.internal.trace.DebugTraceHelper;
import cs1302.tracer.internal.trace.TraceDispatch;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Entry point for the tracer program.
 *
 * <p>Normative References: <ul> <li>The Java Language Specification (Java SE 21 Edition), &sect;12.1.4 (Invoke
 * {@code void main(String[])}). <li>Picocli Specification 4.7 (Command-line parsing and option binding). </ul>
 *
 * <p>Informative References: <ul> <li>Online Python Tutor (OPT) Trace Event Format v3 Specification (Philip Guo, 2013).
 * </ul>
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
@Command(name = "code-tracer", description = "Trace Java program execution and inspect memory states.",
        mixinStandardHelpOptions = true, usageHelpAutoWidth = true,
        versionProvider = App.PropertiesVersionProvider.class)
public class App {

    /** Constructs a new {@code App} command-line application instance. */
    public App() {} // App

    /** Reports build provenance and runtime details with Picocli-controlled ANSI styling. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public static class PropertiesVersionProvider implements CommandLine.IVersionProvider {

        /** Constructs a new PropertiesVersionProvider. */
        public PropertiesVersionProvider() {} // PropertiesVersionProvider

        @Override
        public String[] getVersion() {
            Properties metadata = new Properties();
            try (InputStream is = App.class.getResourceAsStream("/cs1302/tracer/version.properties")) {
                if (is != null) {
                    metadata.load(is);
                } // if
            } catch (IOException ignored) {
                metadata.clear();
            } // try
            return formatVersion(metadata, System.getProperties());
        } // getVersion

        /**
         * Formats packaged provenance and the properties of the running JVM without subprocesses.
         *
         * @param metadata build-time version and Git provenance
         * @param runtime runtime system properties
         * @return the five-line CLI version report with Picocli ANSI markup
         */
        static String[] formatVersion(Properties metadata, Properties runtime) {
            String version = metadata.getProperty("version", "development");
            String commit = metadata.getProperty("git.commit", "unknown");
            String dirty = Boolean.parseBoolean(metadata.getProperty("git.dirty")) ? "-dirty" : "";
            String runtimeName = runtime.getProperty("java.runtime.name", "unknown");
            String osName = runtime.getProperty("os.name", "unknown");
            osName = osName.equals("Mac OS X") ? "macOS" : osName;
            String javaName = runtimeName.startsWith("OpenJDK") ? "openjdk" : "java";
            String vendor = runtime.getProperty("java.vendor.version", runtime.getProperty("java.vendor", "unknown"));
            return new String[] {
                "@|bold code-tracer " + version + " (" + commit + dirty + ")|@",
                osName + " " + runtime.getProperty("os.version", "unknown")
                    + " " + runtime.getProperty("os.arch", "unknown"),
                javaName + " " + runtime.getProperty("java.version", "unknown")
                    + " " + runtime.getProperty("java.version.date", "unknown"),
                runtimeName + " " + vendor + " (build "
                    + runtime.getProperty("java.runtime.version", "unknown") + ")",
                runtime.getProperty("java.vm.name", "unknown") + " " + vendor
                    + " (build " + runtime.getProperty("java.vm.version", "unknown") + ", "
                    + runtime.getProperty("java.vm.info", "unknown") + ")"
            };
        } // formatVersion

    } // PropertiesVersionProvider

    static Consumer<Integer> systemExitHandler = System::exit;

    /**
     * Executes the CLI application with given arguments.
     *
     * @param args Command-line arguments.
     * @return Process exit code.
     */
    public static int execute(String[] args) {
        try {
            CliEnvironment.validate(System.getenv(), Path.of(System.getProperty("java.home")));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("code-tracer: JDK environment check was interrupted.");
            return 1;
        } catch (IOException | IllegalArgumentException invalid) {
            System.err.println("code-tracer: JDK environment check failed: " + invalid.getMessage());
            return 1;
        } // try
        return new CommandLine(new App()).addSubcommand(new Trace()).addSubcommand(new BatchTrace())
                .addSubcommand(new ListBreakpoints()).addSubcommand(new ShowLicenses()).execute(args);
    } // execute

    /**
     * Main entry point of the CLI application.
     *
     * @param args Command-line arguments.
     * @throws Exception If an error occurs.
     */
    public static void main(String[] args) throws Exception {
        int exitCode = execute(args);
        systemExitHandler.accept(exitCode);
    } // main

    /**
     * Parse the given Java source code string with optional source root for type resolution.
     *
     * @param source The Java source code to parse.
     * @param sourceRoot The root directory where source files for the program are located.
     * @return The parsed Java source code.
     * @throws IllegalArgumentException If parsing failed.
     */
    public static CompilationUnit parseSource(String source, Optional<Path> sourceRoot) {
        return SourcePreparation.parseSource(source, sourceRoot);
    } // parseSource

    /**
     * Discovers and parses all compilation units in the source files and source root.
     *
     * @param sourceFiles Input source files.
     * @param sourceRoot Optional source root path.
     * @param parserSourceRoot Parser type resolution path.
     * @return List of parsed CompilationUnits.
     */
    public static List<CompilationUnit> discoverAllCompilationUnits(List<CompilationHelper.SourceFile> sourceFiles,
            Optional<Path> sourceRoot, Optional<Path> parserSourceRoot) {
        return SourcePreparation.discoverAllCompilationUnits(sourceFiles, sourceRoot, parserSourceRoot);
    } // discoverAllCompilationUnits

    /** Base class that holds common CLI parameters. */
    @Command
    abstract static class CommandBase implements Runnable {

        @picocli.CommandLine.Mixin
        ClassPathOptions dependencies = new ClassPathOptions();

        @Option(names = { "--verbose", "-v" }, description = "Output messages about what the tracer is doing.")
        boolean verbose = false;

        @Option(names = { "--input", "-i" },
                description = "Input path to Java source file (defaults to stdin if omitted).")
        File input = null;

        @Option(names = { "--pretty", "-p" }, description = "Pretty-print JSON output.")
        boolean pretty = false;

        public Consumer<Integer> exitHandler = System::exit;

        /**
         * Read the entirety of {@code input} into a string.
         *
         * @return The read contents of the file.
         * @throws RuntimeException if an IO exception occurred.
         */
        protected String readInputFile() {
            if (input == null) {
                StringBuilder sb = new StringBuilder();
                try (Scanner scan = new Scanner(System.in)) {
                    while (scan.hasNextLine()) {
                        sb.append(scan.nextLine()).append("\n");
                    } // while
                } // try
                return sb.toString();
            } else {
                try {
                    return Files.readString(input.toPath());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } // try
            } // if
        } // readInputFile

        /**
         * Returns the optional absolute Path to the input file.
         *
         * @return Optional containing the input Path.
         */
        protected Optional<Path> getInputPath() {
            return input != null ? Optional.of(input.toPath().toAbsolutePath()) : Optional.empty();
        } // getInputPath

        /**
         * Parse the given Java source code string without type solver.
         *
         * @param source The Java source code to parse.
         * @return The parsed Java source code.
         */
        protected CompilationUnit parseSource(String source) {
            return new com.github.javaparser.JavaParser(
                    new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT)).parse(source).getResult()
                    .orElseThrow(() -> new IllegalArgumentException("Failed to parse Java source"));
        } // parseSource

        /**
         * Parse the given Java source code string with optional source root for type resolution.
         *
         * @param source The Java source code to parse.
         * @param sourceRoot The root directory where source files for the program are located.
         * @return The parsed Java source code.
         * @throws IllegalArgumentException If parsing failed.
         */
        protected CompilationUnit parseSource(String source, Optional<Path> sourceRoot) {
            return App.parseSource(source, sourceRoot);
        } // parseSource

    } // CommandBase

    /** Run a trace. */
    @Command(name = "trace", description = "Generate an execution trace for a Java program.",
            mixinStandardHelpOptions = true, versionProvider = App.PropertiesVersionProvider.class)
    static class Trace extends CommandBase {

        @picocli.CommandLine.Mixin
        JobOptions job = new JobOptions();

        @Option(names = { "--remove-main-args" },
                description = "Don't include the main method's args parameter in the output.")
        boolean removeMainArgs = false;

        @Option(names = { "--inline-strings", "-s" },
            description = "Inline strings into Python Tutor fields; has no effect on modern JSON.")
        boolean inlineStrings = false;

        @Option(names = "--include-primitive-bits",
                description = "Include fixed-width primitive binary metadata alongside values (default: false). "
                        + "Optional flag; no value required. Works with both formats and all capture modes; "
                        + "does not require an envelope. Increases serialized payload size.")
        boolean includePrimitiveBits = false;

        @Option(names = { "--remove-method-this" },
                description = "Don't include the value of this for methods in the output.")
        boolean removeMethodThis = false;

        @Option(names = { "--all-breakpoints", "-a" },
                description = "Include all encountered breakpoint instances in chronological " + "order.")
        boolean allBreakpoints = false;

        @Option(names = { "--accumulate-breakpoints" },
                description = "Output an array of snapshots containing each reached breakpoint.")
        boolean accumulateBreakpoints = false;

        @Option(names = { "--format", "-f" },
                description = "Output trace format: ${COMPLETION-CANDIDATES} " + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "pytutor")
        TraceFormat format = TraceFormat.PYTUTOR;

        @Option(names = { "--type-style" },
                description = "Type qualification style: ${COMPLETION-CANDIDATES} " + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "fqn")
        TypeStyle typeStyle = TypeStyle.FQN;

        /** Choices resolved for the current command invocation. */
        private TraceJob resolvedJob;

        @Option(names = { "--stdin" }, description = "Input string provided to the traced program via standard input.")
        String stdin = null;

        @Option(names = { "--stdin-file" },
                description = "Path to file whose content is provided to the traced program " + "via standard input.")
        File stdinFile = null;

        /**
         * Resolves the guest process standard input string from --stdin or --stdin-file.
         *
         * @return The standard input string to supply to the guest process.
         */
        protected String resolveGuestStdin() {
            if (stdin != null && stdinFile != null) {
                throw new IllegalArgumentException("Cannot specify both --stdin and --stdin-file");
            } // if
            if (stdinFile != null) {
                try {
                    return Files.readString(stdinFile.toPath());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read stdin file: " + stdinFile, e);
                } // try
            } // if
            return stdin != null ? stdin : "";
        } // resolveGuestStdin

        @Override
        public void run() {
            String guestStdin;
            TraceLimits selected;
            try {
                guestStdin = resolveGuestStdin();
                selected = job.limits();
                resolvedJob = TraceJob.resolve(job.profile(), format, typeStyle, selected, job.inspection,
                        job.breakpoints, allBreakpoints, accumulateBreakpoints, job.multithread,
                        removeMainArgs, inlineStrings, removeMethodThis, job.evalEnumHash, includePrimitiveBits,
                        dependencies.resolve());
                resolvedJob.validateThreadFormat(true);
                resolvedJob.capture().selection();
                if (job.envelope) {
                    runBounded(selected, guestStdin);
                    return;
                } // if
                TraceJob.validateCommandInspection(job.profile(), job.inspection);
            } catch (IllegalArgumentException invalid) {
                System.err.println(invalid.getMessage());
                exitHandler.accept(2);
                return;
            } // try
            try (TraceSession session = new TraceSession(selected, resolvedJob.inspection(),
                    resolvedJob.capture().retainsHistory(), resolvedJob.evalEnumHash())) {
                if (resolvedJob.multithread()) {
                    session.enableMultithread();
                } // if
                try {
                    runOrdinary(session, selected, guestStdin);
                } catch (Throwable cause) {
                    TraceResult result = session.result(format.name(), null, cause);
                    boolean stopped = result.status().equals("stopped");
                    System.err.println(stopped ? "Trace stopped: " + result.stopReason()
                            + ". Increase the limit or use --unlimited; use --result-envelope" + " for partial traces."
                            : "Unable to generate trace! " + cause.getMessage());
                    if (verbose) {
                        cause.printStackTrace();
                    } // if
                    exitHandler.accept(stopped ? 3 : 1);
                } // try
            } // try
        } // run

        /**
         * Runs ordinary output with bounded tracing and the existing payload shape.
         *
         * @param session Active tracing session.
         * @param selected Effective limits.
         * @param guestStdin Guest input.
         * @throws Exception On compilation, tracing, or serialization failure.
         */
        private void runOrdinary(TraceSession session, TraceLimits selected, String guestStdin) throws Exception {
            String source = readBoundedSource(session, selected);
            long files = SourceDelimiters.scan(source).size();
            session.enforce(Math.max(1, files), selected.sourceFiles(), "source_file_limit");
            session.phase("compile");
            try (SourcePreparation prepared = SourcePreparation.prepare(
                    source, getInputPath(), resolvedJob.discovery(), resolvedJob.dependencies())) {
                session.phase("trace");
                TraceDispatch.Capture captured = TraceDispatch.dedicated().capture(prepared,
                        resolvedJob.capture().selection(), guestStdin);
                emitTrace(TraceRendering.ordinary(prepared.submitted(), guestStdin, resolvedJob, captured, pretty));
            } // try
        } // runOrdinary

        /**
         * Executes an opt-in job, preserving snapshots on recoverable failure.
         *
         * @param limits Validated resource policy.
         * @param guestStdin Standard input string for guest process.
         */
        private void runBounded(TraceLimits limits, String guestStdin) {
            try (TraceSession session = new TraceSession(limits, resolvedJob.inspection(),
                    resolvedJob.capture().retainsHistory(), resolvedJob.evalEnumHash())) {
                if (resolvedJob.multithread()) {
                    session.enableMultithread();
                } // if
                SubmittedSources submitted = null;
                Throwable failure = null;
                List<ExecutionSnapshot> snapshots = null;
                try {
                    String source = readBoundedSource(session, limits);
                    session.phase("compile");
                    long files = SourceDelimiters.scan(source).size();
                    session.enforce(Math.max(1, files), limits.sourceFiles(), "source_file_limit");
                    try (SourcePreparation prepared = SourcePreparation.prepare(
                            source, getInputPath(), resolvedJob.discovery(), resolvedJob.dependencies())) {
                        submitted = prepared.submitted();
                        snapshots = executeBoundedSource(prepared, session, guestStdin);
                    } // try
                } catch (Exception caught) {
                    failure = caught;
                    if (caught instanceof InterruptedException) {
                        session.cancel();
                        Thread.currentThread().interrupt();
                    } // if
                } // try
                TraceCompletion.Envelope completed = TraceCompletion.envelope(
                        submitted, guestStdin, resolvedJob, session, snapshots, failure);
                TraceResult result = session.result(format.name().toLowerCase(java.util.Locale.ROOT),
                        completed.payload(), completed.failure());
                System.out.println(TraceRendering.freeze(result, pretty).json());
                if (!result.complete()) {
                    exitHandler.accept(result.status().equals("stopped") ? 3 : 1);
                } // if
            } // try
        } // runBounded

        /**
         * Reads sources with a byte cap before parsing or decoding them.
         *
         * @param session Job session.
         * @param limits Source budgets.
         * @return UTF-8 source text.
         * @throws IOException On source read failure.
         */
        private String readBoundedSource(TraceSession session, TraceLimits limits) throws IOException {
            InputStream stream = input == null ? System.in : Files.newInputStream(input.toPath());
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    session.enforce((long) bytes.size() + count, limits.sourceBytes(), "source_limit");
                    bytes.write(chunk, 0, count);
                } // while
                return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                if (input != null) {
                    stream.close();
                } // if
            } // try
        } // readBoundedSource

        /**
         * Captures a prepared job while its caller retains source ownership and detached recovery identity.
         * @param prepared Compiled job and source evidence.
         * @param session Active session.
         * @param guestStdin Standard input string for guest process.
         * @return Completed snapshots.
         * @throws Exception On tracing failure.
         */
        List<ExecutionSnapshot> executeBoundedSource(SourcePreparation prepared, TraceSession session,
                String guestStdin) throws Exception {
            session.phase("trace");
            TraceDispatch.dedicated().capture(prepared, resolvedJob.capture().selection(), guestStdin);
            session.finishOutput();
            return session.snapshots();
        } // executeBoundedSource

        /**
         * Publishes ordinary JSON only after confirming that no limit stopped the job.
         *
         * @param json Complete serialized payload.
         */
        private void emitTrace(String json) {
            TraceSession.current().check();
            System.out.println(json);
        } // emitTrace

    } // Trace

    /** Run batch traces over NDJSON. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    @Command(name = "batch-trace", description = "Execute independent trace jobs concurrently over an NDJSON stream.",
            mixinStandardHelpOptions = true, versionProvider = App.PropertiesVersionProvider.class)
    public static class BatchTrace implements Runnable {

        @Option(names = { "--workers", "-w" },
                description = "Number of concurrent worker sessions (default: ${DEFAULT-VALUE}).", defaultValue = "1")
        int workers = 1;

        @Option(names = "--max-in-flight", defaultValue = "16",
                description = "Maximum admitted jobs and buffered results (default: 16).")
        int maxInFlight = 16;

        @Option(names = "--completion-order", description = "Emit results as jobs finish; default is submission order.")
        boolean completionOrder;

        @Option(names = { "--input", "-i" }, description = "Input path to NDJSON file (defaults to stdin if omitted).")
        File input = null;

        @Option(names = { "--max-jobs-per-worker" },
                description = "Maximum jobs before recycling a worker process " + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "100")
        int maxJobsPerWorker = 100;

        public Consumer<Integer> exitHandler = System::exit;

        /** Constructs a BatchTrace command. */
        public BatchTrace() {} // BatchTrace

        @Override
        public void run() {
            try (BatchTracer service = new BatchTracer(workers, maxJobsPerWorker, maxInFlight,
                    completionOrder)) {
                InputStream is = input != null ? Files.newInputStream(input.toPath()) : System.in;
                try {
                    OutputStreamWriter writer = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
                    service.processStream(is, writer);
                    writer.flush();
                } finally {
                    if (input != null) {
                        is.close();
                    } // if
                } // try
            } catch (Exception e) {
                System.err.println("Batch trace failed: " + e.getMessage());
                exitHandler.accept(1);
            } // try
        } // run

    } // BatchTrace

    /** List the breakpoint lines available for a compiled Java program. */
    @Command(name = "list-breakpoints", description = "List the breakpoints available in the provided source file.",
            mixinStandardHelpOptions = true, versionProvider = App.PropertiesVersionProvider.class)
    static class ListBreakpoints extends CommandBase {

        @Option(names = { "--json", "-j" }, description = "Output available breakpoints in JSON format.")
        boolean outputJson = false;

        @Override
        public void run() {
            String source = readInputFile();

            try {
                CompilationHelper.ParsedSources parsed = CompilationHelper.parseSources(source);
                try (SourcePreparation prepared = SourcePreparation.prepareParsed(parsed, getInputPath(),
                        SourcePreparation.Discovery.NEIGHBORS, dependencies.resolve())) {
                    CompilationResult compilationResult = prepared.compilation();
                    List<SubmittedSources.File> sourceFiles = prepared.submitted().files();
                    if (sourceFiles.size() > 1) {
                        Map<String, Set<Integer>> breakpointsByFile = DebugTraceHelper
                                .getValidBreakpointLinesByFile(compilationResult);
                        listMultiFileBreakpoints(sourceFiles, breakpointsByFile);
                    } else {
                        Collection<Integer> availableBreakpoints = DebugTraceHelper
                                .getValidBreakpointLines(compilationResult);
                        listSingleFileBreakpoints(source, availableBreakpoints);
                    } // if
                } // try
            } catch (Throwable cause) {
                System.err.println("Unable to list breakpoints! " + cause.getMessage());
                if (verbose) {
                    cause.printStackTrace();
                } // if
                exitHandler.accept(1);
            } // try
        } // run

        /**
         * Formats and prints breakpoints for multi-file inputs.
         *
         * @param sourceFiles Input source files.
         * @param breakpointsByFile Valid line mappings by file path.
         */
        private void listMultiFileBreakpoints(List<SubmittedSources.File> sourceFiles,
                Map<String, Set<Integer>> breakpointsByFile) {
            if (outputJson) {
                List<BreakpointEntry> output = new ArrayList<>();
                for (SubmittedSources.File sf : sourceFiles) {
                    String normPath = sf.logicalPath();
                    Set<Integer> validLines = sf.listingLines(breakpointsByFile);
                    String[] fileLines = sf.content().split("\n");
                    for (int i = 0; i < fileLines.length; i++) {
                        int lineNum = i + 1;
                        boolean valid = validLines.contains(lineNum);
                        output.add(new BreakpointEntry(lineNum, valid, fileLines[i], normPath));
                    } // for
                } // for
                System.out.println(PyTutorSerializer.getGson(pretty).toJson(output));
            } else {
                StringBuilder sb = new StringBuilder();
                for (int f = 0; f < sourceFiles.size(); f++) {
                    SubmittedSources.File sf = sourceFiles.get(f);
                    String normPath = sf.logicalPath();
                    sb.append("// --- ").append(normPath).append(" ---\n");
                    Set<Integer> validLines = sf.listingLines(breakpointsByFile);
                    String[] fileLines = sf.content().split("\n");
                    int digitLength = ((int) Math.log10(Math.max(1, fileLines.length))) + 1;
                    for (int i = 0; i < fileLines.length; i++) {
                        if (validLines.contains(i + 1)) {
                            sb.append(Ansi.AUTO.string(String.format("@|green b %" + digitLength + "d | |@", i + 1)));
                        } else {
                            sb.append(String.format("  %" + digitLength + "d | ", i + 1));
                        } // if
                        sb.append(fileLines[i]);
                        if (i < fileLines.length - 1 || f < sourceFiles.size() - 1) {
                            sb.append('\n');
                        } // if
                    } // for
                } // for
                System.out.println(sb.toString());
            } // if
        } // listMultiFileBreakpoints

        /**
         * Formats and prints breakpoints for single-file input.
         *
         * @param source Source code text.
         * @param availableBreakpoints Valid line numbers.
         */
        private void listSingleFileBreakpoints(String source, Collection<Integer> availableBreakpoints) {
            String[] sourceLines = source.split("\n");
            int digitLength = ((int) Math.log10(Math.max(1, sourceLines.length))) + 1;

            if (outputJson) {
                List<BreakpointEntry> output = new ArrayList<>();
                for (int i = 0; i < sourceLines.length; i++) {
                    int lineNumber = i + 1;
                    boolean valid = availableBreakpoints.contains(lineNumber);
                    output.add(new BreakpointEntry(lineNumber, valid, sourceLines[i]));
                } // for
                System.out.println(PyTutorSerializer.getGson(pretty).toJson(output));
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < sourceLines.length; i++) {
                    if (availableBreakpoints.contains(i + 1)) {
                        sb.append(Ansi.AUTO.string(String.format("@|green b %" + digitLength + "d | |@", i + 1)));
                    } else {
                        sb.append(String.format("  %" + digitLength + "d | ", i + 1));
                    } // if
                    sb.append(sourceLines[i]);
                    if (i < sourceLines.length - 1) {
                        sb.append('\n');
                    } // if
                } // for
                System.out.println(sb.toString());
            } // if
        } // listSingleFileBreakpoints

    } // ListBreakpoints

    /** Print dependency licenses to console. */
    @Command(name = "show-licenses", description = "Show the licenses for projects used in this program and then exit.",
            mixinStandardHelpOptions = true, versionProvider = App.PropertiesVersionProvider.class)
    static class ShowLicenses implements Runnable {

        @Override
        public void run() {
            System.out.println(LicenseHelper.getLicenseText());
        } // run

    } // ShowLicenses

} // App
