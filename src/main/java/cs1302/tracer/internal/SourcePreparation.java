package cs1302.tracer.internal;

import org.apiguardian.api.API;

import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.classpath.BinaryTypeSolver;
import cs1302.tracer.internal.classpath.PreparedClassPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Owns compiled classes, source evidence, prepared dependencies, and archive views for a trace.
 *
 * @param compilation Compiled program owned by this preparation.
 * @param units Source evidence used to interpret runtime values.
 * @param dependencies Prepared dependency files owned by this job.
 * @param submitted Detached submitted text and identities, valid after preparation closes.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record SourcePreparation(CompilationHelper.CompilationResult compilation, List<CompilationUnit> units,
        PreparedClassPath dependencies, SubmittedSources submitted)
        implements AutoCloseable {

    /** Explicit source discovery policy. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public enum Discovery {

        /** Only submitted sources contribute evidence. */
        SUBMITTED_ONLY,
        /** Only submitted sources contribute evidence, resolved against their compiled source directory. */
        SUBMITTED_OR_COMPILED,
        /** Include neighboring source evidence. */
        NEIGHBORS,
        /** Include neighbors, using compiled source files when no original root exists. */
        NEIGHBORS_OR_COMPILED

    } // Discovery

    /** Creates a preparation with an unmodifiable list copy; mutable compilation units remain shared. */
    public SourcePreparation {
        units = List.copyOf(units);
        Objects.requireNonNull(submitted, "submitted");
    } // SourcePreparation

    /**
     * Constructs a preparation without additional binary resources.
     * @param compilation Owned compiled program.
     * @param units Source evidence.
     * @param submitted Detached submitted-source description.
     */
    public SourcePreparation(CompilationHelper.CompilationResult compilation, List<CompilationUnit> units,
            SubmittedSources submitted) {
        this(compilation, units, null, submitted);
    } // SourcePreparation

    /**
     * Prepares a submitted program and transfers ownership of its temporary files.
     *
     * @param source Submitted source text.
     * @param inputPath Optional original source path.
     * @param discovery Source discovery policy.
     * @return Owned compiled program and source evidence.
     * @throws IOException On compilation I/O failure.
     */
    public static SourcePreparation prepare(String source, Optional<Path> inputPath, Discovery discovery)
            throws IOException {
        return prepare(source, inputPath, discovery, TraceClassPath.empty());
    } // prepare

    /**
     * Prepares sources and binary dependencies for one job.
     * @param source Submitted source text.
     * @param inputPath Original source location.
     * @param discovery Permitted source evidence.
     * @param classPath Deferred dependency configuration.
     * @return Owned preparation.
     * @throws IOException On dependency or compilation I/O failure.
     */
    public static SourcePreparation prepare(String source, Optional<Path> inputPath, Discovery discovery,
            TraceClassPath classPath) throws IOException {
        PreparedClassPath dependencies = PreparedClassPath.prepare(classPath);
        try {
            return prepareSource(CompilationHelper.parseSources(source), inputPath, discovery, dependencies);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(dependencies, failure);
            throw failure;
        } // try
    } // prepare

    /**
     * Prepares sources already parsed by a caller whose source errors precede dependency preparation.
     * @param sources Correlated parse result, without debug-path ambiguity validation.
     * @param inputPath Original source location.
     * @param discovery Permitted source evidence.
     * @param classPath Deferred dependency configuration.
     * @return Owned compilation and detached submitted-source description.
     * @throws IOException On dependency or compilation I/O failure.
     */
    public static SourcePreparation prepareParsed(CompilationHelper.ParsedSources sources, Optional<Path> inputPath,
            Discovery discovery, TraceClassPath classPath) throws IOException {
        PreparedClassPath dependencies = PreparedClassPath.prepare(classPath);
        try {
            return prepareSource(sources, inputPath, discovery, dependencies);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(dependencies, failure);
            throw failure;
        } // try
    } // prepareParsed

    /**
     * Compiles and analyzes after dependency preparation has succeeded.
     * @param parsed Correlated parsed submitted files and detached identities.
     * @param inputPath Original source location.
     * @param discovery Source discovery policy.
     * @param dependencies Resolved dependency ownership.
     * @return Complete preparation.
     * @throws IOException On compilation failure.
     */
    private static SourcePreparation prepareSource(CompilationHelper.ParsedSources parsed, Optional<Path> inputPath,
            Discovery discovery, PreparedClassPath dependencies) throws IOException {
        List<CompilationHelper.SourceFile> sources = parsed.files();
        Optional<Path> root = discovery == Discovery.NEIGHBORS || discovery == Discovery.NEIGHBORS_OR_COMPILED
                ? CompilationHelper.findSourceRoot(CompilationHelper.findEntryPoint(sources).ast(), inputPath)
                : Optional.empty();
        return CompilationHelper.compile(sources, root, dependencies, compiled -> {
            List<Path> analysisRoots = new ArrayList<>();
            if (!dependencies.entries().isEmpty()) {
                analysisRoots.add(compiled.classPath());
                analysisRoots.addAll(dependencies.binaryRoots());
            } // if
            return new SourcePreparation(compiled, discoverAllCompilationUnits(sources, root,
                        (discovery == Discovery.SUBMITTED_OR_COMPILED
                                || discovery == Discovery.NEIGHBORS_OR_COMPILED) && root.isEmpty()
                                ? Optional.of(compiled.classPath()) : root, analysisRoots), dependencies,
                    parsed.submitted());
        });
    } // prepareSource

    /**
     * Releases an incompletely prepared resource while retaining the primary failure.
     * @param resource Resource to release.
     * @param failure Original failure.
     */
    static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception cleanup) {
            failure.addSuppressed(cleanup);
        } // try
    } // closeAfterFailure

    /**
     * Parse the given Java source code string with optional source root for type resolution.
     *
     * @param source The Java source code to parse.
     * @param sourceRoot The root directory where source files for the program are located.
     * @return The parsed Java source code.
     * @throws IllegalArgumentException If parsing failed.
     */
    public static CompilationUnit parseSource(String source, Optional<Path> sourceRoot) {
        return parseSource(source, sourceRoot, List.of());
    } // parseSource

    /**
     * Parses source with a job-local bytecode resolver.
     * @param source Source text.
     * @param sourceRoot Optional source evidence root.
     * @param binaryRoots Ordered binary directories.
     * @return Parsed compilation unit.
     */
    private static CompilationUnit parseSource(String source, Optional<Path> sourceRoot, List<Path> binaryRoots) {
        CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
        combinedTypeSolver.add(new ReflectionTypeSolver());
        if (!binaryRoots.isEmpty()) {
            combinedTypeSolver.add(new BinaryTypeSolver(binaryRoots));
        } // if
        sourceRoot.ifPresent(sr -> combinedTypeSolver.add(new JavaParserTypeSolver(sr)));
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

        ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver)
                .setLanguageLevel(LanguageLevel.CURRENT);

        CompilationUnit unit = new com.github.javaparser.JavaParser(config).parse(source).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Failed to parse Java source with symbol solver"));
        unit.setData(cs1302.tracer.internal.trace.AstTypeResolver.TYPE_SOLVER, combinedTypeSolver);
        return unit;
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
        return discoverAllCompilationUnits(sourceFiles, sourceRoot, parserSourceRoot, List.of());
    } // discoverAllCompilationUnits

    /**
     * Discovers source evidence against the prepared binary directories.
     * @param sourceFiles Submitted sources.
     * @param sourceRoot Optional neighboring-source root.
     * @param parserSourceRoot Source resolution root.
     * @param binaryRoots Version-selected binary directories.
     * @return Source evidence.
     */
    private static List<CompilationUnit> discoverAllCompilationUnits(List<CompilationHelper.SourceFile> sourceFiles,
            Optional<Path> sourceRoot, Optional<Path> parserSourceRoot, List<Path> binaryRoots) {
        List<CompilationUnit> allCus = new ArrayList<>();
        Set<String> parsedPaths = new HashSet<>();
        for (CompilationHelper.SourceFile sf : sourceFiles) {
            allCus.add(parseSource(sf.content(), parserSourceRoot, binaryRoots));
            parsedPaths.add(sf.relativePath().replace('\\', '/'));
        } // for
        if (sourceRoot.isPresent()) {
            try (Stream<Path> stream = Files.walk(sourceRoot.get())) {
                List<Path> javaFiles = stream.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                        .toList();
                for (Path p : javaFiles) {
                    String rel = sourceRoot.get().relativize(p).toString().replace('\\', '/');
                    if (!parsedPaths.contains(rel)) {
                        try {
                            allCus.add(parseSource(Files.readString(p), parserSourceRoot, binaryRoots));
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

    /** Releases the compiled files after tracing has finished. */
    @Override
    public void close() {
        try (this.dependencies) {
            this.compilation.close();
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to clean binary dependencies", failure);
        } // try
    } // close

} // SourcePreparation
