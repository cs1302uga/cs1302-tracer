package cs1302.tracer;

import org.apiguardian.api.API;

/** Stable command-line launcher; embedding applications use {@link cs1302.tracer.api.Tracer}. */
@API(status = API.Status.STABLE, since = "4.0.0")
public final class App {

    /** Prevents launcher construction. */
    private App() {} // App

    /**
     * Executes a CLI invocation without terminating the calling JVM.
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
