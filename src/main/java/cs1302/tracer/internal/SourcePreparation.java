package cs1302.tracer.internal;

import org.apiguardian.api.API;

import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Owns compiled classes and source evidence for the lifetime of a trace.
 *
 * @param compilation Compiled program owned by this preparation.
 * @param units Source evidence used to interpret runtime values.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record SourcePreparation(CompilationHelper.CompilationResult compilation, List<CompilationUnit> units)
        implements AutoCloseable {

    /** Explicit source discovery policy. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public enum Discovery {
        /** Only submitted sources contribute evidence. */
        SUBMITTED_ONLY,
        /** Include neighboring source evidence. */
        NEIGHBORS,
        /** Include neighbors, using compiled source files when no original root exists. */
        NEIGHBORS_OR_COMPILED
    } // Discovery

    /** Creates a preparation with an immutable source list. */
    public SourcePreparation {
        units = List.copyOf(units);
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
        List<CompilationHelper.SourceFile> sources = CompilationHelper.parseMultiFileStream(source);
        Optional<Path> root = discovery == Discovery.SUBMITTED_ONLY ? Optional.empty()
                : CompilationHelper.findSourceRoot(CompilationHelper.findEntryPoint(sources).ast(), inputPath);
        return CompilationHelper.compile(sources, root, compiled -> new SourcePreparation(compiled,
                discoverAllCompilationUnits(sources, root,
                        discovery == Discovery.NEIGHBORS_OR_COMPILED && root.isEmpty()
                                ? Optional.of(compiled.classPath()) : root)));
    } // prepare

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
        List<CompilationUnit> allCus = new ArrayList<>();
        Set<String> parsedPaths = new HashSet<>();
        for (CompilationHelper.SourceFile sf : sourceFiles) {
            allCus.add(parseSource(sf.content(), parserSourceRoot));
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

    /** Releases the compiled files after tracing has finished. */
    @Override
    public void close() {
        this.compilation.close();
    } // close
} // SourcePreparation
