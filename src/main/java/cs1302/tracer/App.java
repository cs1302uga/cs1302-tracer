package cs1302.tracer;

import org.apiguardian.api.API;

/** Stable command-line launcher; embedding applications use {@link cs1302.tracer.api.Tracer}. */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class App {

    /** Prevents launcher construction. */
    private App() {} // App

    /**
     * Executes a CLI invocation without terminating the calling JVM.
     * The environment-selected Java and compiler must be matching stable JDK 21 or later tools,
     * and the selected JDK must be the running JVM. Environment failures return 1 with stderr diagnostics,
     * including for help and version requests. Embedding callers use {@link cs1302.tracer.api.Tracer}.
     *
     * @param args Command-line arguments.
     * @return Process exit status.
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
