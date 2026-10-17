package cs1302.tracer.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates the selected CLI tools without changing the running JVM or library callers. */
final class CliEnvironment {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    private static final int OUTPUT_LIMIT = 65536;
    private static final String REMEDY = " Set JAVA_HOME to a full stable JDK 21 or later, or unset it and put"
        + " that JDK's bin directory first on PATH.";

    /** Prevents instantiation. */
    private CliEnvironment() {} // CliEnvironment

    /**
     * Checks tool selection, versions, and the identity of the running JDK.
     *
     * @param environment process environment
     * @param runningHome running JVM's Java home
     * @throws IOException if the environment cannot run this CLI
     * @throws InterruptedException if a probe is interrupted
     */
    static void validate(Map<String, String> environment, Path runningHome) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = select(environment, "java", windows);
        Path javac = select(environment, "javac", windows);
        String javaOutput = probe(List.of(java.toString(), "-XshowSettings:properties", "--version"), PROBE_TIMEOUT);
        String javacOutput = probe(List.of(javac.toString(), "--version"), PROBE_TIMEOUT);
        Runtime.Version runtime = version(javaOutput, "(?:openjdk|java)", java);
        Runtime.Version compiler = version(javacOutput, "javac", javac);
        if (!runtime.version().equals(compiler.version())) {
            throw failure("Java/compiler versions differ: " + java + " reports " + runtime + "; "
                + javac + " reports " + compiler + ".");
        } // if
        Matcher home = Pattern.compile("(?m)^\\s*java\\.home = (.+)$").matcher(javaOutput);
        if (!home.find()) {
            throw failure("Cannot determine the selected Java home from " + java + ".");
        } // if
        Path selectedHome = Path.of(home.group(1).strip());
        if (!Files.isSameFile(selectedHome, runningHome)) {
            throw failure("The running JDK " + runningHome + " differs from the selected JDK " + selectedHome
                + ". Launch with " + java + " -jar code-tracer.jar.");
        } // if
    } // validate

    /**
     * Selects a real executable using authoritative JAVA_HOME or executable search on PATH.
     *
     * @param environment process environment
     * @param tool java or javac
     * @param windows whether executable names use the Windows suffix
     * @return selected executable
     * @throws IOException if the selected executable is unavailable
     */
    static Path select(Map<String, String> environment, String tool, boolean windows) throws IOException {
        String name = tool + (windows ? ".exe" : "");
        String home = variable(environment, "JAVA_HOME", windows);
        if (!home.isEmpty()) {
            Path executable = Path.of(home, "bin", name).toAbsolutePath();
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                return executable;
            } // if
            throw failure("JAVA_HOME does not select an executable " + name + ": " + executable + ".");
        } // if
        String path = variable(environment, "PATH", windows);
        String[] entries = path.isEmpty() ? new String[0] : path.split(Pattern.quote(windows ? ";" : ":"), -1);
        for (String entry : entries) {
            Path executable = Path.of(entry, name).toAbsolutePath();
            if (Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                return executable;
            } // if
        } // for
        throw failure("No executable " + name + " was found on PATH.");
    } // select

    /**
     * Reads environment names with the platform's case sensitivity.
     *
     * @param environment process environment
     * @param name variable name
     * @param windows whether names are case insensitive
     * @return value, or the empty string when absent
     */
    private static String variable(Map<String, String> environment, String name, boolean windows) {
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (windows ? entry.getKey().equalsIgnoreCase(name) : entry.getKey().equals(name)) {
                return entry.getValue();
            } // if
        } // for
        return "";
    } // variable

    /**
     * Parses a tool's release token and enforces the supported stable release minimum.
     *
     * @param output combined version-command output
     * @param tool banner prefix pattern
     * @param executable selected path for diagnostics
     * @return parsed compatible version
     * @throws IOException if output is unreadable or incompatible
     */
    static Runtime.Version version(String output, String tool, Path executable) throws IOException {
        Matcher banner = Pattern.compile("(?m)^" + tool + " (?:version )?\"?([^\\s\"]+)").matcher(output);
        if (!banner.find()) {
            throw failure("Cannot read the version reported by " + executable + ".");
        } // if
        String token = banner.group(1);
        Runtime.Version parsed;
        try {
            parsed = Runtime.Version.parse(token);
        } catch (IllegalArgumentException invalid) {
            throw failure("Unrecognized or unsupported version '" + token + "' from " + executable + ".");
        } // try
        if (parsed.feature() < 21 || parsed.pre().isPresent()) {
            throw failure("Unsupported version '" + token + "' from " + executable
                + "; a stable JDK 21 or later is required.");
        } // if
        return parsed;
    } // version

    /**
     * Runs a bounded probe without forwarding its streams to the user's command output.
     *
     * @param command executable and arguments
     * @param timeout maximum time per probe
     * @return captured output
     * @throws IOException if startup, exit status, output size, or deadline is invalid
     * @throws InterruptedException if the caller is interrupted
     */
    static String probe(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Path output = Files.createTempFile("code-tracer-jdk-", ".txt");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) {
                    throw failure("Timed out probing " + command.get(0) + " after " + timeout.toMillis() + " ms.");
                } // if
                if (Files.size(output) > OUTPUT_LIMIT) {
                    throw failure("Excessive version output from " + command.get(0) + ".");
                } // if
            } // while
            if (process.exitValue() != 0) {
                throw failure("Version probe failed for " + command.get(0) + " (exit " + process.exitValue() + ").");
            } // if
            try (InputStream input = Files.newInputStream(output)) {
                byte[] bytes = input.readNBytes(OUTPUT_LIMIT + 1);
                if (bytes.length > OUTPUT_LIMIT) {
                    throw failure("Excessive version output from " + command.get(0) + ".");
                } // if
                return new String(bytes, Charset.defaultCharset());
            } // try
        } finally {
            try {
                if (process != null) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                } // if
            } finally {
                Files.deleteIfExists(output);
            } // try
        } // try
    } // probe

    /**
     * Adds consistent recovery instructions to an environment diagnostic.
     *
     * @param message specific failure
     * @return diagnostic exception
     */
    private static IOException failure(String message) {
        return new IOException(message + REMEDY);
    } // failure

} // CliEnvironment
