package cs1302.tracer.guest;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

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

    /** Monotonic byte counter for guest standard output. */
    public static final AtomicLong OUT_BYTES = new AtomicLong(0);

    /** Monotonic byte counter for guest standard error. */
    public static final AtomicLong ERR_BYTES = new AtomicLong(0);

    /** Barrier snapshot of total standard output bytes at last completion. */
    public static volatile long outBytes = 0;

    /** Barrier snapshot of total standard error bytes at last completion. */
    public static volatile long errBytes = 0;

    /** Harness failure message for the current job, or null. */
    public static volatile String lastHarnessFailure = null;

    private static final VirtualInputStream VIRTUAL_IN = new VirtualInputStream();
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;
    private static final InputStream ORIGINAL_IN = System.in;
    private static final Thread.UncaughtExceptionHandler ORIGINAL_UNCAUGHT_EXCEPTION_HANDLER =
            Thread.getDefaultUncaughtExceptionHandler();
    private static final Properties ORIGINAL_PROPERTIES =
            (Properties) System.getProperties().clone();
    private static final Locale ORIGINAL_DEFAULT_LOCALE = Locale.getDefault();
    private static final Locale ORIGINAL_FORMAT_LOCALE = Locale.getDefault(Locale.Category.FORMAT);
    private static final Locale ORIGINAL_DISPLAY_LOCALE =
            Locale.getDefault(Locale.Category.DISPLAY);
    private static final TimeZone ORIGINAL_TIME_ZONE = (TimeZone) TimeZone.getDefault().clone();

    /** Prevents instantiation of utility harness. */
    private GuestHarness() {} // GuestHarness

    /**
     * Main entry point for the guest JVM worker process.
     *
     * @param args Command-line arguments.
     */
    public static void main(String[] args) {
        System.setIn(VIRTUAL_IN);
        System.setOut(createForwardingPrintStream(ORIGINAL_OUT, OUT_BYTES));
        System.setErr(createForwardingPrintStream(ORIGINAL_ERR, ERR_BYTES));

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
     * Finds and invokes the entry point on the specified class matching Java main method rules.
     *
     * @param targetClass The loaded class containing the main method.
     * @throws Exception If resolution, instantiation, or invocation fails.
     */
    private static void invokeMain(Class<?> targetClass) throws Exception {
        Method mainMethod = findMainMethod(targetClass);
        if (mainMethod == null) {
            throw new NoSuchMethodException(
                    "No suitable main method found on " + targetClass.getName());
        } // if
        mainMethod.setAccessible(true);
        Object receiver = null;
        if (!Modifier.isStatic(mainMethod.getModifiers())) {
            Constructor<?> ctor = targetClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            receiver = ctor.newInstance();
        } // if
        if (mainMethod.getParameterCount() == 0) {
            mainMethod.invoke(receiver);
        } else {
            mainMethod.invoke(receiver, (Object) new String[0]);
        } // if
    } // invokeMain

    /**
     * Finds the entry point main method on the target class or its superclasses.
     *
     * @param targetClass Target class to search.
     * @return Discovered main Method, or null if none found.
     */
    static Method findMainMethod(Class<?> targetClass) {
        for (Class<?> c = targetClass; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (isMainMethod(m)) {
                    return m;
                } // if
            } // for
        } // for
        return null;
    } // findMainMethod

    /**
     * Determines whether the specified method matches Java main method entry rules.
     *
     * @param m Method to inspect.
     * @return True if method matches main signature.
     */
    static boolean isMainMethod(Method m) {
        if (!m.getName().equals("main") || !m.getReturnType().equals(void.class)) {
            return false;
        } // if
        Class<?>[] params = m.getParameterTypes();
        return params.length == 0 || (params.length == 1 && params[0].equals(String[].class));
    } // isMainMethod

    /**
     * Executes the target job using a disposable classloader and redirected input.
     */
    static void runJob() {
        OUT_BYTES.set(0);
        ERR_BYTES.set(0);
        lastHarnessFailure = null;
        String cp = nextClassPath;
        String mc = nextMainClass;
        String stdin = nextStdin;

        VIRTUAL_IN.reset(stdin != null ? stdin : "");

        try {
            File cpFile = new File(cp);
            URL[] urls = new URL[] {cpFile.toURI().toURL()};
            ThreadGroup jobGroup = new ThreadGroup("student-job-group");
            try (URLClassLoader loader = new URLClassLoader(
                    urls, ClassLoader.getPlatformClassLoader())) {
                Thread jobThread = new Thread(jobGroup, () -> {
                    try {
                        Class<?> mainClass = Class.forName(mc, true, loader);
                        invokeMain(mainClass);
                    } catch (InvocationTargetException ignored) {
                        // Student failure is reported through JDI events
                    } catch (ReflectiveOperationException reflectiveFailure) {
                        lastHarnessFailure = reflectiveFailure.toString();
                    } catch (Throwable t) {
                        // Handled or ignored; snapshot or exception event captured by JDI
                    } // try
                }, "student-main");
                jobThread.setContextClassLoader(loader);
                jobThread.start();
                try {
                    jobThread.join();
                } catch (InterruptedException e) {
                    jobThread.interrupt();
                    Thread.currentThread().interrupt();
                } // try
                stopLingeringThreads(loader, jobGroup);
            } // try
        } catch (Throwable t) {
            lastHarnessFailure = t.toString();
        } finally {
            Thread.interrupted();
            ORIGINAL_OUT.flush();
            ORIGINAL_ERR.flush();
            outBytes = OUT_BYTES.get();
            errBytes = ERR_BYTES.get();
            onJobCompleted();
            cleanState();
        } // try
    } // runJob

    /**
     * Enumerates all active threads in the specified thread group.
     *
     * @param rootGroup Root thread group to inspect.
     * @return Array of all enumerated active threads.
     */
    static Thread[] enumerateAllThreads(ThreadGroup rootGroup) {
        return enumerateAllThreads(rootGroup, Math.max(1, rootGroup.activeCount()));
    } // enumerateAllThreads

    /**
     * Enumerates all active threads with specified initial capacity.
     *
     * @param rootGroup Root thread group to inspect.
     * @param initialCapacity Initial array capacity.
     * @return Array of all enumerated active threads.
     */
    static Thread[] enumerateAllThreads(ThreadGroup rootGroup, int initialCapacity) {
        Thread[] threads = new Thread[Math.max(1, initialCapacity)];
        int n;
        while ((n = rootGroup.enumerate(threads, true)) == threads.length) {
            threads = new Thread[threads.length * 2];
        } // while
        Thread[] result = new Thread[n];
        System.arraycopy(threads, 0, result, 0, n);
        return result;
    } // enumerateAllThreads

    /**
     * Interrupts and awaits termination of any background threads started by the target.
     *
     * @param loader Current job classloader.
     */
    static void stopLingeringThreads(ClassLoader loader) {
        stopLingeringThreads(loader, null);
    } // stopLingeringThreads

    /**
     * Interrupts and awaits termination of lingering background threads.
     *
     * @param loader Current job classloader.
     * @param jobGroup Job-owned thread group.
     */
    static void stopLingeringThreads(ClassLoader loader, ThreadGroup jobGroup) {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        } // while
        Thread[] threads = enumerateAllThreads(rootGroup);
        Thread current = Thread.currentThread();
        List<Thread> targets = new ArrayList<>();
        for (Thread t : threads) {
            if (t != current && isJobThread(t, loader, jobGroup)) {
                targets.add(t);
                t.interrupt();
            } // if
        } // for
        for (Thread t : targets) {
            try {
                t.join(250);
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
     * Checks if a thread was created by or belongs to the current job.
     *
     * @param t Target thread to test.
     * @param loader Current job classloader.
     * @param jobGroup Job-owned thread group.
     * @return True if thread belongs to current job.
     */
    static boolean isJobThread(Thread t, ClassLoader loader, ThreadGroup jobGroup) {
        if (t.getContextClassLoader() == loader) {
            return true;
        } // if
        ThreadGroup g = t.getThreadGroup();
        while (g != null) {
            if (g == jobGroup) {
                return true;
            } // if
            g = g.getParent();
        } // while
        return false;
    } // isJobThread

    /**
     * Cleans up transient state between jobs.
     */
    static void cleanState() {
        Thread.interrupted();
        nextClassPath = null;
        nextMainClass = null;
        nextStdin = null;
        VIRTUAL_IN.reset("");

        Locale.setDefault(ORIGINAL_DEFAULT_LOCALE);
        Locale.setDefault(Locale.Category.FORMAT, ORIGINAL_FORMAT_LOCALE);
        Locale.setDefault(Locale.Category.DISPLAY, ORIGINAL_DISPLAY_LOCALE);
        TimeZone.setDefault((TimeZone) ORIGINAL_TIME_ZONE.clone());

        System.setIn(ORIGINAL_IN);
        System.setOut(createForwardingPrintStream(ORIGINAL_OUT, OUT_BYTES));
        System.setErr(createForwardingPrintStream(ORIGINAL_ERR, ERR_BYTES));
        Thread.setDefaultUncaughtExceptionHandler(ORIGINAL_UNCAUGHT_EXCEPTION_HANDLER);

        System.setProperties((Properties) ORIGINAL_PROPERTIES.clone());
        System.setIn(VIRTUAL_IN);
        lastHarnessFailure = null;
    } // cleanState

    /**
     * Filter stream preventing target student code from closing persistent system streams.
     */
    static final class UnclosableOutputStream extends FilterOutputStream {
        private final AtomicLong counter;

        /**
         * Constructs an unclosable stream wrapping target stream.
         *
         * @param out Underlying output stream.
         * @param counter Monotonic byte counter.
         */
        UnclosableOutputStream(OutputStream out, AtomicLong counter) {
            super(out);
            this.counter = counter;
        } // UnclosableOutputStream

        /**
         * Constructs an unclosable stream defaulting to a zero counter.
         *
         * @param out Underlying output stream.
         */
        UnclosableOutputStream(OutputStream out) {
            this(out, new AtomicLong(0));
        } // UnclosableOutputStream

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            counter.incrementAndGet();
        } // write

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            counter.addAndGet(len);
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
     * @param counter Monotonic byte counter.
     * @return Non-closeable forwarding print stream.
     */
    static PrintStream createForwardingPrintStream(PrintStream original, AtomicLong counter) {
        return new PrintStream(
                new UnclosableOutputStream(original, counter), true, StandardCharsets.UTF_8) {
            @Override
            public void close() {
                flush();
            } // close
        };
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
} // GuestHarness
