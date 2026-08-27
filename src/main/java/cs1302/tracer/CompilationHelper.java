package cs1302.tracer;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithPublicModifier;
import com.github.javaparser.ast.ImportDeclaration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.*;
import javax.tools.JavaFileObject.Kind;

/** A collection of methods that are used to compile a Java program. */
public class CompilationHelper {

  /** Regular expression pattern for matching file delimiters in multi-file source streams. */
  public static final Pattern DELIMITER_PATTERN =
      Pattern.compile("^//\\s*[-=]{3,}\\s*(.*?\\.java)\\s*[-=]{3,}\\s*$", Pattern.MULTILINE);

  /**
   * Represents an individual source file from a single-file or multi-file input stream.
   *
   * @param relativePath The relative path of the source file (e.g. {@code cs1302/math/Calculator.java}).
   * @param content The source code content of the file.
   * @param ast The parsed CompilationUnit for the file.
   */
  public record SourceFile(String relativePath, String content, CompilationUnit ast) {}

  /**
   * Parse a single-file or multi-file Java stream demarcated by comment delimiters like
   * {@code // --- path/to/File.java ---}.
   *
   * @param rawInput The raw input string from stdin or file.
   * @return A list of {@link SourceFile} objects parsed from the input stream.
   */
  public static List<SourceFile> parseMultiFileStream(String rawInput) {
    if (rawInput == null) {
      throw new IllegalArgumentException("Input source cannot be null");
    }
    Matcher matcher = DELIMITER_PATTERN.matcher(rawInput);
    List<Integer> delimiterStarts = new ArrayList<>();
    List<Integer> delimiterEnds = new ArrayList<>();
    List<String> filePaths = new ArrayList<>();

    while (matcher.find()) {
      delimiterStarts.add(matcher.start());
      delimiterEnds.add(matcher.end());
      filePaths.add(matcher.group(1).trim());
    }

    JavaParser parser =
        new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));

    if (filePaths.isEmpty()) {
      ParseResult<CompilationUnit> parseResult = parser.parse(rawInput);
      if (!parseResult.isSuccessful()) {
        throw new IllegalArgumentException(
            "Parsing failed with the following errors: "
                + parseResult.getProblems().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "[", "]")));
      }
      CompilationUnit cu = parseResult.getResult().get();
      String relPath =
          findTopLevelDeclarationBinaryName(cu).replace('.', File.separatorChar) + ".java";
      return List.of(new SourceFile(relPath, rawInput, cu));
    }

    List<SourceFile> result = new ArrayList<>();
    for (int i = 0; i < filePaths.size(); i++) {
      int contentStart = delimiterEnds.get(i);
      int contentEnd =
          (i + 1 < delimiterStarts.size()) ? delimiterStarts.get(i + 1) : rawInput.length();
      String content = rawInput.substring(contentStart, contentEnd);
      if (content.startsWith("\r\n")) {
        content = content.substring(2);
      } else if (content.startsWith("\n")) {
        content = content.substring(1);
      }

      ParseResult<CompilationUnit> parseResult = parser.parse(content);
      if (!parseResult.isSuccessful()) {
        throw new IllegalArgumentException(
            "Parsing failed for "
                + filePaths.get(i)
                + " with the following errors: "
                + parseResult.getProblems().stream()
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "[", "]")));
      }
      CompilationUnit cu = parseResult.getResult().get();

      String rawPath = filePaths.get(i).replace('\\', '/');
      String normalizedRelPath;
      if (rawPath.contains("/")) {
        normalizedRelPath = rawPath.replace('/', File.separatorChar);
      } else {
        Optional<String> pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString());
        if (pkg.isPresent()) {
          normalizedRelPath =
              pkg.get().replace('.', File.separatorChar) + File.separatorChar + rawPath;
        } else {
          normalizedRelPath = rawPath;
        }
      }

      result.add(new SourceFile(normalizedRelPath, content, cu));
    }

    return result;
  }

  /**
   * Find the entry point source file containing the main method among multiple files.
   *
   * @param sourceFiles The list of source files to search.
   * @return The source file containing {@code public static void main(String[] args)}.
   */
  public static SourceFile findEntryPoint(List<SourceFile> sourceFiles) {
    if (sourceFiles == null || sourceFiles.isEmpty()) {
      throw new IllegalArgumentException("No source files provided");
    }
    for (SourceFile sf : sourceFiles) {
      if (!sf.ast().findAll(MethodDeclaration.class, CompilationHelper::isMainMethod).isEmpty()) {
        return sf;
      }
    }
    return sourceFiles.getFirst();
  }

  /**
   * Combine all compilation units from a multi-file stream into a single compilation unit for AST
   * queries.
   *
   * @param sourceFiles The list of source files.
   * @return A single combined {@link CompilationUnit}.
   */
  public static CompilationUnit combineCompilationUnits(List<SourceFile> sourceFiles) {
    if (sourceFiles == null || sourceFiles.isEmpty()) {
      throw new IllegalArgumentException("No source files provided");
    }
    if (sourceFiles.size() == 1) {
      return sourceFiles.getFirst().ast();
    }
    CompilationUnit combined = new CompilationUnit();
    SourceFile entry = findEntryPoint(sourceFiles);
    entry.ast().getPackageDeclaration().ifPresent(combined::setPackageDeclaration);
    Set<String> importNames = new HashSet<>();
    for (SourceFile sf : sourceFiles) {
      for (ImportDeclaration imp : sf.ast().getImports()) {
        if (importNames.add(imp.getNameAsString())) {
          combined.addImport(imp);
        }
      }
      for (TypeDeclaration<?> type : sf.ast().getTypes()) {
        combined.addType(type.clone());
      }
    }
    return combined;
  }

  /**
   * Automatically infer the source root directory for a given compilation unit and optional input
   * file path.
   *
   * @param cu The parsed compilation unit.
   * @param inputPath The optional path to the input source file.
   * @return An Optional containing the inferred source root directory, if discovered.
   */
  public static Optional<Path> findSourceRoot(CompilationUnit cu, Optional<Path> inputPath) {
    Optional<String> pkgName = cu.getPackageDeclaration().map(p -> p.getNameAsString());
    if (pkgName.isEmpty()) {
      return Optional.empty();
    }

    String pkgPathStr = pkgName.get().replace('.', File.separatorChar);

    if (inputPath.isPresent()) {
      Path absInput = inputPath.get().toAbsolutePath().normalize();
      Path parent = absInput.getParent();
      if (parent == null) {
        return Optional.empty();
      }
      String[] parts = pkgName.get().split("\\.");
      Path curr = parent;
      for (int i = parts.length - 1; i >= 0; i--) {
        if (curr != null && curr.getFileName() != null && curr.getFileName().toString().equals(parts[i])) {
          curr = curr.getParent();
        } else {
          return Optional.empty();
        }
      }
      if (curr != null && Files.isDirectory(curr.resolve(pkgPathStr))) {
        return Optional.of(curr);
      }
      return Optional.empty();
    } else {
      // Input from stdin
      List<Path> candidates =
          List.of(
              Path.of("."),
              Path.of("src/main/java"),
              Path.of("src"));
      for (Path candidate : candidates) {
        Path pkgDir = candidate.resolve(pkgPathStr);
        if (Files.isDirectory(pkgDir)) {
          return Optional.of(candidate.toAbsolutePath().normalize());
        }
      }
      return Optional.empty();
    }
  }

  /**
   * Compile a Java program.
   *
   * @param javaSource The Java program to compile.
   * @return The CompilationResult for this compilation.
   * @throws IllegalArgumentException If the Java program failed to compile.
   */
  public static CompilationResult compile(String javaSource) throws IOException {
    return compile(javaSource, Optional.empty());
  }

  /**
   * Compile a Java program with an optional source root for resolving dependencies.
   *
   * @param javaSource The Java program to compile.
   * @param sourceRoot The optional source root directory containing package dependencies.
   * @return The CompilationResult for this compilation.
   * @throws IllegalArgumentException If the Java program failed to compile.
   */
  public static CompilationResult compile(String javaSource, Optional<Path> sourceRoot)
      throws IOException {
    List<SourceFile> sourceFiles = parseMultiFileStream(javaSource);

    /*
     * Create a working directory tree for compilation
     */
    Path workingDir = createWorkingDir();
    List<File> allSourceFiles = new ArrayList<>();

    for (SourceFile sf : sourceFiles) {
      Path targetPath = workingDir.resolve(sf.relativePath());
      if (targetPath.getParent() != null) {
        Files.createDirectories(targetPath.getParent());
      }
      Files.writeString(targetPath, sf.content());
      allSourceFiles.add(targetPath.toFile());
    }

    /*
     * Compile Java source code
     */
    Set<String> compiledClassNames = new HashSet<>();
    JavaCompiler javaCompiler =
        Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Could not get Java compiler");
    DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
    StandardJavaFileManager standardFileManager =
        javaCompiler.getStandardFileManager(diagnosticCollector, null, null);
    JavaFileManager forwardingFileManager =
        new ForwardingJavaFileManager<StandardJavaFileManager>(
            javaCompiler.getStandardFileManager(diagnosticCollector, null, null)) {
          @Override
          public JavaFileObject getJavaFileForOutput(
              Location location, String className, Kind kind, FileObject sibling)
              throws IOException {
            compiledClassNames.add(className);
            return super.getJavaFileForOutput(location, className, kind, sibling);
          }
        };

    Iterable<? extends JavaFileObject> compilationUnit =
        standardFileManager.getJavaFileObjectsFromFiles(allSourceFiles);

    List<String> compilerOptions = new ArrayList<>();
    compilerOptions.add("-g");
    compilerOptions.add("-d");
    compilerOptions.add(workingDir.toAbsolutePath().toString());
    compilerOptions.add("-sourcepath");
    String sourcePathStr = workingDir.toAbsolutePath().toString();
    if (sourceRoot.isPresent() && Files.isDirectory(sourceRoot.get())) {
      sourcePathStr += File.pathSeparator + sourceRoot.get().toAbsolutePath().toString();
    }
    compilerOptions.add(sourcePathStr);
    compilerOptions.add("-classpath");
    compilerOptions.add(sourcePathStr);

    boolean compilationSuccess =
        javaCompiler
            .getTask(
                null,
                forwardingFileManager,
                diagnosticCollector,
                compilerOptions,
                null,
                compilationUnit)
            .call();

    if (!compilationSuccess) {
      StringBuilder message = new StringBuilder("Compilation of provided Java source code failed");
      List<?> diagnostics = diagnosticCollector.getDiagnostics();

      if (diagnostics.isEmpty()) {
        message.append('.');
      } else {
        message.append(" with the following messages:\n");
        message.append(
            diagnosticCollector.getDiagnostics().stream()
                .map(Object::toString)
                .collect(Collectors.joining("\n")));
      }

      throw new IllegalArgumentException(message.toString());
    }

    SourceFile entryPoint = findEntryPoint(sourceFiles);
    MethodDeclaration mainMethod = findMain(entryPoint.ast());
    String mainClass = String.join(".", getAncestorFqn(entryPoint.ast(), mainMethod));

    return new CompilationResult(
        workingDir,
        compiledClassNames,
        mainClass,
        sourceRoot.isPresent() ? sourceRoot : Optional.of(workingDir));
  }

  /**
   * Create a temporary working directory that will be removed at JVM exit.
   *
   * @return The path to the created temporary working directory.
   */
  private static Path createWorkingDir() throws IOException {
    Path workingDir = Files.createTempDirectory("code-tracer");
    Thread workingDirCleanupHook =
        new Thread(
            () -> {
              try (Stream<Path> paths = Files.walk(workingDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
              } catch (IOException e) {
                return;
              }
            });
    Runtime.getRuntime().addShutdownHook(workingDirCleanupHook);
    return workingDir;
  }

  /**
   * Find the binary name of the single top-level declaration in a compilation unit.
   *
   * @param compilationUnit The compilation unit to search in.
   * @return The binary name of the compilation unit's top-level declaration.
   * @throws IllegalArgumentException If the compilation unit doesn't contain exactly one top-level
   *     declaration.
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
      String lines =
          "["
              + String.join(
                  ", ",
                  topLevelPublicDeclarations.stream()
                      .filter(m -> m.getBegin().isPresent())
                      .map(m -> m.getBegin().get().line)
                      .map(Object::toString)
                      .toArray(String[]::new))
              + "]";
      throw new IllegalArgumentException(
          String.format(
              "Java source code must have exactly one public top-level type declaration. "
                  + "Found %d such declarations on lines %s.",
              topLevelPublicDeclarations.size(), lines));
    }

    TypeDeclaration<?> fileClass = topLevelPublicDeclarations.get(0);
    List<String> fqn = getAncestorFqn(compilationUnit, fileClass);
    fqn.add(fileClass.getNameAsString());

    return String.join(".", fqn);
  }

  /**
   * Get the FQN of a declaration's direct ancestor in a compilation unit as a list.
   *
   * <p>For example, given the following compilation unit, the ancestor FQN of {@code Inner} would
   * be {@code ["test", "example", "Outer"]}.
   *
   * <pre>
   * <code>
   * package test.example;
   * public class Outer {
   *   public class Inner {
   *   }
   * }
   * </code>
   * </pre>
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
      }
      ancestorNames.addFirst(parentDecl.getNameAsString());
    }

    compilationUnit
        .getPackageDeclaration()
        .ifPresent(
            p -> {
              ancestorNames.addAll(0, Arrays.asList(p.getNameAsString().split("\\.")));
            });

    return ancestorNames;
  }

  /**
   * Check whether a method declaration matches standard {@code public static void main(String[] args)}.
   *
   * @param m The method declaration to check.
   * @return True if the method is a main method.
   */
  public static boolean isMainMethod(MethodDeclaration m) {
    boolean isNamedMain = m.getNameAsString().equals("main");
    boolean hasVoidReturn = m.getType().isVoidType();
    boolean hasStringArrArg =
        m.getParameterByType(String[].class)
            .or(() -> m.getParameterByType("java.lang.String[]"))
            .isPresent();
    boolean hasStringVarargsArg =
        m.getParameterByType(String.class)
            .or(() -> m.getParameterByType("java.lang.String"))
            .map(p -> p.isVarArgs())
            .orElse(false);
    boolean hasOneArg = m.getParameters().size() == 1;
    return m.isPublic()
        && m.isStatic()
        && hasVoidReturn
        && isNamedMain
        && (hasStringArrArg ^ hasStringVarargsArg)
        && hasOneArg;
  }

  /**
   * Locate the main method in the provided compilation unit.
   *
   * @param compilationUnit parsed Java source code
   * @return the declaration node for the main method
   * @throws IllegalArgumentException if the source code doesn't have exactly one main method
   */
  private static MethodDeclaration findMain(CompilationUnit compilationUnit) {
    List<MethodDeclaration> mainMethods =
        compilationUnit.findAll(MethodDeclaration.class, CompilationHelper::isMainMethod);

    if (mainMethods.size() != 1) {
      String lines =
          "["
              + String.join(
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
    }

    return mainMethods.getFirst();
  }

  /**
   * A collection of information from the successful compilation of a Java program.
   *
   * @param classPath Root of the class path where compiled classes were output.
   * @param compiledClassNames Binary names of the classes that were compiled.
   * @param mainClass Binary name of the class that contains the main method.
   * @param sourceRoot The source root used during compilation, if any.
   */
  public record CompilationResult(
      Path classPath,
      Set<String> compiledClassNames,
      String mainClass,
      Optional<Path> sourceRoot)
      implements AutoCloseable {

    public CompilationResult(Path classPath, Set<String> compiledClassNames, String mainClass) {
      this(classPath, compiledClassNames, mainClass, Optional.empty());
    }

    @Override
    public void close() {
      if (classPath != null && Files.exists(classPath)) {
        try (Stream<Path> paths = Files.walk(classPath)) {
          paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
          // ignore error during cleanup
        }
      }
    }
  }
}
