package cs1302.tracer.guest;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Resident execution harness running inside the persistent guest JVM.
 *
 * <p>The harness coordinates job execution dispatched by the tracer host,
 * virtualizes standard input, isolates class loading via disposable
 * child {@link URLClassLoader} instances, and notifies the host debugger
 * via breakpoint sentinel hooks.</p>
 */
public final class GuestHarness {

    /** Dispatch parameter: target classpath set by host debugger. */
    public static volatile String nextClassPath = null;

    /** Dispatch parameter: target main entry class name set by host debugger. */
    public static volatile String nextMainClass = null;

    /** Dispatch parameter: target standard input content set by host debugger. */
    public static volatile String nextStdin = null;

    /** Control flag: instructs harness event loop to terminate process. */
    public static volatile boolean shouldTerminate = false;

    private static final VirtualInputStream VIRTUAL_IN = new VirtualInputStream();
    private static final Properties ORIGINAL_PROPERTIES =
            (Properties) System.getProperties().clone();

    /** Prevents instantiation of utility harness. */
    private GuestHarness() {} // GuestHarness

    /**
     * Main entry point for the guest JVM worker process.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        System.setIn(VIRTUAL_IN);

        while (true) {
            readyForJob();

            if (shouldTerminate) {
                break;
            } // if

            if (nextClassPath == null || nextMainClass == null) {
                continue;
            } // if

            runJob();
        } // while
    } // main

    /**
     * Sentinel hook invoked when the harness is idle and waiting for a job.
     * The host debugger places a breakpoint here to synchronize before dispatching.
     */
    public static void readyForJob() {
        // Breakpoint hook for host debugger
    } // readyForJob

    /**
     * Sentinel hook invoked when a job execution completes.
     * The host debugger places a breakpoint here to finalize snapshots and output capture.
     */
    public static void onJobCompleted() {
        // Breakpoint hook for host debugger
    } // onJobCompleted

    /**
     * Executes the target job using a disposable classloader and redirected input.
     */
    static void runJob() {
        String cp = nextClassPath;
        String mc = nextMainClass;
        String stdin = nextStdin;

        VIRTUAL_IN.reset(stdin != null ? stdin : "");

        try {
            File cpFile = new File(cp);
            URL[] urls = new URL[] {cpFile.toURI().toURL()};
            try (URLClassLoader loader = new URLClassLoader(
                    urls, GuestHarness.class.getClassLoader())) {
                Class<?> mainClass = Class.forName(mc, true, loader);
                Method mainMethod = mainClass.getMethod("main", String[].class);
                mainMethod.invoke(null, (Object) new String[0]);
            } // try
        } catch (Throwable t) {
            // Handled or ignored; snapshot or exception event captured by JDI
        } finally {
            onJobCompleted();
            cleanState();
        } // try
    } // runJob

    /**
     * Cleans up transient state between jobs.
     */
    static void cleanState() {
        nextClassPath = null;
        nextMainClass = null;
        nextStdin = null;
        System.setProperties((Properties) ORIGINAL_PROPERTIES.clone());
    } // cleanState

    /**
     * Resettable virtual input stream simulating guest standard input.
     */
    static final class VirtualInputStream extends InputStream {
        private volatile byte[] buffer = new byte[0];
        private int pos = 0;

        /** Constructs a new VirtualInputStream. */
        VirtualInputStream() {} // VirtualInputStream

        /**
         * Resets the input buffer with new input string.
         *
         * @param input String to supply as standard input.
         */
        synchronized void reset(String input) {
            this.buffer = input != null ? input.getBytes(StandardCharsets.UTF_8) : new byte[0];
            this.pos = 0;
        } // reset

        @Override
        public synchronized int read() {
            if (pos >= buffer.length) {
                return -1;
            } // if
            return buffer[pos++] & 0xFF;
        } // read

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (pos >= buffer.length) {
                return -1;
            } // if
            int available = buffer.length - pos;
            int toRead = Math.min(len, available);
            System.arraycopy(buffer, pos, b, off, toRead);
            pos += toRead;
            return toRead;
        } // read

        @Override
        public synchronized int available() {
            return Math.max(0, buffer.length - pos);
        } // available
    } // VirtualInputStream
}
