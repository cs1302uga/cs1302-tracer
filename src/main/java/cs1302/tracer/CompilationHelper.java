package cs1302.tracer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithPublicModifier;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * A collection of methods that are used to compile a Java program.
 *
 * <p>Normative References:
 * <ul>
 *   <li>JSR-199: The Java Compiler API (package {@code javax.tools}).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;7
 *       (Packages and Compilation Units).
 * </ul>
 */
public class CompilationHelper {

    /**
     * Private constructor to prevent direct instantiation of utility class.
     */
    private CompilationHelper() {} // CompilationHelper

    /** Regular expression pattern for matching file delimiters in multi-file source streams. */
    public static final Pattern DELIMITER_PATTERN =
            Pattern.compile("^//\\s*[-=]{3,}\\s*(.*?\\.java)\\s*[-=]{3,}\\s*$", Pattern.MULTILINE);

    /**
     * Represents an individual source file from a single-file or multi-file input stream.
     *
     * @param relativePath Relative path of the source file.
     * @param content Source code content.
     * @param ast Parsed CompilationUnit AST.
     */
    public record SourceFile(
            String relativePath,
            String content,
            CompilationUnit ast) {} // SourceFile

    /**
     * Parse a single-file or multi-file Java stream demarcated by comment delimiters.
     *
     * @param rawInput The raw input string from stdin or file.
     * @return A list of {@link SourceFile} objects parsed from the input stream.
     */
    public static List<SourceFile> parseMultiFileStream(String rawInput) {
        if (rawInput == null) {
            throw new IllegalArgumentException("Input source cannot be null");
        } // if
        Matcher matcher = DELIMITER_PATTERN.matcher(rawInput);
        List<Integer> delimiterStarts = new ArrayList<>();
        List<Integer> delimiterEnds = new ArrayList<>();
        List<String> filePaths = new ArrayList<>();

        while (matcher.find()) {
            delimiterStarts.add(matcher.start());
            delimiterEnds.add(matcher.end());
            filePaths.add(matcher.group(1).trim());
        } // while

        JavaParser parser = new JavaParser(
                new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));

