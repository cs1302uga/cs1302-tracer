package cs1302.tracer;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.modifiers.NodeWithPublicModifier;

import javax.tools.*;
import javax.tools.JavaFileObject.Kind;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A collection of methods that are used to compile a Java program. */
public class CompilationHelper {

    /**
     * Compile a Java program.
     *
     * @param javaSource The Java program to compile.
     * @return The CompilationResult for this compilation.
     * @throws IllegalArgumentException If the Java program failed to compiled.
     */
    public static CompilationResult compile(String javaSource) throws IOException {
        /*
         * Parse source code so we can later identify the main method and top-level
         * class
         */
        StaticJavaParser.setConfiguration(
            new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));
        CompilationUnit sourceCompilationUnit = StaticJavaParser.parse(javaSource);

        /*
         * Create a working directory tree for compilation
         */
        Path workingDir = createWorkingDir();
        String[] topLevelClassBinaryName =
            findTopLevelDeclarationBinaryName(sourceCompilationUnit).split("\\.");

        Path inputSourceDirectory;
        if (topLevelClassBinaryName.length == 1) {
            inputSourceDirectory = workingDir;
        } else {
            inputSourceDirectory = Files.createDirectories(
                Paths.get(workingDir.toString(),
                    Arrays.copyOf(topLevelClassBinaryName, topLevelClassBinaryName.length - 1)));
        }
        Path inputSourceFile = Paths.get(inputSourceDirectory.toString(),
            topLevelClassBinaryName[topLevelClassBinaryName.length - 1] + Kind.SOURCE.extension);
        Files.writeString(inputSourceFile, javaSource);

        /*
         * Compile Java source code
         */
        Set<String> compiledClassNames = new HashSet<String>();
        JavaCompiler javaCompiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(),
            "Could not get Java compiler");
        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();
        // this file manager is used to open source files from disk
        StandardJavaFileManager standardFileManager =
            javaCompiler.getStandardFileManager(diagnosticCollector, null,
                null);
        // this file manager is used when outputting class files to disk. it wraps the
        // standard file manager so that we can record what classes are compiled. this
        // is necessary for debugging later.
        JavaFileManager forwardingFileManager =
            new ForwardingJavaFileManager<StandardJavaFileManager>(
                javaCompiler.getStandardFileManager(diagnosticCollector, null, null)) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                           Kind kind,
                                                           FileObject sibling) throws IOException {
                    compiledClassNames.add(className);
                    return super.getJavaFileForOutput(location, className, kind, sibling);
                }
            };
        Iterable<? extends JavaFileObject> compilationUnit =
            standardFileManager.getJavaFileObjects(inputSourceFile);

        boolean compilationSuccess = javaCompiler
            .getTask(null, forwardingFileManager, diagnosticCollector, List.of("-g"), null,
                compilationUnit).call();

        if (!compilationSuccess) {
            StringBuilder message =
                new StringBuilder("Compilation of provided Java source code failed");
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

        MethodDeclaration mainMethod = findMain(sourceCompilationUnit);
        String mainClass = String.join(".", getAncestorFqn(sourceCompilationUnit, mainMethod));

        return new CompilationResult(workingDir, compiledClassNames, mainClass);
    }

    /**
     * Create a temporary working directory that will be removed at JVM exit.
     *
     * @return The path to the created temporary working directory.
     */
    private static Path createWorkingDir() throws IOException {
        Path workingDir = Files.createTempDirectory("code-tracer");
        Thread workingDirCleanupHook = new Thread(() -> {
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
     * @throws IllegalArgumentException If the compilation unit doesn't contain exactly one
     *                                  top-level declaration.
     */
    private static String findTopLevelDeclarationBinaryName(CompilationUnit compilationUnit) {
        List<Node> topLevelNodes = new ArrayList<>();
        new Node.DirectChildrenIterator(compilationUnit).forEachRemaining(topLevelNodes::add);
        List<TypeDeclaration<?>> topLevelPublicDeclarations = topLevelNodes.stream()
            .filter(n -> n instanceof TypeDeclaration)
            .map(n -> (TypeDeclaration<?>) n)
            .filter(NodeWithPublicModifier::isPublic)
            .collect(Collectors.toList());

        if (topLevelPublicDeclarations.size() != 1) {
            String lines = "[" + String.join(", ", topLevelPublicDeclarations.stream()
                .filter(m -> m.getBegin().isPresent())
                .map(m -> m.getBegin().get().line)
                .map(Object::toString)
                .toArray(String[]::new)) + "]";
            throw new IllegalArgumentException(String.format(
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
     * For example, given the following compilation unit, the ancestor FQN of {@code Inner} would be
     * {@code ["test", "example", "Outer"]}.
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
    private static List<String> getAncestorFqn(CompilationUnit compilationUnit,
                                               BodyDeclaration<?> declaration) {
        Iterator<Node> parents = new Node.ParentsVisitor(declaration);
        List<String> ancestorNames = new LinkedList<>();
        while (parents.hasNext()) {
            Node parent = parents.next();
            if (!(parent instanceof TypeDeclaration<?> parentDecl)) {
                continue;
            }
            ancestorNames.addFirst(parentDecl.getNameAsString());
        }

        compilationUnit.getPackageDeclaration().ifPresent(p -> {
            ancestorNames.addAll(0, Arrays.asList(p.getNameAsString().split("\\.")));
        });

        return ancestorNames;
    }

    /**
     * Locate the main method in the provided compilation unit.
     *
     * @param compilationUnit parsed Java source code
     * @return the declaration node for the main method
     * @throws IllegalArgumentException if the source code doesn't have exactly one
     *                                  main method
     */
    private static MethodDeclaration findMain(CompilationUnit compilationUnit) {
        Predicate<MethodDeclaration> isMain = m -> {
            boolean isNamedMain = m.getNameAsString().equals("main");
            boolean hasVoidReturn = m.getType().isVoidType();
            // we fall back to checking type strings with these param checks because
            // otherwise they fail when String is given as an fqn (java.lang.String)
            boolean hasStringArrArg = m.getParameterByType(String[].class)
                .or(() -> m.getParameterByType("java.lang.String[]"))
                .isPresent();
            boolean hasStringVarargsArg = m.getParameterByType(String.class)
                .or(() -> m.getParameterByType("java.lang.String"))
                .map(p -> p.isVarArgs())
                .orElse(false);
            boolean hasOneArg = m.getParameters().size() == 1;
            return m.isPublic() && m.isStatic() && hasVoidReturn && isNamedMain
                && (hasStringArrArg ^ hasStringVarargsArg)
                && hasOneArg;
        };

        List<MethodDeclaration> mainMethods =
            compilationUnit.findAll(MethodDeclaration.class, isMain);

        if (mainMethods.size() != 1) {
            String lines = "[" + String.join(", ", mainMethods.stream()
                .filter(m -> m.getBegin().isPresent())
                .map(m -> m.getBegin().get().line)
                .map(Object::toString)
                .toArray(String[]::new)) + "]";
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
     * @param classPath          Root of the class path where compiled classes were
     *                           output.
     * @param compiledClassNames Binary names of the classes that were compiled.
     * @param mainClass          Binary name of the class that contains the main
     *                           method.
     */
    public record CompilationResult(Path classPath, Set<String> compiledClassNames,
                                    String mainClass) {
    }
}
