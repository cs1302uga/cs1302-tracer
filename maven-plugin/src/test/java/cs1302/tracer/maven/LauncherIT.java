package cs1302.tracer.maven;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LauncherIT {
    @TempDir Path directory;

    @Test void projectFreeCommandsAndQuoting() throws Exception {
        assertFalse(Files.exists(directory.resolve("pom.xml")));
        assertTrue(invoke(null, "").contains("Usage:"));
        assertTrue(invoke("--version", "").contains(System.getProperty("launcher.version")));
        Path sources = Files.createDirectory(directory.resolve("source files"));
        Files.writeString(sources.resolve("Main.java"), """
            public class Main {
                public static void main(String[] args) {
                    System.out.println("launcher result");
                }
            }
            """);
        for (String format : List.of("modern", "pytutor")) {
            String json = invoke("trace --input 'source files/Main.java' --all-breakpoints --format " + format, "");
            assertNotNull(JsonParser.parseString(json));
            assertTrue(json.contains("launcher result"));
        }
        assertFailure("--unknown-option");
        assertFailure("'unclosed");
        // A caller's unrelated dependencies must not participate in plugin resolution.
        Files.writeString(directory.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>
            <artifactId>unrelated</artifactId><version>1</version><dependencies><dependency>
            <groupId>does.not.exist</groupId><artifactId>missing</artifactId><version>1</version>
            </dependency></dependencies></project>
            """);
        assertTrue(invoke("--version", "").contains("code-tracer"));
    }

    @Test void consecutiveBatchJobsAndInput() throws Exception {
        String source = "public class Main { public static void main(String[] a) { "
            + "System.out.println(new java.util.Scanner(System.in).nextLine()); } }";
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<String> requests = new ArrayList<>();
        for (String format : List.of("modern", "pytutor")) {
            for (int i = 0; i < 2; i++) {
                String marker = format + i;
                requests.add("{\"id\":\"" + marker + "\",\"source\":" + gson.toJson(source)
                    + ",\"format\":\"" + format + "\",\"stdin\":\"" + marker + "\\n\"}");
            }
        }
        String output = invoke("batch-trace --workers 1", String.join("\n", requests) + "\n");
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertEquals(4, lines.size(), output);
        for (int i = 0; i < lines.size(); i++) {
            com.google.gson.JsonObject response = JsonParser.parseString(lines.get(i)).getAsJsonObject();
            assertEquals(JsonParser.parseString(requests.get(i)).getAsJsonObject().get("id"), response.get("id"));
            assertEquals("completed", response.getAsJsonObject("result").get("status").getAsString(), lines.get(i));
            assertEquals(response.get("id").getAsString() + "\n",
                response.getAsJsonObject("result").get("stdout").getAsString().replace("\r\n", "\n"));
            assertEquals("", response.getAsJsonObject("result").get("stderr").getAsString());
        }
        String threaded = "public class Main { public static void main(String[] a) throws Exception { "
            + "Thread t = new Thread(() -> System.out.println(42)); t.start(); t.join(); } }";
        String request = "{\"source\":" + gson.toJson(threaded) + ",\"format\":\"modern\",\"multithread\":true}";
        String multithread = invoke("batch-trace --workers 1", request + "\n" + request + "\n");
        assertEquals(2, multithread.lines().count());
        for (String line : multithread.lines().toList()) {
            assertEquals("completed", JsonParser.parseString(line).getAsJsonObject()
                .getAsJsonObject("result").get("status").getAsString(), line);
            assertTrue(line.contains("threads"));
        }
        String ordinary = invoke("trace --format modern --multithread", threaded);
        assertTrue(JsonParser.parseString(ordinary).isJsonObject());
        assertTrue(ordinary.contains("threads"));
    }

    @Test void runtimeDependenciesRetainTracerOverrides() throws Exception {
        String version = System.getProperty("launcher.version");
        Path jar = Path.of(System.getProperty("launcher.repository"), "io/github/cs1302uga",
            "cs1302-tracer-maven-plugin", version, "cs1302-tracer-maven-plugin-" + version + ".jar");
        javax.xml.parsers.DocumentBuilder parser = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder();
        org.w3c.dom.Document root = parser.parse(Path.of(System.getProperty("launcher.root"), "pom.xml").toFile());
        try (java.util.jar.JarFile archive = new java.util.jar.JarFile(jar.toFile())) {
            org.w3c.dom.Document descriptor = parser.parse(archive.getInputStream(
                archive.getJarEntry("META-INF/maven/plugin.xml")));
            org.w3c.dom.NodeList dependencies = descriptor.getElementsByTagName("dependency");
            java.util.Map<String, String> versions = new java.util.HashMap<>();
            for (int i = 0; i < dependencies.getLength(); i++) {
                org.w3c.dom.Element dependency = (org.w3c.dom.Element) dependencies.item(i);
                versions.put(dependency.getElementsByTagName("artifactId").item(0).getTextContent(),
                    dependency.getElementsByTagName("version").item(0).getTextContent());
            }
            for (String artifact : List.of("javassist", "guava", "checker-qual", "error_prone_annotations", "jspecify")) {
                assertEquals(root.getElementsByTagName(artifact + ".version").item(0).getTextContent(),
                    versions.get(artifact), artifact);
            }
            assertFalse(versions.containsKey("maven-plugin-api"));
            assertFalse(versions.containsKey("maven-artifact"));
            assertFalse(versions.containsKey("junit-jupiter"));
            assertNotNull(archive.getJarEntry("META-INF/LICENSE"));
        }
    }

    private String invoke(String args, String stdin) throws Exception {
        int status = launch(args, stdin);
        String output = Files.readString(directory.resolve("stdout"));
        assertEquals(0, status, output + Files.readString(directory.resolve("stderr")));
        return output;
    }

    private void assertFailure(String args) throws Exception {
        assertNotEquals(0, launch(args, ""));
    }

    private int launch(String args, String stdin) throws Exception {
        Path maven = Path.of(System.getProperty("launcher.maven"));
        Path repository = Path.of(System.getProperty("launcher.repository"));
        Path boot;
        try (java.util.stream.Stream<Path> entries = Files.list(maven.resolve("boot"))) {
            boot = entries.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst().orElseThrow();
        }
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add(System.getProperty("launcher.agent").replace("\"", ""));
        command.add("-Dmaven.home=" + maven);
        command.add("-Dmaven.multiModuleProjectDirectory=" + directory);
        command.add("-Dclassworlds.conf=" + maven.resolve("bin/m2.conf"));
        command.addAll(List.of("-cp", boot.toString(), "org.codehaus.plexus.classworlds.launcher.Launcher",
            "-q", "-ntp", "-Dstyle.color=never", "-Dmaven.repo.local=" + repository,
            "io.github.cs1302uga:cs1302-tracer-maven-plugin:" + System.getProperty("launcher.version") + ":run"));
        if (args != null) { command.add("-Dtracer.args=" + args); }
        Path input = directory.resolve("stdin");
        Files.writeString(input, stdin);
        Process process = new ProcessBuilder(command).directory(directory.toFile())
            .redirectInput(input.toFile()).redirectOutput(directory.resolve("stdout").toFile())
            .redirectError(directory.resolve("stderr").toFile()).start();
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Maven launch timed out");
            return process.exitValue();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