        if (filePaths.isEmpty()) {
            ParseResult<CompilationUnit> parseResult = parser.parse(rawInput);
            if (!parseResult.isSuccessful()) {
                throw new IllegalArgumentException(
                        "Parsing failed with the following errors: "
                                + parseResult.getProblems().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(", ", "[", "]")));
            } // if
            CompilationUnit cu = parseResult.getResult().get();
            String relPath = findTopLevelDeclarationBinaryName(cu).replace('.', File.separatorChar)
                    + ".java";
            return List.of(new SourceFile(relPath, rawInput, cu));
        } // if

        return parseMultiFileParts(
                rawInput, delimiterStarts, delimiterEnds, filePaths, parser);
    } // parseMultiFileStream

    /**
     * Parses the individual parts of a multi-file stream.
     *
     * @param rawInput Raw input string.
     * @param starts Delimiter start positions.
     * @param ends Delimiter end positions.
     * @param filePaths List of file paths.
     * @param parser Configured JavaParser.
     * @return List of parsed SourceFiles.
     */
    private static List<SourceFile> parseMultiFileParts(
            String rawInput,
            List<Integer> starts,
            List<Integer> ends,
            List<String> filePaths,
            JavaParser parser) {
        List<SourceFile> result = new ArrayList<>();
        for (int i = 0; i < filePaths.size(); i++) {
            int contentStart = ends.get(i);
            int contentEnd = (i + 1 < starts.size()) ? starts.get(i + 1) : rawInput.length();
            String content = rawInput.substring(contentStart, contentEnd);
            if (content.startsWith("\r\n")) {
                content = content.substring(2);
            } else if (content.startsWith("\n")) {
                content = content.substring(1);
            } // if

            ParseResult<CompilationUnit> parseResult = parser.parse(content);
            if (!parseResult.isSuccessful()) {
                throw new IllegalArgumentException(
                        "Parsing failed for " + filePaths.get(i) + " with errors: "
                                + parseResult.getProblems().stream()
                                .map(Object::toString)
                                .collect(Collectors.joining(", ", "[", "]")));
            } // if
            CompilationUnit cu = parseResult.getResult().get();
            String rawPath = filePaths.get(i).replace('\\', '/');
            String normalizedRelPath = normalizePath(rawPath, cu);
            result.add(new SourceFile(normalizedRelPath, content, cu));
        } // for
        return result;
    } // parseMultiFileParts

    /**
     * Normalizes a relative file path based on package declaration.
     *
     * @param rawPath Raw path string.
     * @param cu CompilationUnit.
     * @return Normalized relative path.
     */
    private static String normalizePath(String rawPath, CompilationUnit cu) {
        if (rawPath.contains("/")) {
            return rawPath.replace('/', File.separatorChar);
        } // if
        Optional<String> pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString());
        if (pkg.isPresent()) {
            return pkg.get().replace('.', File.separatorChar) + File.separatorChar + rawPath;
        } // if
        return rawPath;
    } // normalizePath

    /**
     * Find the entry point source file containing the main method among multiple files.
     *
     * @param sourceFiles The list of source files to search.
     * @return The source file containing {@code public static void main(String[] args)}.
     */
    public static SourceFile findEntryPoint(List<SourceFile> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No source files provided");
        } // if
        for (SourceFile sf : sourceFiles) {
            if (!sf.ast().findAll(
                    MethodDeclaration.class, CompilationHelper::isMainMethod).isEmpty()) {
                return sf;
            } // if
        } // for
        return sourceFiles.getFirst();
    } // findEntryPoint

    /**
     * Combine all compilation units from a multi-file stream into a single compilation unit.
     *
     * @param sourceFiles The list of source files.
     * @return A single combined {@link CompilationUnit}.
     */
    public static CompilationUnit combineCompilationUnits(List<SourceFile> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("No source files provided");
        } // if
        if (sourceFiles.size() == 1) {
            return sourceFiles.getFirst().ast();
        } // if
        CompilationUnit combined = new CompilationUnit();
        SourceFile entry = findEntryPoint(sourceFiles);
        entry.ast().getPackageDeclaration().ifPresent(combined::setPackageDeclaration);
        Set<String> importNames = new HashSet<>();
        for (SourceFile sf : sourceFiles) {
            for (ImportDeclaration imp : sf.ast().getImports()) {
                if (importNames.add(imp.getNameAsString())) {
                    combined.addImport(imp);
                } // if
            } // for
            for (TypeDeclaration<?> type : sf.ast().getTypes()) {
                combined.addType(type.clone());
            } // for
        } // for
        return combined;
    } // combineCompilationUnits

    /**
     * Automatically infer the source root directory for a given compilation unit and path.
     *
     * @param cu The parsed compilation unit.
     * @param inputPath The optional path to the input source file.
     * @return An Optional containing the inferred source root directory, if discovered.
     */
    public static Optional<Path> findSourceRoot(CompilationUnit cu, Optional<Path> inputPath) {
        Optional<String> pkgName = cu.getPackageDeclaration().map(p -> p.getNameAsString());
        if (pkgName.isEmpty()) {
            return Optional.empty();
        } // if

        String pkgPathStr = pkgName.get().replace('.', File.separatorChar);

        if (inputPath.isPresent()) {
            Path absInput = inputPath.get().toAbsolutePath().normalize();
            Path parent = absInput.getParent();
            if (parent == null) {
                return Optional.empty();
            } // if
            String[] parts = pkgName.get().split("\\.");
            Path curr = parent;
            for (int i = parts.length - 1; i >= 0; i--) {
                if (curr != null && curr.getFileName() != null
                        && curr.getFileName().toString().equals(parts[i])) {
                    curr = curr.getParent();
                } else {
                    return Optional.empty();
                } // if
            } // for
            if (curr != null && Files.isDirectory(curr.resolve(pkgPathStr))) {
                return Optional.of(curr);
            } // if
            return Optional.empty();
        } else {
            List<Path> candidates = List.of(
                    Path.of("."),
                    Path.of("src/main/java"),
                    Path.of("src"));
            for (Path candidate : candidates) {
                Path pkgDir = candidate.resolve(pkgPathStr);
                if (Files.isDirectory(pkgDir)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                } // if
            } // for
            return Optional.empty();
        } // if
    } // findSourceRoot

    /**
     * Compile a Java program.
     *
     * @param javaSource The Java program to compile.
     * @return The CompilationResult for this compilation.
     * @throws IOException If an I/O error occurs.
     * @throws IllegalArgumentException If the Java program failed to compile.
     */
    public static CompilationResult compile(String javaSource) throws IOException {
        return compile(javaSource, Optional.empty());
    } // compile

    /**
     * Compile a Java program with an optional source root for resolving dependencies.
     *
     * @param javaSource The Java program to compile.
     * @param sourceRoot The optional source root directory containing package dependencies.
     * @return The CompilationResult for this compilation.
     * @throws IOException If an I/O error occurs.
     * @throws IllegalArgumentException If the Java program failed to compile.
     */
    public static CompilationResult compile(String javaSource, Optional<Path> sourceRoot)
            throws IOException {
        List<SourceFile> sourceFiles = parseMultiFileStream(javaSource);
        boolean previewEnabled =
                detectPreviewUsage(sourceFiles.stream().map(SourceFile::ast).toList());
        Path workingDir = Files.createTempDirectory("code-tracer");
        try {
            List<File> files = writeSourceFiles(workingDir, sourceFiles);
            Set<String> classes = compileFiles(workingDir, files, sourceRoot, previewEnabled);
            SourceFile entryPoint = findEntryPoint(sourceFiles);
            MethodDeclaration mainMethod = findMain(entryPoint.ast());
            String mainClass = String.join(".", getAncestorFqn(entryPoint.ast(), mainMethod));
            return new CompilationResult(workingDir, classes, mainClass,
                    sourceRoot.isPresent() ? sourceRoot : Optional.of(workingDir),
                    previewEnabled);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                deleteWorkingDirectory(workingDir);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            } // try
            throw failure;
        } // try
    } // compile

    /**
     * Compiles source files while owning the compiler's file manager.
     * @param workingDir Output directory.
     * @param files Source files.
     * @param sourceRoot Optional dependency root.
     * @param previewEnabled Whether preview features are enabled.
     * @return Compiled binary names.
     * @throws IOException On file manager failure.
     */
    private static Set<String> compileFiles(
            Path workingDir,
            List<File> files,
            Optional<Path> sourceRoot,
            boolean previewEnabled) throws IOException {
        Set<String> classes = new HashSet<>();
        JavaCompiler compiler = Objects.requireNonNull(
                ToolProvider.getSystemJavaCompiler(), "Could not get Java compiler");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standard =
                compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaFileManager forwarding = new ForwardingJavaFileManager<>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(
                        Location location, String className, Kind kind, FileObject sibling)
                        throws IOException {
                    classes.add(className);
                    return super.getJavaFileForOutput(location, className, kind, sibling);
                } // getJavaFileForOutput
            };
            boolean success = compiler.getTask(null, forwarding, diagnostics,
                    buildCompilerOptions(workingDir, sourceRoot, previewEnabled), null,
                    standard.getJavaFileObjectsFromFiles(files)).call();
            if (!success) {
                throw new IllegalArgumentException(
                        "Compilation of provided Java source code failed"
                        + " with the following messages:"
                        + "\n" + diagnostics.getDiagnostics().stream()
                        .map(Object::toString).collect(Collectors.joining("\n")));
            } // if
        } // try
        return classes;
    } // compileFiles

    /**
     * Writes source files to a destination directory.
     *
     * @param workingDir Target directory.
     * @param sourceFiles List of source files.
     * @return List of created File objects.
     * @throws IOException On write error.
     */
    private static List<File> writeSourceFiles(
            Path workingDir, List<SourceFile> sourceFiles) throws IOException {
        List<File> allSourceFiles = new ArrayList<>();
        Set<Path> destinations = new HashSet<>();
        // Validate the complete input before any write into this private directory.
        for (SourceFile sf : sourceFiles) {
            String path = sf.relativePath().replace('\\', '/');
            Path relative = Path.of(path).normalize();
            Path target = workingDir.resolve(relative).normalize();
            if (relative.isAbsolute() || path.contains(":") || !target.startsWith(workingDir)
                    || !relative.toString().endsWith(".java")
                    || !destinations.add(target)) {
                throw new IllegalArgumentException("Invalid or duplicate source path: " + path);
            } // if
            allSourceFiles.add(target.toFile());
        } // for
        for (int i = 0; i < sourceFiles.size(); i++) {
            Path target = allSourceFiles.get(i).toPath();
            Files.createDirectories(target.getParent());
            Files.writeString(target, sourceFiles.get(i).content(),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } // for
        return allSourceFiles;
    } // writeSourceFiles

    /**
     * Builds javac compiler command-line options.
     *
     * @param workingDir Temporary compilation directory.
     * @param sourceRoot Optional source root.
     * @param previewEnabled Whether preview features are enabled.
     * @return List of option strings.
     */
    private static List<String> buildCompilerOptions(
            Path workingDir, Optional<Path> sourceRoot, boolean previewEnabled) {
        List<String> compilerOptions = new ArrayList<>();
        compilerOptions.add("-g");
        if (previewEnabled) {
            compilerOptions.add("--enable-preview");
            compilerOptions.add("-source");
            compilerOptions.add(String.valueOf(Runtime.version().feature()));
        } // if
        compilerOptions.add("-d");
        compilerOptions.add(workingDir.toAbsolutePath().toString());
        compilerOptions.add("-sourcepath");
        String sourcePathStr = workingDir.toAbsolutePath().toString();
        if (sourceRoot.isPresent() && Files.isDirectory(sourceRoot.get())) {
            sourcePathStr += File.pathSeparator + sourceRoot.get().toAbsolutePath().toString();
        } // if
        compilerOptions.add(sourcePathStr);
        compilerOptions.add("-classpath");
        compilerOptions.add(sourcePathStr);
        return compilerOptions;
    } // buildCompilerOptions

    /**
     * Checks whether the given compilation units use preview language or library features
     * such as java.lang.IO or instance main methods.
     *
     * @param compilationUnits List of compilation units to inspect.
     * @return True if preview features are detected, false otherwise.
     */
    public static boolean detectPreviewUsage(List<CompilationUnit> compilationUnits) {
        if (compilationUnits == null) {
            return false;
        } // if
        for (CompilationUnit cu : compilationUnits) {
            if (detectPreviewUsage(cu)) {
                return true;
            } // if
        } // for
        return false;
    } // detectPreviewUsage

    /**
     * Checks whether a single compilation unit uses preview language or library features.
     *
     * @param cu The compilation unit to inspect.
     * @return True if preview features are detected.
     */
    public static boolean detectPreviewUsage(CompilationUnit cu) {
        if (cu == null) {
            return false;
        } // if
        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (name.equals("java.lang.IO") || name.equals("java.io.IO")
                    || name.startsWith("java.lang.IO.") || name.startsWith("java.io.IO.")) {
                return true;
            } // if
        } // for
        List<MethodCallExpr> calls = cu.findAll(MethodCallExpr.class);
        for (MethodCallExpr call : calls) {
            if (call.getScope().isPresent()) {
                String scope = call.getScope().get().toString();
                if (scope.equals("IO") || scope.equals("java.lang.IO")
                        || scope.equals("java.io.IO")) {
                    return true;
                } // if
            } // if
        } // for
        List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
        for (MethodDeclaration m : methods) {
            if (m.getNameAsString().equals("main") && (!m.isStatic() || !m.isPublic())) {
                return true;
            } // if
        } // for
        return false;
    } // detectPreviewUsage

    /**
     * Deletes owned compilation files without hiding cleanup failures.
     * @param directory Private working directory.
     * @throws IOException On cleanup failure.
     */
    private static void deleteWorkingDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        } // if
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            } // for
        } // try
    } // deleteWorkingDirectory

    /**
     * Find the binary name of the single top-level declaration in a compilation unit.
     *
     * @param compilationUnit The compilation unit to search in.
     * @return The binary name of the compilation unit's top-level declaration.
     * @throws IllegalArgumentException If top-level count is not 1.
     */
    private static String findTopLevelDeclarationBinaryName(CompilationUnit compilationUnit) {
        List<Node> topLevelNodes = new ArrayList<>();
        new Node.DirectChildrenIterator(compilationUnit).forEachRemaining(topLevelNodes::add);
        List<TypeDeclaration<?>> topLevelPublicDeclarations =
                topLevelNodes.stream()
                        .filter(n -> n instanceof TypeDeclaration)
                        .map(n -> (TypeDeclaration<?>) n)
                        .filter(NodeWithPublicModifier::isPublic)
                        .collect(Collectors.toList());

        if (topLevelPublicDeclarations.size() != 1) {
            String lines = "[" + String.join(
                    ", ",
                    topLevelPublicDeclarations.stream()
                            .filter(m -> m.getBegin().isPresent())
                            .map(m -> m.getBegin().get().line)
                            .map(Object::toString)
                            .toArray(String[]::new))
                    + "]";
            throw new IllegalArgumentException(
                    String.format(
                            "Java source code must have exactly one public top-level type "
                                    + "declaration. Found %d such declarations on lines %s.",
                            topLevelPublicDeclarations.size(), lines));
        } // if

        TypeDeclaration<?> fileClass = topLevelPublicDeclarations.get(0);
        List<String> fqn = getAncestorFqn(compilationUnit, fileClass);
        fqn.add(fileClass.getNameAsString());

        return String.join(".", fqn);
    } // findTopLevelDeclarationBinaryName

    /**
     * Get the FQN of a declaration's direct ancestor in a compilation unit as a list.
     *
     * @param compilationUnit The declaration's compilation unit.
     * @param declaration The declaration.
     * @return The FQN of the declaration's direct ancestor.
     */
    private static List<String> getAncestorFqn(
            CompilationUnit compilationUnit, BodyDeclaration<?> declaration) {
        Iterator<Node> parents = new Node.ParentsVisitor(declaration);
        List<String> ancestorNames = new LinkedList<>();
        while (parents.hasNext()) {
            Node parent = parents.next();
            if (!(parent instanceof TypeDeclaration<?> parentDecl)) {
                continue;
            } // if
            ancestorNames.addFirst(parentDecl.getNameAsString());
        } // while

        compilationUnit.getPackageDeclaration().ifPresent(p -> {
            ancestorNames.addAll(0, Arrays.asList(p.getNameAsString().split("\\.")));
        });

        return ancestorNames;
    } // getAncestorFqn

    /**
     * Check whether a method declaration matches standard main method signature.
     *
     * @param m The method declaration to check.
     * @return True if the method is a main method.
     */
    public static boolean isMainMethod(MethodDeclaration m) {
        boolean isNamedMain = m.getNameAsString().equals("main");
        boolean hasVoidReturn = m.getType().isVoidType();
        boolean hasStringArrArg = m.getParameterByType(String[].class)
                .or(() -> m.getParameterByType("java.lang.String[]"))
                .isPresent();
        boolean hasStringVarargsArg = m.getParameterByType(String.class)
                .or(() -> m.getParameterByType("java.lang.String"))
                .map(p -> p.isVarArgs())
                .orElse(false);
        boolean hasOneArg = m.getParameters().size() == 1;
        boolean standardMain = m.isPublic()
                && m.isStatic()
                && hasVoidReturn
                && isNamedMain
                && (hasStringArrArg ^ hasStringVarargsArg)
                && hasOneArg;
        if (standardMain) {
            return true;
        } // if
        boolean instanceMain = isNamedMain
                && hasVoidReturn
                && (m.getParameters().isEmpty()
                        || (hasOneArg && (hasStringArrArg ^ hasStringVarargsArg)));
        return instanceMain;
    } // isMainMethod

    /**
     * Locate the main method in the provided compilation unit.
     *
     * @param compilationUnit parsed Java source code
     * @return the declaration node for the main method
     * @throws IllegalArgumentException if main method count != 1
     */
    private static MethodDeclaration findMain(CompilationUnit compilationUnit) {
        List<MethodDeclaration> mainMethods =
                compilationUnit.findAll(MethodDeclaration.class, CompilationHelper::isMainMethod);

        if (mainMethods.size() != 1) {
            String lines = "[" + String.join(
                    ", ",
                    mainMethods.stream()
                            .filter(m -> m.getBegin().isPresent())
                            .map(m -> m.getBegin().get().line)
                            .map(Object::toString)
                            .toArray(String[]::new))
                    + "]";
            throw new IllegalArgumentException(
                    String.format(
                            "Java source code must have exactly one main method. "
                                    + "Found %d main methods on lines %s.",
                            mainMethods.size(), lines));
        } // if

        return mainMethods.getFirst();
    } // findMain

    /**
     * A collection of information from the successful compilation of a Java program.
     *
     * @param classPath Root of the class path where compiled classes were output.
     * @param compiledClassNames Binary names of the classes that were compiled.
     * @param mainClass Binary name of the class that contains the main method.
     * @param sourceRoot The source root used during compilation, if any.
     * @param previewEnabled True if preview features were enabled during compilation.
     */
    public record CompilationResult(
            Path classPath,
            Set<String> compiledClassNames,
            String mainClass,
            Optional<Path> sourceRoot,
            boolean previewEnabled)
            implements AutoCloseable {

        /**
         * Constructs a CompilationResult with an explicit source root.
         *
         * @param classPath Class output path.
         * @param compiledClassNames Set of compiled class names.
         * @param mainClass Main entry class name.
         * @param sourceRoot Source root path.
         */
        public CompilationResult(
                Path classPath,
                Set<String> compiledClassNames,
                String mainClass,
                Optional<Path> sourceRoot) {
            this(classPath, compiledClassNames, mainClass, sourceRoot, false);
        } // CompilationResult

        /**
         * Constructs a CompilationResult without an explicit source root.
         *
         * @param classPath Class output path.
         * @param compiledClassNames Set of compiled class names.
         * @param mainClass Main entry class name.
         */
        public CompilationResult(
                Path classPath, Set<String> compiledClassNames, String mainClass) {
            this(classPath, compiledClassNames, mainClass, Optional.empty(), false);
        } // CompilationResult

        @Override
        public void close() {
            try {
                deleteWorkingDirectory(classPath);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(
                        "Unable to clean compilation files", failure);
            } // try
        } // close
    } // CompilationResult
} // CompilationHelper
