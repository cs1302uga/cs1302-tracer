package cs1302.tracer.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Launches the CLI in its own JVM, with the plugin's resolved runtime dependencies. */
@Mojo(name = "run", requiresProject = false, threadSafe = true)
public final class RunMojo extends AbstractMojo {

    /** CLI arguments, with whitespace separation and single or double quoted values. */
    @Parameter(property = "tracer.args", defaultValue = "--help")
    private String args;

    /** Maven-resolved plugin dependencies, independent of the caller's project. */
    @Parameter(defaultValue = "${plugin.artifacts}", readonly = true, required = true)
    private List<Artifact> artifacts;

    /** Creates the Maven-managed goal. */
    public RunMojo() {} // RunMojo

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable(System.getProperty("java.home"), System.getProperty("os.name")));
        command.add("-cp");
        command.add(this.artifacts.stream().map(Artifact::getFile).map(File::getAbsolutePath)
            .collect(Collectors.joining(File.pathSeparator)));
        command.add("cs1302.tracer.App");
        command.addAll(arguments(this.args));
        run(new ProcessBuilder(command).inheritIO());
    } // execute

    /**
     * Selects Java from Maven's running JDK without depending on optional process metadata.
     *
     * @param home running JDK home
     * @param operatingSystem operating-system name
     * @return platform-specific Java executable
     */
    static String javaExecutable(String home, String operatingSystem) {
        return Path.of(home, "bin", operatingSystem.startsWith("Windows") ? "java.exe" : "java").toString();
    } // javaExecutable

    /**
     * Runs one child, cleaning up the child tree on interruption and JVM shutdown.
     *
     * @param builder configured child process
     * @throws MojoExecutionException if the process cannot be started or is interrupted
     * @throws MojoFailureException if the CLI returns failure
     */
    static void run(ProcessBuilder builder) throws MojoExecutionException, MojoFailureException {
        try {
            Process child = builder.start();
            Thread shutdown = new Thread(() -> terminate(child), "tracer-launcher-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                int status = child.waitFor();
                if (status != 0) {
                    throw new MojoFailureException("Tracer exited with status " + status);
                } // if
            } catch (InterruptedException exception) {
                terminate(child);
                Thread.currentThread().interrupt();
                throw new MojoExecutionException("Tracer launch interrupted", exception);
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } // try
        } catch (IOException exception) {
            throw new MojoExecutionException("Cannot launch tracer", exception);
        } // try
    } // run

    /**
     * Stops owned descendants before their parent can orphan them.
     *
     * @param child CLI process
     */
    static void terminate(Process child) {
        List<ProcessHandle> descendants = child.descendants().toList();
        descendants.reversed().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
    } // terminate

    /**
     * Splits arguments without shell expansion; backslashes remain literal.
     *
     * @param text command arguments after the invoking shell has processed quoting
     * @return ordered arguments, including explicitly quoted empty arguments
     * @throws MojoFailureException for an unclosed quote
     */
    static List<String> arguments(String text) throws MojoFailureException {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        char quote = 0;
        boolean present = false;
        for (char character : text.toCharArray()) {
            if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    value.append(character);
                } // if
            } else if (character == '\'' || character == '"') {
                quote = character;
                present = true;
            } else if (Character.isWhitespace(character)) {
                if (present) {
                    result.add(value.toString());
                    value.setLength(0);
                    present = false;
                } // if
            } else {
                value.append(character);
                present = true;
            } // if
        } // for
        if (quote != 0) {
            throw new MojoFailureException("Unclosed quote in tracer.args");
        } // if
        if (present) {
            result.add(value.toString());
        } // if
        return result;
    } // arguments

} // RunMojo
