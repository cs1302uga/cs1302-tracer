package cs1302.tracer.internal.classpath;

import static org.assertj.core.api.Assertions.*;

import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.SourcePreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BinaryClassPathTest {

    @TempDir Path root;

    Path compile(String source) throws Exception {
        Path directory = Files.createTempDirectory(root, "classes");
        Path input = Files.writeString(directory.resolve("Helper.java"), source);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-d",
                directory.toString(), input.toString())).isZero();
        return directory;
    }

    Path jar(Path classes, String manifestPath, Map<String, Path> extra) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (manifestPath != null) manifest.getMainAttributes().putValue("Class-Path", manifestPath);
        if (!extra.isEmpty()) manifest.getMainAttributes().putValue("Multi-Release", "true");
        Path archive = Files.createTempFile(root, "library", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            if (classes != null) {
                try (var paths = Files.walk(classes)) {
                    for (Path entry : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                        output.putNextEntry(new JarEntry(classes.relativize(entry).toString().replace('\\', '/')));
                        Files.copy(entry, output);
                        output.closeEntry();
                    }
                }
            }
            for (var entry : extra.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                Files.copy(entry.getValue(), output);
                output.closeEntry();
            }
        }
        return archive;
    }

    boolean compileAgainst(List<Path> roots, String source) throws Exception {
        Path output = Files.createTempDirectory(root, "program");
        Path input = Files.writeString(output.resolve("Main.java"), source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, roots);
            manager.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
            return compiler.getTask(null, manager, new DiagnosticCollector<JavaFileObject>(),
                    List.of("-proc:none", "-sourcepath", output.toString(), "-d", output.toString()), null,
                    manager.getJavaFileObjects(input)).call();
        }
    }

    @Test
    void compilerUsesOnlyExplicitViewsWithoutReexpandingAbsoluteManifests() throws Exception {
        Path classes = compile("package dep; public class Helper<T> { public T value; }");
        Path dependency = jar(classes, null, Map.of());
        Path container = jar(null, dependency.toUri().toString(), Map.of());
        String source = "class Main extends dep.Helper<String> {}";
        try (var view = BinaryClassPath.open(List.of(container))) {
            assertThat(compileAgainst(view.roots(), source)).isFalse();
        }
        Path copied = Files.copy(dependency, root.resolve("captured.jar"));
        Files.delete(dependency);
        try (var view = BinaryClassPath.open(List.of(container, copied))) {
            assertThat(compileAgainst(view.roots(), source)).isTrue();
            var solver = new BinaryTypeSolver(view.roots());
            var parent = new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                    new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(), solver);
            assertThat(solver.getParent()).isSameAs(parent);
            var type = solver.solveType("dep.Helper");
            assertThat(type.getTypeParameters()).extracting(p -> p.getName()).containsExactly("T");
            assertThat(type.getAllMethods()).isNotEmpty();
            assertThat(solver.tryToSolveType("missing.Type").isSolved()).isFalse();
            assertThat(solver.tryToSolveTypeInModule("module", "dep.Helper").isSolved()).isFalse();
            assertThatThrownBy(() -> solver.setParent(parent)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new BinaryTypeSolver(List.of()).setParent(null))
                    .isInstanceOf(NullPointerException.class);
            var self = new BinaryTypeSolver(List.of());
            assertThatThrownBy(() -> self.setParent(self)).isInstanceOf(IllegalStateException.class);
        }
        Files.delete(copied);
    }

    @Test
    void multiReleaseCompilerAndBytecodeSolverSelectTheSameVersion() throws Exception {
        Path base = compile("package dep; public class Helper { public static int value(){ return 1; } }");
        Path version = compile("package dep; public class Helper { public static String value(){ return \"v\"; } }");
        Path archive = jar(base, null, Map.of("META-INF/versions/21/dep/Helper.class",
                version.resolve("dep/Helper.class")));
        try (var view = BinaryClassPath.open(List.of(archive))) {
            assertThat(compileAgainst(view.roots(), "class Main { String value = dep.Helper.value(); }")).isTrue();
            var solver = new BinaryTypeSolver(view.roots());
            new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                    new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(), solver);
            var declaration = solver.solveType("dep.Helper");
            assertThat(declaration.getDeclaredMethods()).anySatisfy(method -> {
                assertThat(method.getName()).isEqualTo("value");
                assertThat(method.getReturnType().describe()).isEqualTo("java.lang.String");
            });
        }
    }

    @Test
    void directoriesRespectOrderAndResolveNestedTypes() throws Exception {
        Path first = compile("package dep; public class Helper { public static class Nested {} }");
        Path second = compile("package dep; public class Helper { public int other; }");
        try (var view = BinaryClassPath.open(List.of(first, second))) {
            assertThat(view.roots()).containsExactly(first, second);
            var solver = new BinaryTypeSolver(view.roots());
            assertThat(solver.getParent()).isNull();
            new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                    new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(), solver);
            assertThat(solver.solveType("dep.Helper").getDeclaredFields()).isEmpty();
            assertThat(solver.solveType("dep.Helper.Nested").getName()).isEqualTo("Nested");
        }
    }

    @Test
    void bytecodeResolutionKeepsPlatformTypesAheadOfDependencyCopies() throws Exception {
        Path classes = compile("package dep; public class Helper {}");
        Path fakeObject = classes.resolve("java/lang/Object.class");
        Files.createDirectories(fakeObject.getParent());
        var fake = new javassist.bytecode.ClassFile(false, "java.lang.Object", null);
        fake.addField(new javassist.bytecode.FieldInfo(fake.getConstPool(), "shadow", "I"));
        try (var output = new java.io.DataOutputStream(Files.newOutputStream(fakeObject))) {
            fake.write(output);
        }
        var solver = new BinaryTypeSolver(List.of(classes));
        new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver(
                new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver(), solver);
        var platform = solver.solveType("java.lang.Object");
        assertThat(platform.getDeclaredFields()).noneMatch(field -> field.getName().equals("shadow"));
        assertThat(platform.getDeclaredMethods()).anyMatch(method -> method.getName().equals("toString"));
        assertThat(solver.solveType("dep.Helper").getAllMethods())
                .anyMatch(method -> method.getName().equals("toString"));
    }

    @Test
    void preparationResolvesCopiedManifestDependenciesAndOwnsOnlyCopies() throws Exception {
        Path dependency = jar(compile("package dep; public class Helper<T> {}"), null, Map.of());
        Path container = jar(null, dependency.toUri().toString(), Map.of());
        TraceClassPath config = TraceClassPath.builder().addPath(container).copy(true).build();
        Path copied;
        Path output;
        try (var prepared = SourcePreparation.prepare(
                "public class Main extends dep.Helper<String> { public static void main(String[] args) {} }",
                Optional.empty(), SourcePreparation.Discovery.SUBMITTED_ONLY, config)) {
            copied = prepared.compilation().dependencies().getLast();
            output = prepared.compilation().classPath();
            Files.delete(dependency);
            assertThat(prepared.units().getFirst().getType(0).asClassOrInterfaceDeclaration()
                    .getExtendedTypes().get(0).resolve().describe()).isEqualTo("dep.Helper<java.lang.String>");
            assertThat(copied).exists();
        }
        assertThat(copied).doesNotExist();
        assertThat(output).doesNotExist();
        assertThat(container).exists();
    }

    @Test
    void brokenArchiveClosesAlreadyOpenedViews() throws Exception {
        Path valid = jar(null, null, Map.of());
        Path invalid = Files.writeString(root.resolve("invalid.jar"), "broken");
        assertThatThrownBy(() -> BinaryClassPath.open(List.of(valid, invalid)))
                .isInstanceOf(java.io.IOException.class);
        Files.delete(valid);
        var view = BinaryClassPath.open(List.of());
        assertThat(view.roots()).isEmpty();
        view.close();
        view.close();
    }

}
