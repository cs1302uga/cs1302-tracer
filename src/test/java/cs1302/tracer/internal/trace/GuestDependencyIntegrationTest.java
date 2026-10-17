package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jdi.VirtualMachine;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.internal.SourcePreparation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuestDependencyIntegrationTest {

    @TempDir
    Path directory;

    private static final String SOURCE = """
        public class Main {
            public static void main(String[] args) throws Exception {
                int value = library.Value.next();
                System.out.print(value);
                try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream("version.txt")) {
                    System.out.print(":" + new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        }
        """;

    @Test
    void dedicatedAndPersistentGuestsUseCapturedAbsoluteManifestDependencies() throws Exception {
        Path dependency = library("absolute original : ; \" ' .jar", 7);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, dependency.toUri().toASCIIString());
        Path owner = directory.resolve("owner.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(owner), manifest)) {
            stream.finish();
        }
        TraceClassPath dependencies = TraceClassPath.builder().addPath(owner).copy(true).build();
        try (SourcePreparation preparation = prepare(SOURCE, dependencies);
                PersistentGuestSession persistent = PersistentGuestSession.create()) {
            Files.delete(owner);
            Files.delete(dependency);
            List<ExecutionSnapshot> dedicated = DebugTraceHelper.traceChronological(preparation.compilation(),
                List.of(), preparation.units(), true);
            assertThat(dedicated.getLast().stdoutSlice().asUtf8String()).isEqualTo("8:7");
            List<ExecutionSnapshot> reusable = persistent.traceChronologicalWithSpecs(preparation.compilation(),
                List.of(), preparation.units(), "");
            assertThat(reusable.getLast().stdoutSlice().asUtf8String()).isEqualTo("8:7");
            assertThat(reusable.getLast().stderrSlice().asUtf8String()).isEmpty();
            assertThat(persistent.isAlive()).isTrue();
        }
    }

    @Test
    void persistentJobsReplaceDependencyClassesResourcesAndStaticStateAfterFailure() throws Exception {
        Path first = library("first.jar", 10);
        Path second = library("second.jar", 20);
        TraceClassPath firstPath = TraceClassPath.builder().addPath(first).build();
        TraceClassPath secondPath = TraceClassPath.builder().addPath(second).build();
        String failure = SOURCE.replace("int value = library.Value.next();",
            "int value = library.Value.next(); if (value > 0) { throw new IllegalStateException(\"between\"); }");
        try (SourcePreparation one = prepare(SOURCE, firstPath);
                SourcePreparation failed = prepare(failure, firstPath);
                SourcePreparation two = prepare(SOURCE, secondPath);
                PersistentGuestSession guest = PersistentGuestSession.create()) {
            List<ExecutionSnapshot> before = guest.traceChronologicalWithSpecs(one.compilation(), List.of(),
                one.units(), "");
            assertThat(before.getLast().stdoutSlice().asUtf8String()).isEqualTo("11:10");
            List<ExecutionSnapshot> between = guest.traceChronologicalWithSpecs(failed.compilation(), List.of(),
                failed.units(), "");
            assertThat(between.getLast().stderrSlice().asUtf8String()).contains("between");
            List<ExecutionSnapshot> after = guest.traceChronologicalWithSpecs(two.compilation(), List.of(),
                two.units(), "");
            assertThat(after.getLast().stdoutSlice().asUtf8String()).isEqualTo("21:20");
            List<ExecutionSnapshot> repeated = guest.traceChronologicalWithSpecs(one.compilation(), List.of(),
                one.units(), "");
            assertThat(repeated.getLast().stdoutSlice().asUtf8String()).isEqualTo("11:10");
            assertThat(before.getLast().stdoutSlice().asUtf8String()).isEqualTo("11:10");
            assertThat(guest.completedJobCount()).isEqualTo(4);
        }
    }

    @Test
    void nativeDedicatedMainKeepsSystemContextLoaderInputAndNonDaemonLifetime() throws Exception {
        String source = """
            public class Main {
                public static void main(String[] args) throws Exception {
                    System.out.println(library.Value.next());
                    System.out.println(Main.class.getClassLoader() == ClassLoader.getSystemClassLoader());
                    System.out.println(Main.class.getClassLoader() == Thread.currentThread().getContextClassLoader());
                    System.out.println(System.in.read());
                    new Thread(() -> {
                        try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); }
                        System.out.println("child-finished");
                    }).start();
                }
            }
            """;
        Path dependency = library("native.jar", 30);
        try (SourcePreparation preparation = prepare(source, TraceClassPath.builder().addPath(dependency).build())) {
            Process process = launch(preparation);
            try {
                process.getOutputStream().write('A');
                process.getOutputStream().close();
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(error).isZero();
                assertThat(output.lines().toList()).containsExactly("31", "true", "true", "65", "child-finished");
                assertThat(error).isEmpty();
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void nativeInstanceMainAndUncaughtExceptionsKeepJvmBehavior() throws Exception {
        Path dependency = library("instance.jar", 40);
        String source = """
            public class Main {
                void main() {
                    System.out.println(library.Value.next());
                    throw new IllegalStateException("native-failure");
                }
            }
            """;
        try (SourcePreparation preparation = prepare(source, TraceClassPath.builder().addPath(dependency).build())) {
            Process process = launch(preparation);
            try {
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isNotZero();
                assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).contains("41");
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(error).contains("Exception in thread \"main\" java.lang.IllegalStateException: native-failure")
                    .contains("Main.main(Main.java:4)").doesNotContain("GuestHarness", "GuestSystemClassLoader");
            } finally {
                process.destroyForcibly();
            }
        }
    }

    private Path library(String jarName, int initial) throws Exception {
        Path root = Files.createTempDirectory(directory, "library-");
        Path source = root.resolve("Value.java");
        Files.writeString(source, "package library; public class Value { private static int value = " + initial
            + "; public static int next() { return ++value; } }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-g", "--release", "21", "-d", root.toString(), source.toString())).isZero();
        Path archive = directory.resolve(jarName);
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(archive))) {
            stream.putNextEntry(new JarEntry("library/Value.class"));
            Files.copy(root.resolve("library/Value.class"), stream);
            stream.closeEntry();
            stream.putNextEntry(new JarEntry("version.txt"));
            stream.write(Integer.toString(initial).getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        return archive;
    }

    private static SourcePreparation prepare(String source, TraceClassPath dependencies) throws Exception {
        return SourcePreparation.prepare(source, Optional.empty(), SourcePreparation.Discovery.SUBMITTED_ONLY,
            dependencies);
    }

    private static Process launch(SourcePreparation preparation) throws Exception {
        var compiled = preparation.compilation();
        VirtualMachine vm = GuestLauncher.launch(compiled.mainClass(), compiled.classPath(), compiled.dependencies(),
            compiled.previewEnabled());
        vm.resume();
        return vm.process();
    }

}
