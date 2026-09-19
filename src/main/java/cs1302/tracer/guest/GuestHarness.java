package cs1302.tracer.guest;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final InputStream ORIGINAL_IN = System.in;
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
        System.setOut(createForwardingPrintStream(ORIGINAL_OUT));
        System.setErr(createForwardingPrintStream(ORIGINAL_ERR));

        while (true) {
            readyForJob();

            if (shouldTerminate) {
                break;
            } // if

            if (nextClassPath == null || nextMainClass == null) {
                continue;
            } // if

            runJob();

            if (shouldTerminate) {
                break;
            } // if
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
                    urls, ClassLoader.getPlatformClassLoader())) {
                ClassLoader originalContextLoader =
                        Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(loader);
                try {
                    Class<?> mainClass = Class.forName(mc, true, loader);
                    Method mainMethod = mainClass.getMethod("main", String[].class);
                    mainMethod.invoke(null, (Object) new String[0]);
                } finally {
                    Thread.currentThread().setContextClassLoader(originalContextLoader);
                    stopLingeringThreads(loader);
                } // try
            } // try
        } catch (Throwable t) {
            // Handled or ignored; snapshot or exception event captured by JDI
        } finally {
            onJobCompleted();
            cleanState();
        } // try
    } // runJob

    /**
     * Interrupts and awaits termination of any background threads started by the target.
     *
     * @param loader Current job classloader.
     */
    static void stopLingeringThreads(ClassLoader loader) {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        } // while
        Thread[] threads = new Thread[rootGroup.activeCount() * 2 + 16];
        int n = rootGroup.enumerate(threads, true);
        Thread current = Thread.currentThread();
        List<Thread> targets = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Thread t = threads[i];
            if (t != current && t.getContextClassLoader() == loader) {
                targets.add(t);
                t.interrupt();
            } // if
        } // for
        for (Thread t : targets) {
            try {
                t.join(50);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            } // try
        } // for
        for (Thread t : targets) {
            if (t.isAlive()) {
                shouldTerminate = true;
                break;
            } // if
        } // for
    } // stopLingeringThreads

    /**
     * Cleans up transient state between jobs.
     */
    static void cleanState() {
        nextClassPath = null;
        nextMainClass = null;
        nextStdin = null;
        VIRTUAL_IN.reset("");

        System.setIn(ORIGINAL_IN);
        System.setOut(createForwardingPrintStream(ORIGINAL_OUT));
        System.setErr(createForwardingPrintStream(ORIGINAL_ERR));

        System.setProperties((Properties) ORIGINAL_PROPERTIES.clone());
        System.setIn(VIRTUAL_IN);
    } // cleanState

    /**
     * Filter stream preventing target student code from closing persistent system streams.
     */
    static final class UnclosableOutputStream extends FilterOutputStream {
        /**
         * Constructs an unclosable stream wrapping target stream.
         *
         * @param out Underlying output stream.
         */
        UnclosableOutputStream(OutputStream out) {
            super(out);
        } // UnclosableOutputStream

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        } // write

        @Override
        public void close() throws IOException {
            flush();
        } // close
    } // UnclosableOutputStream

    /**
     * Creates a forwarding print stream protected against student closing.
     *
     * @param original Underlying print stream.
     * @return Non-closeable forwarding print stream.
     */
    static PrintStream createForwardingPrintStream(PrintStream original) {
        return new PrintStream(new UnclosableOutputStream(original), true, StandardCharsets.UTF_8);
    } // createForwardingPrintStream

    /**
     * In-memory redirected input stream dynamically backed by string content.
     */
    public static final class VirtualInputStream extends InputStream {

        private byte[] buffer;
        private int pos;

        /**
         * Constructs an empty virtual input stream.
         */
        public VirtualInputStream() {
            this.buffer = new byte[0];
            this.pos = 0;
        } // VirtualInputStream

        /**
         * Resets the input content to the given string.
         *
         * @param content New standard input text.
         */
        public synchronized void reset(String content) {
            if (content == null) {
                this.buffer = new byte[0];
            } else {
                this.buffer = content.getBytes(StandardCharsets.UTF_8);
            } // if
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
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            } // if
            if (pos >= buffer.length) {
                return -1;
            } // if
            int available = buffer.length - pos;
            int count = Math.min(len, available);
            System.arraycopy(buffer, pos, b, off, count);
            pos += count;
            return count;
        } // read

        @Override
        public synchronized int available() {
            return Math.max(0, buffer.length - pos);
        } // available
    } // VirtualInputStream
}
