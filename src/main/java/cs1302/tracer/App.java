package cs1302.tracer;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.batch.BatchTraceService;
import cs1302.tracer.execution.JobOptions;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceSession;
import cs1302.tracer.execution.TraceResult;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.model.BreakpointEntry;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.TypeStyle;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.BreakpointSpec;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Entry point for the tracer program.
 *
 * <p>Normative References:
 * <ul>
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;12.1.4
 *       (Invoke {@code void main(String[])}).
 *   <li>Picocli Specification 4.7 (Command-line parsing and option binding).
 * </ul>
 *
 * <p>Informative References:
 * <ul>
 *   <li>Online Python Tutor (OPT) Trace Event Format v3 Specification (Philip Guo, 2013).
 * </ul>
 */
@Command(
        name = "code-tracer",
        description = "Trace Java program execution and inspect memory states.",
        mixinStandardHelpOptions = true,
        versionProvider = App.PropertiesVersionProvider.class)
public class App {

    /**
     * Constructs a new {@code App} command-line application instance.
     *
     * <p>Normative Reference: The Picocli command-line parsing specification requires a public
     * zero-argument constructor for command dispatch and instantiation.</p>
     */
    public App() {} // App

    /** Provides version string resolved from Maven resource filtering at build time. */
    public static class PropertiesVersionProvider implements CommandLine.IVersionProvider {

        /** Constructs a new PropertiesVersionProvider. */
        public PropertiesVersionProvider() {} // PropertiesVersionProvider

        @Override
        public String[] getVersion() {
            String version = "development";
            try (InputStream is = App.class.getResourceAsStream(
                    "/cs1302/tracer/version.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    version = props.getProperty("version", "development");
                } // if
            } catch (IOException ignored) {
                version = "development";
            } // try
            return new String[] {version};
        } // getVersion
    } // PropertiesVersionProvider

    static Consumer<Integer> systemExitHandler = System::exit;

    /**
     * Executes the CLI application with given arguments.
     *
     * @param args Command-line arguments.
     * @return Process exit code.
     */
    public static int execute(String[] args) {
        return new CommandLine(new App())
                .addSubcommand(new Trace())
                .addSubcommand(new BatchTrace())
                .addSubcommand(new ListBreakpoints())
                .addSubcommand(new ShowLicenses())
                .execute(args);
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
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        sourceRoot.ifPresent(sr -> combinedTypeSolver.add(new JavaParserTypeSolver(sr)));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(symbolSolver)
                .setLanguageLevel(LanguageLevel.CURRENT);

        return new com.github.javaparser.JavaParser(config)
                .parse(source)
                .getResult()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Failed to parse Java source with symbol solver"));
    } // parseSource

    /**
     * Discovers and parses all compilation units in the source files and source root.
     *
     * @param sourceFiles Input source files.
     * @param sourceRoot Optional source root path.
     * @param parserSourceRoot Parser type resolution path.
     * @return List of parsed CompilationUnits.
     */
    public static List<CompilationUnit> discoverAllCompilationUnits(
            List<CompilationHelper.SourceFile> sourceFiles,
            Optional<Path> sourceRoot,
            Optional<Path> parserSourceRoot) {
        List<CompilationUnit> allCus = new ArrayList<>();
        Set<String> parsedPaths = new HashSet<>();
        for (CompilationHelper.SourceFile sf : sourceFiles) {
            allCus.add(parseSource(sf.content(), parserSourceRoot));
            parsedPaths.add(sf.relativePath().replace('\\', '/'));
        } // for
        if (sourceRoot.isPresent()) {
            try (var stream = Files.walk(sourceRoot.get())) {
                List<Path> javaFiles = stream
                        .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .toList();
                for (Path p : javaFiles) {
                    String rel = sourceRoot.get().relativize(p).toString().replace('\\', '/');
                    if (!parsedPaths.contains(rel)) {
                        try {
                            allCus.add(parseSource(Files.readString(p), parserSourceRoot));
                            parsedPaths.add(rel);
                        } catch (Exception ignored) {
                            // ignore parse errors on unreferenced files
                        } // try
                    } // if
                } // for
            } catch (Exception ignored) {
                // ignore file discovery errors
            } // try
        } // if
        return allCus;
    } // discoverAllCompilationUnits

