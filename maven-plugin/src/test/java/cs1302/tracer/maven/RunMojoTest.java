package cs1302.tracer.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RunMojoTest {
    @TempDir Path directory;

    @Test void javaSelectionUsesTheRunningJdkOnEachPlatform() {
        String home = directory.resolve("JDK with spaces").toString();
        assertEquals(Path.of(home, "bin", "java.exe").toString(), RunMojo.javaExecutable(home, "Windows 11"));
        assertEquals(Path.of(home, "bin", "java").toString(), RunMojo.javaExecutable(home, "Mac OS X"));
        assertEquals(Path.of(home, "bin", "java").toString(), RunMojo.javaExecutable(home, "Linux"));
    }

    @Test void tokenizationPreservesValues() throws Exception {
        assertEquals(List.of("trace", "a b", "", "C:\\work\\Main.java", "a'b", "a\"b", "xy"),
            RunMojo.arguments("  trace 'a b' \"\" C:\\work\\Main.java \"a'b\" 'a\"b' x'y'  "));
        assertEquals(List.of(), RunMojo.arguments(" \t\n"));
        assertEquals(List.of("last"), RunMojo.arguments("last"));
        assertThrows(MojoFailureException.class, () -> RunMojo.arguments("'unfinished"));
        assertThrows(MojoFailureException.class, () -> RunMojo.arguments("\"unfinished"));
    }

    @Test void subprocessSuccessAndFailures() throws Exception {
        RunMojo.run(javaProcess("--version"));
        MojoFailureException failure = assertThrows(MojoFailureException.class,
            () -> RunMojo.run(javaProcess("--not-a-java-option")));
        assertTrue(failure.getMessage().contains("status"));
        assertThrows(MojoExecutionException.class,
            () -> RunMojo.run(new ProcessBuilder(directory.resolve("missing-java").toString())));
    }

    @Test void interruptionDestroysOwnedChild() throws Exception {
        Path source = directory.resolve("Waiting.java");
        Path pid = directory.resolve("pid");
        Files.writeString(source, """
            import java.nio.file.*;
            class Waiting {
                public static void main(String[] args) throws Exception {
                    Files.writeString(Path.of(args[0]), "" + ProcessHandle.current().pid());
                    Thread.sleep(60000);
                }
            }
            """);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread owner = new Thread(() -> {
            try {
                RunMojo.run(javaProcess(source.toString(), pid.toString()));
            } catch (Throwable exception) {
                failure.set(exception);
                assertTrue(Thread.currentThread().isInterrupted());
            }
        });
        owner.start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                while (!Files.exists(pid)) { Thread.sleep(20); }
            });
            long child = Long.parseLong(Files.readString(pid));
            owner.interrupt();
            owner.join(10000);
            assertFalse(owner.isAlive());
            assertInstanceOf(MojoExecutionException.class, failure.get());
            Files.delete(directory.resolve("stdout"));
            Files.delete(directory.resolve("stderr"));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)) {
                    Thread.sleep(20);
                }
            });
        } finally {
            owner.interrupt();
            owner.join(10000);
        }
    }

    @Test void cleanupDestroysDescendants() throws Exception {
        Path source = directory.resolve("Parent.java");
        Path pid = directory.resolve("descendant");
        Files.writeString(source, """
            import java.nio.file.*;
            class Parent {
                public static void main(String[] args) throws Exception {
                    if (args.length == 0) { Thread.sleep(60000); return; }
                    Process child = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(),
                        args[1]).start();
                    Files.writeString(Path.of(args[0]), "" + child.pid());
                    Thread.sleep(60000);
                }
            }
            """);
        Process parent = javaProcess(source.toString(), pid.toString(), source.toString()).start();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
                while (!Files.exists(pid)) { Thread.sleep(20); }
            });
            long descendant = Long.parseLong(Files.readString(pid));
            RunMojo.terminate(parent);
            assertTrue(parent.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (ProcessHandle.of(descendant).map(ProcessHandle::isAlive).orElse(false)) {
                    Thread.sleep(20);
                }
            });
        } finally {
            RunMojo.terminate(parent);
        }
    }

    private ProcessBuilder javaProcess(String... arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectError(directory.resolve("stderr").toFile())
            .redirectOutput(directory.resolve("stdout").toFile());
    }
}
