package cs1302.tracer;

import org.apiguardian.api.API;

/**
 * Stable command-line launcher that writes to the process standard streams and may terminate the JVM.
 * Embedding applications use {@link cs1302.tracer.api.Tracer} or {@link cs1302.tracer.api.BatchTracer}.
 */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class App {

    /** Prevents launcher construction. */
    private App() {} // App

    /**
     * Executes a CLI invocation, returning its exit status when the selected command returns.
     * Subcommands can terminate the calling JVM through {@link System#exit(int)} on failure or a controlled stop.
     * The environment-selected Java and compiler must be matching stable JDK 21 or later tools,
     * and the selected JDK must be the running JVM. Environment failures return 1 with stderr diagnostics,
     * including for help and version requests. Arguments and results follow the
     * <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/cli.md">CLI reference</a>.
     * Embedding callers use {@link cs1302.tracer.api.Tracer} or {@link cs1302.tracer.api.BatchTracer}.
     *
     * @param args Command-line arguments.
     * @return CLI exit status if command execution returns; not reached when a subcommand terminates the JVM.
     */
    public static int execute(String[] args) {
        return cs1302.tracer.internal.App.execute(args);
    } // execute

    /**
     * Executes the command line and terminates the JVM with the resulting exit status.
     *
     * @param args Command-line arguments.
     * @throws Exception If command-line startup fails.
     */
    public static void main(String[] args) throws Exception {
        cs1302.tracer.internal.App.main(args);
    } // main

} // App