    /** Base class that holds common CLI parameters. */
    @Command
    abstract static class CommandBase implements Runnable {

        @Option(
                names = {"--verbose", "-v"},
                description = "Output messages about what the tracer is doing.")
        boolean verbose = false;

        @Option(
                names = {"--input", "-i"},
                description = "Input path to Java source file (defaults to stdin if omitted).")
        File input = null;

        @Option(
                names = {"--pretty", "-p"},
                description = "Pretty-print JSON output.")
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
                    new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT))
                    .parse(source)
                    .getResult()
                    .orElseThrow(() -> new IllegalArgumentException("Failed to parse Java source"));
        } // parseSource

        /**
         * Parse the given Java source code string with optional source root for type resolution.
         *
         * @param source The Java source code to parse.
         * @param sourceRoot The root directory where source files for the program are located.
         * @return The parsed Java source code.
         * @throws ParseProblemException If parsing failed.
         */
        protected CompilationUnit parseSource(String source, Optional<Path> sourceRoot) {
            return App.parseSource(source, sourceRoot);
        } // parseSource
    } // CommandBase

    /** Run a trace. */
    @Command(
        name = "trace",
        description = "Generate an execution trace for a Java program.",
        mixinStandardHelpOptions = true)
    static class Trace extends CommandBase {

        @picocli.CommandLine.Mixin
        JobOptions job = new JobOptions();

        @Option(
                names = {"--remove-main-args"},
                description = "Don't include the main method's args parameter in the output.")
        boolean removeMainArgs = false;

        @Option(
                names = {"--inline-strings", "-s"},
                description = "If provided, strings are inlined into fields.")
        boolean inlineStrings = false;

        @Option(
                names = {"--remove-method-this"},
                description = "Don't include the value of this for methods in the output.")
        boolean removeMethodThis = false;

        @Option(
                names = {"--all-breakpoints", "-a"},
                description = "Include all encountered breakpoint instances in chronological "
                        + "order.")
        boolean allBreakpoints = false;

        @Option(
                names = {"--accumulate-breakpoints"},
                description = "Output an array of snapshots containing each reached breakpoint.")
        boolean accumulateBreakpoints = false;

        @Option(
                names = {"--format", "-f"},
                description = "Output trace format: ${COMPLETION-CANDIDATES} "
                        + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "pytutor")
        TraceFormat format = TraceFormat.PYTUTOR;

        @Option(
                names = {"--type-style"},
                description = "Type qualification style: ${COMPLETION-CANDIDATES} "
                        + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "fqn")
        TypeStyle typeStyle = TypeStyle.FQN;

        /**
         * Parses the configured breakpoint strings into BreakpointSpec targets.
         *
         * @return List of parsed BreakpointSpec objects.
         */
        List<BreakpointSpec> parsedBreakpoints() {
            return job.breakpointSpecs();
        } // parsedBreakpoints

        @Option(
                names = {"--stdin"},
                description = "Input string provided to the traced program via standard input.")
        String stdin = null;

        @Option(
                names = {"--stdin-file"},
                description = "Path to file whose content is provided to the traced program "
                        + "via standard input.")
        File stdinFile = null;

        /**
         * Resolves the guest process standard input string from --stdin or --stdin-file.
         *
         * @return The standard input string to supply to the guest process.
         */
        protected String resolveGuestStdin() {
            if (stdin != null && stdinFile != null) {
                throw new IllegalArgumentException(
                        "Cannot specify both --stdin and --stdin-file");
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
                job.breakpointSpecs();
                if (job.envelope) {
                    runBounded(selected, guestStdin);
                    return;
                } // if
                if (job.inspection != InspectionPolicy.TRUSTED) {
                    throw new IllegalArgumentException(
                            "Inspection controls require --result-envelope");
                } // if
            } catch (IllegalArgumentException invalid) {
                System.err.println(invalid.getMessage());
                exitHandler.accept(2);
                return;
            } // try
            try (TraceSession session = new TraceSession(selected, job.inspection,
                    allBreakpoints || accumulateBreakpoints, job.evalEnumHash)) {
                try {
                    runOrdinary(session, selected, guestStdin);
                } catch (Throwable cause) {
                    TraceResult result = session.result(format.name(), null, cause);
                    boolean stopped = result.status().equals("stopped");
                    System.err.println(stopped ? "Trace stopped: " + result.stopReason()
                            + ". Increase the limit or use --unlimited; use --result-envelope"
                            + " for partial traces." : "Unable to generate trace!");
                    if (verbose) {
                        cause.printStackTrace();
                    } // if
                    exitHandler.accept(stopped ? 3 : 1);
                } // try
            } // try
        } // run

        /**
         * Runs ordinary output with bounded tracing and the existing payload shape.
         * @param session Active tracing session.
         * @param selected Effective limits.
         * @param guestStdin Guest input.
         * @throws Exception On compilation, tracing, or serialization failure.
         */
        private void runOrdinary(TraceSession session, TraceLimits selected, String guestStdin)
                throws Exception {
            String source = readBoundedSource(session, selected);
            long files = CompilationHelper.DELIMITER_PATTERN.matcher(source)
                    .results().count();
            session.enforce(Math.max(1, files), selected.sourceFiles(),
                    "source_file_limit");
            session.phase("compile");
            List<CompilationHelper.SourceFile> sourceFiles =
                    CompilationHelper.parseMultiFileStream(source);
            CompilationHelper.SourceFile entryFile =
                    CompilationHelper.findEntryPoint(sourceFiles);
            CompilationUnit preCu = entryFile.ast();
            Optional<Path> sourceRoot =
                    CompilationHelper.findSourceRoot(preCu, getInputPath());

            try (CompilationResult compilationResult =
                    CompilationHelper.compile(source, sourceRoot)) {
                Optional<Path> parserSourceRoot = sourceRoot.isPresent()
                        ? sourceRoot
                        : Optional.of(compilationResult.classPath());
                List<CompilationUnit> allCus = discoverAllCompilationUnits(
                        sourceFiles, sourceRoot, parserSourceRoot);

                session.phase("trace");
                if (format == TraceFormat.MODERN) {
                    runModernTrace(source, compilationResult, allCus, guestStdin);
                } else {
                    runPyTutorTrace(source, compilationResult, allCus, guestStdin);
                } // if
            } // try
        } // runOrdinary

        /**
         * Executes an opt-in job, preserving snapshots on recoverable failure.
         *
         * @param limits Validated resource policy.
         * @param guestStdin Standard input string for guest process.
         */
        private void runBounded(TraceLimits limits, String guestStdin) {
            try (TraceSession session = new TraceSession(limits, job.inspection,
                    allBreakpoints || accumulateBreakpoints, job.evalEnumHash)) {
                String source = "";
                Throwable failure = null;
                List<ExecutionSnapshot> snapshots = null;
                try {
                    source = readBoundedSource(session, limits);
                    session.phase("compile");
                    snapshots = executeBoundedSource(source, session, limits, guestStdin);
                } catch (Exception caught) {
                    failure = caught;
                    if (caught instanceof InterruptedException) {
                        session.cancel();
                        Thread.currentThread().interrupt();
                    } // if
                } // try
                if (snapshots == null && session.traceAvailable()) {
                    snapshots = session.snapshots();
                } // if
                Object payload = null;
                try {
                    payload = snapshots == null ? null : boundedPayload(
                            source, snapshots, guestStdin);
                } catch (RuntimeException serializationFailure) {
                    failure = serializationFailure;
                    session.phase("serialize");
                } // try
                TraceResult result = session.result(
                        format.name().toLowerCase(java.util.Locale.ROOT),
                        payload, failure);
                PyTutorSerializer.getGson(pretty).toJson(result, System.out);
                System.out.println();
                if (!result.complete()) {
                    exitHandler.accept(result.status().equals("stopped") ? 3 : 1);
                } // if
            } // try
        } // runBounded

        /**
         * Reads sources with a byte cap before parsing or decoding them.
         * @param session Job session.
         * @param limits Source budgets.
         * @return UTF-8 source text.
         * @throws IOException On source read failure.
         */
        private String readBoundedSource(TraceSession session, TraceLimits limits)
                throws IOException {
            InputStream stream = input == null ? System.in : Files.newInputStream(input.toPath());
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    session.enforce((long) bytes.size() + count,
                            limits.sourceBytes(), "source_limit");
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
         * Compiles a job and captures its selected trace.
         *
         * @param source Source stream.
         * @param session Active session.
         * @param limits Validated limits.
         * @param guestStdin Standard input string for guest process.
         * @return Completed snapshots.
         * @throws Exception On compilation or tracing failure.
         */
        List<ExecutionSnapshot> executeBoundedSource(
                String source, TraceSession session, TraceLimits limits, String guestStdin)
                throws Exception {
            long files = CompilationHelper.DELIMITER_PATTERN.matcher(source).results().count();
            session.enforce(Math.max(1, files), limits.sourceFiles(), "source_file_limit");
            List<CompilationHelper.SourceFile> sources =
                    CompilationHelper.parseMultiFileStream(source);
            Optional<Path> root = job.inspection == InspectionPolicy.FIELDS ? Optional.empty()
                    : CompilationHelper.findSourceRoot(
                            CompilationHelper.findEntryPoint(sources).ast(), getInputPath());
            try (CompilationResult compiled = CompilationHelper.compile(source, root)) {
                List<CompilationUnit> units = discoverAllCompilationUnits(sources, root, root);
                session.phase("trace");
                if (allBreakpoints) {
                    if (job.breakpoints == null) {
                        Collection<Integer> lines =
                                DebugTraceHelper.getValidBreakpointLines(compiled);
                        DebugTraceHelper.traceChronological(
                                compiled, lines, units, true, guestStdin);
                    } else {
                        DebugTraceHelper.traceChronologicalWithSpecs(
                                compiled, parsedBreakpoints(), units, true, guestStdin);
                    } // if
                } else {
                    DebugTraceHelper.traceWithSpecs(
                            compiled, parsedBreakpoints(), units, guestStdin);
                } // if
                session.finishOutput();
                return session.snapshots();
            } // try
        } // executeBoundedSource

        /**
         * Creates an unchanged trace-format root inside the result envelope.
         *
         * @param source Source text.
         * @param snapshots Completed states in capture order.
         * @param guestStdin Standard input string for guest process.
         * @return Serializer model.
         */
        Object boundedPayload(
                String source, List<ExecutionSnapshot> snapshots, String guestStdin) {
            if (format == TraceFormat.MODERN) {
                return new ModernTraceSerializer(removeMainArgs, inlineStrings,
                        removeMethodThis, typeStyle).createTrace(source, guestStdin, snapshots);
            } // if
            return new PyTutorSerializer(removeMainArgs, inlineStrings,
                    removeMethodThis, typeStyle).createTrace(source, guestStdin, snapshots);
        } // boundedPayload

        /**
         * Discovers and parses all compilation units in the source files and source root.
         *
         * @param sourceFiles Input source files.
         * @param sourceRoot Optional source root path.
         * @param parserSourceRoot Parser type resolution path.
         * @return List of parsed CompilationUnits.
         */
        private List<CompilationUnit> discoverAllCompilationUnits(
                List<CompilationHelper.SourceFile> sourceFiles,
                Optional<Path> sourceRoot,
                Optional<Path> parserSourceRoot) {
            return App.discoverAllCompilationUnits(sourceFiles, sourceRoot, parserSourceRoot);
        } // discoverAllCompilationUnits

        /**
         * Runs and outputs modern JSON trace.
         *
         * @param source Source string.
         * @param compResult Compilation result.
         * @param allCus Compilation units.
         * @param guestStdin Standard input string for guest process.
         * @throws Exception On tracing error.
         */
        private void runModernTrace(
                String source,
                CompilationResult compResult,
                List<CompilationUnit> allCus,
                String guestStdin) throws Exception {
            ModernTraceSerializer serializer =
                    new ModernTraceSerializer(
                            removeMainArgs, inlineStrings, removeMethodThis, typeStyle);

            if (allBreakpoints) {
                List<ExecutionSnapshot> chronological = job.breakpoints != null
                        ? DebugTraceHelper.traceChronologicalWithSpecs(
                                compResult, parsedBreakpoints(), allCus, true, guestStdin)
                        : DebugTraceHelper.traceChronological(
                                compResult, DebugTraceHelper.getValidBreakpointLines(compResult),
                                allCus, true, guestStdin);
                cs1302.tracer.model.modern.Trace trace =
                        serializer.createTrace(source, guestStdin, chronological);
                emitTrace(ModernTraceSerializer.getGson().toJson(trace));
            } else if (job.breakpoints == null) {
                ExecutionSnapshot snapshot = DebugTraceHelper.trace(compResult, allCus, guestStdin);
                cs1302.tracer.model.modern.Trace trace =
                        serializer.createTrace(source, guestStdin, snapshot);
                emitTrace(ModernTraceSerializer.getGson().toJson(trace));
            } else {
                Map<Integer, List<ExecutionSnapshot>> snapshots = accumulateBreakpoints
                        ? DebugTraceHelper.traceWithSpecs(
                                compResult, parsedBreakpoints(), allCus, guestStdin)
                        : DebugTraceHelper.traceLatestWithSpecs(
                                compResult, parsedBreakpoints(), allCus, guestStdin);
                if (accumulateBreakpoints) {
                    cs1302.tracer.model.modern.Trace trace =
                            serializer.createBreakpointsTrace(source, guestStdin, snapshots);
                    emitTrace(ModernTraceSerializer.getGson().toJson(trace));
                } else {
                    Map<Integer, Object> latestSnapshots = new LinkedHashMap<>();
                    for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshots.entrySet()) {
                        List<ExecutionSnapshot> list = e.getValue();
                        if (list.size() == 1) {
                            latestSnapshots.put(e.getKey(), list.get(0));
                        } else {
                            latestSnapshots.put(e.getKey(), list);
                        } // if
                    } // for
                    cs1302.tracer.model.modern.Trace trace =
                            serializer.createBreakpointsTrace(source, guestStdin, latestSnapshots);
                    emitTrace(ModernTraceSerializer.getGson().toJson(trace));
                } // if
            } // if
        } // runModernTrace

        /**
         * Runs and outputs PyTutor JSON trace.
         *
         * @param source Source string.
         * @param compResult Compilation result.
         * @param allCus Compilation units.
         * @param guestStdin Standard input string for guest process.
         * @throws Exception On tracing error.
         */
        private void runPyTutorTrace(
                String source,
                CompilationResult compResult,
                List<CompilationUnit> allCus,
                String guestStdin) throws Exception {
            PyTutorSerializer serializer =
                    new PyTutorSerializer(
                            removeMainArgs, inlineStrings, removeMethodThis, typeStyle);

            if (allBreakpoints) {
                List<ExecutionSnapshot> chronological = job.breakpoints != null
                        ? DebugTraceHelper.traceChronologicalWithSpecs(
                                compResult, parsedBreakpoints(), allCus, true, guestStdin)
                        : DebugTraceHelper.traceChronological(
                                compResult, DebugTraceHelper.getValidBreakpointLines(compResult),
                                allCus, true, guestStdin);
                PyTutorTrace trace = serializer.createTrace(source, guestStdin, chronological);
                emitTrace(PyTutorSerializer.getGson(pretty).toJson(trace));
            } else if (job.breakpoints == null) {
                ExecutionSnapshot snapshot = DebugTraceHelper.trace(compResult, allCus, guestStdin);
                String pyTutorSnapshot = serializer.serialize(source, guestStdin, snapshot, pretty);
                emitTrace(pyTutorSnapshot);
            } else {
                Map<Integer, List<ExecutionSnapshot>> snapshots = accumulateBreakpoints
                        ? DebugTraceHelper.traceWithSpecs(
                                compResult, parsedBreakpoints(), allCus, guestStdin)
                        : DebugTraceHelper.traceLatestWithSpecs(
                                compResult, parsedBreakpoints(), allCus, guestStdin);
                if (accumulateBreakpoints) {
                    Map<Integer, List<PyTutorTrace>> pyTutorSnapshots =
                            snapshots.entrySet().stream()
                                     .collect(Collectors.toMap(
                                             Map.Entry::getKey,
                                             e -> e.getValue().stream()
                                                     .map(s -> serializer.createTrace(
                                                             source, guestStdin, s))
                                                     .toList()));
                    emitTrace(PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots));
                } else {
                    Map<Integer, Object> pyTutorSnapshots = new LinkedHashMap<>();
                    for (Map.Entry<Integer, List<ExecutionSnapshot>> e : snapshots.entrySet()) {
                        List<ExecutionSnapshot> list = e.getValue();
                        if (list.size() == 1) {
                            pyTutorSnapshots.put(e.getKey(), serializer.createTrace(
                                    source, guestStdin, list.get(0)));
                        } else {
                            pyTutorSnapshots.put(e.getKey(), list.stream()
                                    .map(s -> serializer.createTrace(source, guestStdin, s))
                                    .toList());
                        } // if
                    } // for
                    emitTrace(PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots));
                } // if
            } // if
        } // runPyTutorTrace

        /**
         * Publishes ordinary JSON only after confirming that no limit stopped the job.
         * @param json Complete serialized payload.
         */
        private void emitTrace(String json) {
            TraceSession.current().check();
            System.out.println(json);
        } // emitTrace
    } // Trace

    /** Run batch traces over NDJSON. */
    @Command(
            name = "batch-trace",
            description = "Execute multiple trace jobs over an NDJSON stream reusing "
                    + "persistent guest JVM sessions.",
            mixinStandardHelpOptions = true)
    public static class BatchTrace implements Runnable {

        @Option(
                names = {"--workers", "-w"},
                description = "Number of persistent worker sessions (default: ${DEFAULT-VALUE}).",
                defaultValue = "1")
        int workers = 1;

        @Option(
                names = {"--input", "-i"},
                description = "Input path to NDJSON file (defaults to stdin if omitted).")
        File input = null;

        @Option(
                names = {"--max-jobs-per-worker"},
                description = "Maximum jobs before recycling a worker process "
                        + "(default: ${DEFAULT-VALUE}).",
                defaultValue = "100")
        int maxJobsPerWorker = 100;

        @Option(
                names = {"--pretty", "-p"},
                description = "Pretty-print output JSON.")
        boolean pretty = false;

        public Consumer<Integer> exitHandler = System::exit;

        /** Constructs a BatchTrace command. */
        public BatchTrace() {} // BatchTrace

        @Override
        public void run() {
            try (BatchTraceService service = new BatchTraceService(
                    workers, maxJobsPerWorker, pretty)) {
                InputStream is = input != null
                        ? Files.newInputStream(input.toPath()) : System.in;
                try {
                    OutputStreamWriter writer = new OutputStreamWriter(
                            System.out, StandardCharsets.UTF_8);
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
    @Command(
            name = "list-breakpoints",
            description = "List the breakpoints available in the provided source file.",
            mixinStandardHelpOptions = true)
    static class ListBreakpoints extends CommandBase {

        @Option(
                names = {"--json", "-j"},
                description = "Output available breakpoints in JSON format.")
        boolean outputJson = false;

        @Override
        public void run() {
            String source = readInputFile();

            try {
                List<CompilationHelper.SourceFile> sourceFiles =
                        CompilationHelper.parseMultiFileStream(source);
                CompilationHelper.SourceFile entryFile =
                        CompilationHelper.findEntryPoint(sourceFiles);
                CompilationUnit preCu = entryFile.ast();
                Optional<Path> sourceRoot =
                        CompilationHelper.findSourceRoot(preCu, getInputPath());

                try (CompilationResult compilationResult =
                        CompilationHelper.compile(source, sourceRoot)) {
                    if (sourceFiles.size() > 1) {
                        Map<String, Set<Integer>> breakpointsByFile =
                                DebugTraceHelper.getValidBreakpointLinesByFile(compilationResult);
                        listMultiFileBreakpoints(sourceFiles, breakpointsByFile);
                    } else {
                        Collection<Integer> availableBreakpoints =
                                DebugTraceHelper.getValidBreakpointLines(compilationResult);
                        listSingleFileBreakpoints(source, availableBreakpoints);
                    } // if
                } // try
            } catch (Throwable cause) {
                System.err.println("Unable to list breakpoints!");
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
        private void listMultiFileBreakpoints(
                List<CompilationHelper.SourceFile> sourceFiles,
                Map<String, Set<Integer>> breakpointsByFile) {
            if (outputJson) {
                List<BreakpointEntry> output = new ArrayList<>();
                for (CompilationHelper.SourceFile sf : sourceFiles) {
                    String normPath = sf.relativePath().replace(File.separatorChar, '/');
                    Set<Integer> validLines = findValidLinesForFile(breakpointsByFile, sf);
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
                    CompilationHelper.SourceFile sf = sourceFiles.get(f);
                    String normPath = sf.relativePath().replace(File.separatorChar, '/');
                    sb.append("// --- ").append(normPath).append(" ---\n");
                    Set<Integer> validLines = findValidLinesForFile(breakpointsByFile, sf);
                    String[] fileLines = sf.content().split("\n");
                    int digitLength = ((int) Math.log10(Math.max(1, fileLines.length))) + 1;
                    for (int i = 0; i < fileLines.length; i++) {
                        if (validLines.contains(i + 1)) {
                            sb.append(Ansi.AUTO.string(String.format(
                                    "@|green b %" + digitLength + "d | |@", i + 1)));
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
         * Resolves valid breakpoint line numbers for a source file.
         *
         * @param breakpointsByFile File line map.
         * @param sf SourceFile.
         * @return Set of valid line numbers.
         */
        private Set<Integer> findValidLinesForFile(
                Map<String, Set<Integer>> breakpointsByFile,
                CompilationHelper.SourceFile sf) {
            String normPath = sf.relativePath().replace(File.separatorChar, '/');
            Set<Integer> valid = breakpointsByFile.getOrDefault(
                    normPath,
                    breakpointsByFile.getOrDefault(sf.relativePath(), Collections.emptySet()));
            if (valid.isEmpty()) {
                String simpleName = Path.of(sf.relativePath()).getFileName().toString();
                for (Map.Entry<String, Set<Integer>> entry : breakpointsByFile.entrySet()) {
                    if (entry.getKey().endsWith(simpleName)) {
                        return entry.getValue();
                    } // if
                } // for
            } // if
            return valid;
        } // findValidLinesForFile

        /**
         * Formats and prints breakpoints for single-file input.
         *
         * @param source Source code text.
         * @param availableBreakpoints Valid line numbers.
         */
        private void listSingleFileBreakpoints(
                String source, Collection<Integer> availableBreakpoints) {
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
                        sb.append(Ansi.AUTO.string(String.format(
                                "@|green b %" + digitLength + "d | |@", i + 1)));
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
    @Command(
            name = "show-licenses",
            description = "Show the licenses for projects used in this program and then exit.",
            mixinStandardHelpOptions = true)
    static class ShowLicenses implements Runnable {

        @Override
        public void run() {
            System.out.println(LicenseHelper.getLicenseText());
        } // run
    } // ShowLicenses
} // App
