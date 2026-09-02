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
import cs1302.tracer.model.BreakpointEntry;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.TypeStyle;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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

/** Entry point for the tracer program. */
@Command(
        name = "code-tracer",
        description = "Trace Java program execution and inspect memory states.",
        mixinStandardHelpOptions = true,
        versionProvider = App.PropertiesVersionProvider.class)
public class App {

    /** Provides version string resolved from Maven resource filtering at build time. */
    public static class PropertiesVersionProvider implements CommandLine.IVersionProvider {

        /** Constructs a new PropertiesVersionProvider. */
        public PropertiesVersionProvider() {} // PropertiesVersionProvider

        @Override
        public String[] getVersion() {
            try (InputStream is = App.class.getResourceAsStream(
                    "/cs1302/tracer/version.properties")) {
                if (is != null) {
                    Properties props = new Properties();
                    props.load(is);
                    return new String[] {props.getProperty("version", "development")};
                } // if
            } catch (IOException ignored) {
                // fallback to development
            } // try
            return new String[] {"development"};
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

        Consumer<Integer> exitHandler = System::exit;

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
    } // CommandBase

    /** Run a trace. */
    @Command(
        name = "trace",
        description = "Generate an execution trace for a Java program.",
        mixinStandardHelpOptions = true)
    static class Trace extends CommandBase {

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

        @Option(
                names = {"--breakpoints", "-b"},
                description = "Breakpoints at which to take snapshots.")
        List<Integer> breakpoints = null;

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
                    Optional<Path> parserSourceRoot = sourceRoot.isPresent()
                            ? sourceRoot
                            : Optional.of(compilationResult.classPath());
                    List<CompilationUnit> allCus = discoverAllCompilationUnits(
                            sourceFiles, sourceRoot, parserSourceRoot);

                    if (format == TraceFormat.MODERN) {
                        runModernTrace(source, compilationResult, allCus);
                    } else {
                        runPyTutorTrace(source, compilationResult, allCus);
                    } // if
                } // try
            } catch (Throwable cause) {
                System.err.println("Unable to generate trace!");
                if (verbose) {
                    cause.printStackTrace();
                } // if
                exitHandler.accept(1);
            } // try
        } // run

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

        /**
         * Runs and outputs modern JSON trace.
         *
         * @param source Source string.
         * @param compResult Compilation result.
         * @param allCus Compilation units.
         * @throws Exception On tracing error.
         */
        private void runModernTrace(
                String source,
                CompilationResult compResult,
                List<CompilationUnit> allCus) throws Exception {
            ModernTraceSerializer serializer =
                    new ModernTraceSerializer(
                            removeMainArgs, inlineStrings, removeMethodThis, typeStyle);

            if (allBreakpoints) {
                Collection<Integer> targetLines = breakpoints != null
                        ? breakpoints
                        : DebugTraceHelper.getValidBreakpointLines(compResult);
                List<ExecutionSnapshot> chronological =
                        DebugTraceHelper.traceChronological(compResult, targetLines, allCus, true);
                cs1302.tracer.model.modern.Trace trace =
                        serializer.createTrace(source, chronological);
                System.out.println(ModernTraceSerializer.getGson().toJson(trace));
            } else if (breakpoints == null) {
                ExecutionSnapshot snapshot = DebugTraceHelper.trace(compResult, allCus);
                cs1302.tracer.model.modern.Trace trace =
                        serializer.createTrace(source, snapshot);
                System.out.println(ModernTraceSerializer.getGson().toJson(trace));
            } else {
                Map<Integer, List<ExecutionSnapshot>> snapshots =
                        DebugTraceHelper.trace(compResult, breakpoints, allCus);
                if (accumulateBreakpoints) {
                    cs1302.tracer.model.modern.Trace trace =
                            serializer.createBreakpointsTrace(source, snapshots);
                    System.out.println(ModernTraceSerializer.getGson().toJson(trace));
                } else {
                    Map<Integer, ExecutionSnapshot> singlePerBp = snapshots.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey, e -> e.getValue().getLast()));
                    cs1302.tracer.model.modern.Trace trace =
                            serializer.createBreakpointsTrace(source, singlePerBp);
                    System.out.println(ModernTraceSerializer.getGson().toJson(trace));
                } // if
            } // if
        } // runModernTrace

        /**
         * Runs and outputs PyTutor JSON trace.
         *
         * @param source Source string.
         * @param compResult Compilation result.
         * @param allCus Compilation units.
         * @throws Exception On tracing error.
         */
        private void runPyTutorTrace(
                String source,
                CompilationResult compResult,
                List<CompilationUnit> allCus) throws Exception {
            PyTutorSerializer serializer =
                    new PyTutorSerializer(
                            removeMainArgs, inlineStrings, removeMethodThis, typeStyle);

            if (allBreakpoints) {
                Collection<Integer> targetLines = breakpoints != null
                        ? breakpoints
                        : DebugTraceHelper.getValidBreakpointLines(compResult);
                List<ExecutionSnapshot> chronological =
                        DebugTraceHelper.traceChronological(compResult, targetLines, allCus, true);
                PyTutorTrace trace = serializer.createTrace(source, chronological);
                System.out.println(PyTutorSerializer.getGson(pretty).toJson(trace));
            } else if (breakpoints == null) {
                ExecutionSnapshot snapshot = DebugTraceHelper.trace(compResult, allCus);
                String pyTutorSnapshot = serializer.serialize(source, snapshot, pretty);
                System.out.println(pyTutorSnapshot);
            } else {
                Map<Integer, List<ExecutionSnapshot>> snapshots =
                        DebugTraceHelper.trace(compResult, breakpoints, allCus);
                if (accumulateBreakpoints) {
                    Map<Integer, List<PyTutorTrace>> pyTutorSnapshots =
                            snapshots.entrySet().stream()
                                     .collect(Collectors.toMap(
                                             Map.Entry::getKey,
                                             e -> e.getValue().stream()
                                                     .map(s -> serializer.createTrace(source, s))
                                                     .toList()));
                    System.out.println(PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots));
                } else {
                    Map<Integer, PyTutorTrace> pyTutorSnapshots = snapshots.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> serializer.createTrace(source, e.getValue().getLast())));
                    System.out.println(PyTutorSerializer.getGson(pretty).toJson(pyTutorSnapshots));
                } // if
            } // if
        } // runPyTutorTrace
    } // Trace

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
