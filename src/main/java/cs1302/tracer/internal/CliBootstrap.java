package cs1302.tracer.internal;

import java.util.function.IntConsumer;

/** Java 8-compatible JAR entry point that diagnoses old runtimes before loading Java 21 classes. */
final class CliBootstrap {

    static IntConsumer exit = System::exit;

    /** Prevents instantiation. */
    private CliBootstrap() {} // CliBootstrap

    /**
     * Checks the runtime floor, then delegates to the CLI's complete environment validation.
     *
     * @param args command arguments
     * @throws ReflectiveOperationException if the packaged CLI entry point is missing
     */
    public static void main(String[] args) throws ReflectiveOperationException {
        if (!System.getProperty("java.specification.version", "").matches("2[1-9]|[3-9][0-9]|[1-9][0-9]{2,}")) {
            System.err.println("code-tracer: a stable JDK 21 or later is required; running Java "
                + System.getProperty("java.version") + " from " + System.getProperty("java.home")
                + ". Set JAVA_HOME to a compatible full JDK or correct PATH.");
            exit.accept(1);
            return;
        } // if
        Class<?> cli = Class.forName("cs1302.tracer.internal.App");
        int status = (Integer) cli.getMethod("execute", String[].class).invoke(null, (Object) args);
        exit.accept(status);
    } // main

} // CliBootstrap
