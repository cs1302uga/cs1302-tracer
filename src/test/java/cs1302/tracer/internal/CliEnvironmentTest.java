package cs1302.tracer.internal;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliEnvironmentTest {

    @TempDir Path root;

    private Path executable(String name, String body) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private Path fakeJdk(String version, String compilerVersion, String home) throws Exception {
        Path java = executable("chosen JDK/bin/java", "printf '%s\\n' 'openjdk " + version
                + "' '    java.home = " + home + "'");
        executable("chosen JDK/bin/javac", "printf '%s\\n' 'javac " + compilerVersion + "'");
        return java.getParent().getParent();
    }

    @Test
    void selectionUsesAuthoritativeHomeAndExecutablePathEntries() throws Exception {
        Path jdk = fakeJdk("25", "25", System.getProperty("java.home"));
        Path java = jdk.resolve("bin/java");
        Map<String,String> env = Map.of("JAVA_HOME", jdk.toString(), "PATH", root.resolve("absent").toString());
        assertThat(CliEnvironment.select(env, "java", false)).isEqualTo(java);
        assertThat(CliEnvironment.select(Map.of("JAVA_HOME", "", "PATH", java.getParent().toString()), "java", false))
                .isEqualTo(java);
        assertThatThrownBy(() -> CliEnvironment.select(Map.of("JAVA_HOME", root.resolve("bad").toString(),
                "PATH", java.getParent().toString()), "java", false)).hasMessageContaining("JAVA_HOME");
        Files.delete(java);
        Files.createDirectory(java);
        assertThatThrownBy(() -> CliEnvironment.select(env, "java", false)).hasMessageContaining("executable java");
        Files.delete(java);
        Files.writeString(java, "not executable");
        assertThatThrownBy(() -> CliEnvironment.select(env, "java", false)).hasMessageContaining("executable java");
        assertThatThrownBy(() -> CliEnvironment.select(Map.of("PATH", java.getParent().toString()), "java", false))
                .hasMessageContaining("No executable java");
        assertThatThrownBy(() -> CliEnvironment.select(Map.of(), "javac", false)).hasMessageContaining("PATH");
        Path windowsJava = executable("windows/bin/java.exe", "exit 0");
        assertThat(CliEnvironment.select(Map.of("Java_Home", windowsJava.getParent().getParent().toString()), "java", true))
                .isEqualTo(windowsJava);
        assertThat(CliEnvironment.select(Map.of("Path", root.resolve("missing") + ";" + windowsJava.getParent()), "java", true))
                .isEqualTo(windowsJava);
    }

    @Test
    void validatesVersionsAndCanonicalRuntimeIdentity() throws Exception {
        String home = System.getProperty("java.home");
        Path jdk = fakeJdk("25.0.4+8-LTS", "25.0.4", home);
        Map<String,String> env = Map.of("JAVA_HOME", jdk.toString());
        CliEnvironment.validate(env, Path.of(home));
        Path alias = root.resolve("home alias");
        Files.createSymbolicLink(alias, Path.of(home));
        CliEnvironment.validate(env, alias);
        fakeJdk("25.0.4", "25.0.3", home);
        assertThatThrownBy(() -> CliEnvironment.validate(env, Path.of(home))).hasMessageContaining("versions differ");
        fakeJdk("25.0.4", "25.0.4", root.toString());
        assertThatThrownBy(() -> CliEnvironment.validate(env, Path.of(home))).hasMessageContaining("running JDK");
        executable("chosen JDK/bin/java", "echo 'openjdk 25.0.4'");
        assertThatThrownBy(() -> CliEnvironment.validate(env, Path.of(home))).hasMessageContaining("Cannot determine");
        CliEnvironment.validate(Map.of("JAVA_HOME", home), Path.of(home));
    }

    @ParameterizedTest
    @ValueSource(strings = {"17.0.20", "21-ea", "25-beta", "1.8.0_482", "bad", "25.0.0"})
    void rejectsUnsupportedOrUnparseableReleases(String version) {
        assertThatThrownBy(() -> CliEnvironment.version("javac " + version, "javac", Path.of("javac")))
                .isInstanceOf(IOException.class).hasMessageContaining("JDK 21");
    }

    @Test
    void parsesVendorBannersWithoutRequiringIdenticalBuildMetadata() throws Exception {
        for (String banner : List.of("openjdk 21.0.12 2026-07-21", "java 25.0.4 2026-07-21",
                "java version \"25.0.4\"", "openjdk 26.0.2+7-vendor")) {
            assertThat(CliEnvironment.version(banner, "(?:java|openjdk)", Path.of("java")).feature()).isGreaterThanOrEqualTo(21);
        }
        assertThatThrownBy(() -> CliEnvironment.version("noise", "javac", Path.of("javac")))
                .hasMessageContaining("Cannot read");
    }

    @Test
    void probesCaptureBothStreamsAndRejectFailuresAndTimeouts() throws Exception {
        Path tool = executable("tool", "echo standard; echo error >&2");
        assertThat(CliEnvironment.probe(List.of(tool.toString()), Duration.ofSeconds(5)))
                .contains("standard", "error");
        executable("tool", "exit 7");
        assertThatThrownBy(() -> CliEnvironment.probe(List.of(tool.toString()), Duration.ofSeconds(5)))
                .hasMessageContaining("exit 7");
        assertThatThrownBy(() -> CliEnvironment.probe(List.of(root.resolve("missing").toString()), Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class);
        Path pid = root.resolve("child.pid");
        executable("tool", "sleep 30 &\necho $! > '" + pid + "'\nwait");
        assertThatThrownBy(() -> CliEnvironment.probe(List.of(tool.toString()), Duration.ofMillis(200)))
                .hasMessageContaining("Timed out");
        long child = Long.parseLong(Files.readString(pid).strip());
        for (int i = 0; i < 100 && ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(10);
        }
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void probesRejectExcessiveOutputBeforeAndAfterExit() throws Exception {
        Path data = root.resolve("output");
        Files.writeString(data, "x".repeat(70000));
        for (String wait : List.of("", "\nsleep 5")) {
            Path tool = executable("tool", "cat '" + data + "'" + wait);
            assertThatThrownBy(() -> CliEnvironment.probe(List.of(tool.toString()), Duration.ofSeconds(5)))
                    .hasMessageContaining("Excessive version output");
        }
    }

    @Test
    void interruptedCliPreservesInterruptAndReturnsOne() {
        PrintStream old = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(errors));
            Thread.currentThread().interrupt();
            assertThat(App.execute(new String[] {"--version"})).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(errors.toString()).contains("interrupted");
        } finally {
            Thread.interrupted();
            System.setErr(old);
        }
    }

    @Test
    void directCliRejectsInvalidEnvironmentBeforeInformationalCommands() throws Exception {
        for (String argument : List.of("--help", "--version", "show-licenses")) {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(a -> a.startsWith("-javaagent:") && a.contains("jacoco")).forEach(command::add);
            command.addAll(List.of("-cp", System.getProperty("java.class.path"), "cs1302.tracer.App", argument));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("JAVA_HOME", root.resolve("invalid home").toString());
            Process process = builder.start();
            assertThat(process.waitFor()).isEqualTo(1);
            assertThat(new String(process.getInputStream().readAllBytes())).isEmpty();
            assertThat(new String(process.getErrorStream().readAllBytes())).contains("JAVA_HOME", "JDK environment check failed");
        }
    }

    @Test
    void utilityConstructorIsInaccessibleToCallers() throws Exception {
        var constructor = CliEnvironment.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }
}
