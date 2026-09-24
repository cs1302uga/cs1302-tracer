package cs1302.tracer.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GuestHarness} and {@link GuestHarness.VirtualInputStream}.
 */
public class GuestHarnessTest {

    private InputStream originalIn;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private Thread.UncaughtExceptionHandler originalUncaughtExceptionHandler;

    @BeforeEach
    void setUpHarness() {
        originalIn = System.in;
        originalOut = System.out;
        originalErr = System.err;
        originalUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        resetHarnessState();
    } // setUpHarness

    @AfterEach
    void tearDownHarness() {
        resetHarnessState();
        System.setIn(originalIn);
        System.setOut(originalOut);
        System.setErr(originalErr);
        Thread.setDefaultUncaughtExceptionHandler(originalUncaughtExceptionHandler);
    } // tearDownHarness

    private static void resetHarnessState() {
        GuestHarness.nextClassPath = null;
        GuestHarness.nextMainClass = null;
        GuestHarness.nextStdin = null;
        GuestHarness.shouldTerminate = false;
        GuestHarness.cleanState();
        DummyTarget.MARKER.delete();
        InstanceNoArgsTarget.MARKER.delete();
        InstanceArgsTarget.MARKER.delete();
        StaticNoArgsTarget.MARKER.delete();
    } // resetHarnessState

    @Test
    void testPrivateConstructor() throws Exception {
        Constructor<GuestHarness> c = GuestHarness.class.getDeclaredConstructor();
        c.setAccessible(true);
        GuestHarness instance = c.newInstance();
        assertThat(instance).isNotNull();
    } // testPrivateConstructor

    @Test
    void testVirtualInputStream() throws Exception {
        GuestHarness.VirtualInputStream in = new GuestHarness.VirtualInputStream();
        assertThat(in.available()).isEqualTo(0);
        assertThat(in.read()).isEqualTo(-1);

        byte[] buf = new byte[10];
        assertThat(in.read(buf, 0, buf.length)).isEqualTo(-1);

        in.reset("ABC");
        assertThat(in.available()).isEqualTo(3);
        assertThat(in.read()).isEqualTo((int) 'A');
        assertThat(in.available()).isEqualTo(2);

        int readCount = in.read(buf, 0, 2);
        assertThat(readCount).isEqualTo(2);
        assertThat(new String(buf, 0, readCount, StandardCharsets.UTF_8)).isEqualTo("BC");
        assertThat(in.available()).isEqualTo(0);
        assertThat(in.read()).isEqualTo(-1);
        assertThat(in.read(buf, 0, 1)).isEqualTo(-1);

        in.reset(null);
        assertThat(in.available()).isEqualTo(0);
        assertThat(in.read()).isEqualTo(-1);
    } // testVirtualInputStream

    @Test
    void testSentinelMethods() {
        GuestHarness.readyForJob();
        GuestHarness.onJobCompleted();
    } // testSentinelMethods

    @Test
    void testCleanStateAndRunJobWithMissingParams() {
        GuestHarness.cleanState();
        assertThat(GuestHarness.nextClassPath).isNull();
        assertThat(GuestHarness.nextMainClass).isNull();
        assertThat(GuestHarness.nextStdin).isNull();

        // Calling runJob when nextClassPath is null handles gracefully
        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();
    } // testCleanStateAndRunJobWithMissingParams

    @Test
    void testRunJobExecution() throws Exception {
        File targetDir = new File("target/test-classes");
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        GuestHarness.nextStdin = "sample input";

        DummyTarget.MARKER.delete();
        GuestHarness.runJob();

        assertThat(DummyTarget.MARKER.exists()).isTrue();
        DummyTarget.MARKER.delete();
        assertThat(GuestHarness.nextClassPath).isNull();
        assertThat(GuestHarness.nextMainClass).isNull();
    } // testRunJobExecution

    @Test
    void testRunJobInterrupted() {
        File targetDir = new File("target/test-classes");
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();

        Thread.currentThread().interrupt();
        GuestHarness.runJob();
        assertThat(Thread.interrupted()).isFalse();
    } // testRunJobInterrupted

    @Test
    void testRunJobTargetThrows() {
        File targetDir = new File("target/test-classes");
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = ThrowingTarget.class.getName();
        GuestHarness.nextStdin = null;

        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();
    } // testRunJobTargetThrows

    @Test
    void testMainTermination() {
        GuestHarness.shouldTerminate = true;
        GuestHarness.main(new String[0]);
    } // testMainTermination

    @Test
    void testMainIteration() {
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = null;
        GuestHarness.nextMainClass = null;

        // Run in thread and terminate
        Thread t = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });

        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            // ignore
        } // try

        GuestHarness.shouldTerminate = true;
        try {
            t.join(500);
        } catch (InterruptedException ignored) {
            // ignore
        } // try
    } // testMainIteration

    public static class DummyTarget {
        public static final File MARKER = new File("target/dummy-invoked.marker");

        public static void main(String[] args) {
            try {
                MARKER.createNewFile();
            } catch (Exception ignored) {
                // ignore
            } // try
        } // main
    } // DummyTarget

    public static class LingeringTarget {
        public static void main(String[] args) {
            Thread t = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        // ignore and continue lingering
                    } // try
                } // for
            });
            t.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            t.start();
        } // main
    } // LingeringTarget

    public static class ThrowingTarget {
        public static void main(String[] args) {
            throw new RuntimeException("Simulated error in guest target");
        } // main
    } // ThrowingTarget

    @Test
    void testMainCombinations() {
        // Test null variations and execution
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = "target/test-classes";
        GuestHarness.nextMainClass = null;

        Thread t1 = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });
        try {
            Thread.sleep(30);
        } catch (InterruptedException ignored) {
            // ignore
        } // try
        GuestHarness.shouldTerminate = true;
        try {
            t1.join(500);
        } catch (InterruptedException ignored) {
            // ignore
        } // try

        // nextClassPath null, nextMainClass set
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = null;
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        Thread t2 = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });
        try {
            Thread.sleep(30);
        } catch (InterruptedException ignored) {
            // ignore
        } // try
        GuestHarness.shouldTerminate = true;
        try {
            t2.join(500);
        } catch (InterruptedException ignored) {
            // ignore
        } // try

        // both set -> calls runJob
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = new File("target/test-classes").getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        DummyTarget.MARKER.delete();
        Thread t3 = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
            // ignore
        } // try
        GuestHarness.shouldTerminate = true;
        try {
            t3.join(500);
        } catch (InterruptedException ignored) {
            // ignore
        } // try
        assertThat(DummyTarget.MARKER.exists()).isTrue();
        DummyTarget.MARKER.delete();
    } // testMainCombinations

    @Test
    void testMainTerminatesOnLingeringThreadAfterRunJob() {
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = new File("target/test-classes").getAbsolutePath();
        GuestHarness.nextMainClass = LingeringTarget.class.getName();

        GuestHarness.main(new String[0]);
        assertThat(GuestHarness.shouldTerminate).isTrue();
    } // testMainTerminatesOnLingeringThreadAfterRunJob

    @Test
    void testEnumerateAllThreadsWithResize() {
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while (rootGroup.getParent() != null) {
            rootGroup = rootGroup.getParent();
        } // while
        Thread[] threads = GuestHarness.enumerateAllThreads(rootGroup, 1);
        assertThat(threads).isNotEmpty();
    } // testEnumerateAllThreadsWithResize

    @Test
    void testVirtualInputStreamZeroLengthRead() {
        GuestHarness.VirtualInputStream in = new GuestHarness.VirtualInputStream();
        in.reset("ABC");
        byte[] buf = new byte[10];
        assertThat(in.read(buf, 0, 0)).isEqualTo(0);
    } // testVirtualInputStreamZeroLengthRead

    @Test
    void testStopLingeringThreads() throws Exception {
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0]);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread lingering = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } // try
        });
        lingering.setContextClassLoader(loader);
        lingering.start();
        started.await();

        GuestHarness.stopLingeringThreads(loader);
        assertThat(interrupted.get()).isTrue();
        lingering.join(500);
        assertThat(lingering.isAlive()).isFalse();

        GuestHarness.stopLingeringThreads(Thread.currentThread().getContextClassLoader());
    } // testStopLingeringThreads

    @Test
    void testStopLingeringThreadsInterrupted() throws Exception {
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0]);
        Thread lingering = new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                // ignore
            } // try
        });
        lingering.setContextClassLoader(loader);
        lingering.start();

        Thread.currentThread().interrupt();
        try {
            GuestHarness.stopLingeringThreads(loader);
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            lingering.interrupt();
            lingering.join(500);
        } // try
    } // testStopLingeringThreadsInterrupted

    @Test
    void testStopLingeringThreadsUncooperativeThread() {
        GuestHarness.shouldTerminate = false;
        ClassLoader dummyLoader = new ClassLoader() {};
        Thread rogue = new Thread(() -> {
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // ignore and stay alive
                } // try
            } // for
        });
        rogue.setContextClassLoader(dummyLoader);
        rogue.start();
        try {
            GuestHarness.stopLingeringThreads(dummyLoader);
            assertThat(GuestHarness.shouldTerminate).isTrue();
        } finally {
            rogue.interrupt();
            try {
                rogue.join(500);
            } catch (InterruptedException ignored) {
                // ignore
            } // try
        } // try
    } // testStopLingeringThreadsUncooperativeThread

    @Test
    void testOutputStreamsProtectedAgainstClosing() {
        GuestHarness.cleanState();
        System.out.println("Line 1");
        System.out.close();
        System.err.close();
        GuestHarness.cleanState();
        System.out.println("Line 2");
        System.err.println("Error 2");
    } // testOutputStreamsProtectedAgainstClosing

    @Test
    void testCleanStateRestoresDefaultUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
        });
        GuestHarness.cleanState();
        assertThat(Thread.getDefaultUncaughtExceptionHandler()).isEqualTo(
                originalUncaughtExceptionHandler);
    } // testCleanStateRestoresDefaultUncaughtExceptionHandler

    @Test
    void testUnclosableOutputStream() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        GuestHarness.UnclosableOutputStream uos = new GuestHarness.UnclosableOutputStream(baos);
        byte[] data = "test data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        uos.write(data, 0, data.length);
        uos.close();
        uos.write((int) '!');
        assertThat(baos.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("test data!");
    } // testUnclosableOutputStream

    @Test
    void forwardingPrintStreamRemainsWritableAfterClose() {
        var bytes = new java.io.ByteArrayOutputStream();
        var original = new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8);
        var counter = new java.util.concurrent.atomic.AtomicLong();
        var stream = GuestHarness.createForwardingPrintStream(original, counter);
        stream.print("before");
        stream.close();
        stream.print("after");
        stream.close();
        assertThat(stream.checkError()).isFalse();
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("beforeafter");
        assertThat(counter.get()).isEqualTo(11);
        original.print("original");
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("beforeafteroriginal");
    } // forwardingPrintStreamRemainsWritableAfterClose

    @Test
    void testVariousMainEntryPoints() {
        File targetDir = new File("target/test-classes");

        // Instance void main()
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = InstanceNoArgsTarget.class.getName();
        InstanceNoArgsTarget.MARKER.delete();
        GuestHarness.runJob();
        assertThat(InstanceNoArgsTarget.MARKER.exists()).isTrue();
        InstanceNoArgsTarget.MARKER.delete();

        // Instance void main(String[])
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = InstanceArgsTarget.class.getName();
        InstanceArgsTarget.MARKER.delete();
        GuestHarness.runJob();
        assertThat(InstanceArgsTarget.MARKER.exists()).isTrue();
        InstanceArgsTarget.MARKER.delete();

        // Static void main()
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = StaticNoArgsTarget.class.getName();
        StaticNoArgsTarget.MARKER.delete();
        GuestHarness.runJob();
        assertThat(StaticNoArgsTarget.MARKER.exists()).isTrue();
        StaticNoArgsTarget.MARKER.delete();

        // Inherited main from superclass
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = InheritedTarget.class.getName();
        StaticNoArgsTarget.MARKER.delete();
        GuestHarness.runJob();
        assertThat(StaticNoArgsTarget.MARKER.exists()).isTrue();
        StaticNoArgsTarget.MARKER.delete();

        // Non-void main and wrong param type
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = NonVoidMainTarget.class.getName();
        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();

        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = WrongParamMainTarget.class.getName();
        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();

        // No main method
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = NoMainTarget.class.getName();
        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();
    } // testVariousMainEntryPoints

    /** Dummy target inheriting main. */
    public static class InheritedTarget extends StaticNoArgsTarget {
    } // InheritedTarget

    /** Dummy target with non-void main. */
    public static class NonVoidMainTarget {
        /** Non-void return main. */
        public static int main() {
            return 0;
        } // main
    } // NonVoidMainTarget

    /** Dummy target with wrong param type. */
    public static class WrongParamMainTarget {
        /** Main with int param. */
        public static void main(int x) {
        } // main
    } // WrongParamMainTarget

    /** Dummy target with multiple params for direct reflection test. */
    public static class MultiParamMainTarget {
        /** Main with multiple params. */
        public static void main(String[] a, int b) {
        } // main
    } // MultiParamMainTarget

    @Test
    void testIsMainMethodDirect() throws Exception {
        assertThat(GuestHarness.isMainMethod(
                MultiParamMainTarget.class.getMethod("main", String[].class, int.class)))
                .isFalse();
    } // testIsMainMethodDirect

    /** Dummy target with instance void main(). */
    public static class InstanceNoArgsTarget {
        public static final File MARKER = new File("target/instance-noargs.marker");

        /** Instance main without args. */
        void main() {
            try {
                MARKER.createNewFile();
            } catch (Exception ignored) {
                // ignore
            } // try
        } // main
    } // InstanceNoArgsTarget

    /** Dummy target with instance void main(String[]). */
    public static class InstanceArgsTarget {
        public static final File MARKER = new File("target/instance-args.marker");

        /**
         * Instance main with args.
         *
         * @param args Command-line arguments.
         */
        void main(String[] args) {
            try {
                MARKER.createNewFile();
            } catch (Exception ignored) {
                // ignore
            } // try
        } // main
    } // InstanceArgsTarget

    /** Dummy target with static void main(). */
    public static class StaticNoArgsTarget {
        public static final File MARKER = new File("target/static-noargs.marker");

        /** Static main without args. */
        static void main() {
            try {
                MARKER.createNewFile();
            } catch (Exception ignored) {
                // ignore
            } // try
        } // main
    } // StaticNoArgsTarget

    /** Dummy target without main method. */
    public static class NoMainTarget {
        /** Not a main method. */
        public static void notMain() {}
    } // NoMainTarget

    @Test
    void testStopLingeringThreadsCooperativeExits() throws Exception {
        GuestHarness.shouldTerminate = false;
        ClassLoader dummyLoader = new ClassLoader() {};
        Thread cooperative = new Thread(() -> {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException ignored) {
                // exits immediately
            } // try
        });
        cooperative.setContextClassLoader(dummyLoader);
        cooperative.start();
        try {
            GuestHarness.stopLingeringThreads(dummyLoader);
            assertThat(cooperative.isAlive()).isFalse();
            assertThat(GuestHarness.shouldTerminate).isFalse();
        } finally {
            cooperative.interrupt();
            cooperative.join(500);
        } // try
    } // testStopLingeringThreadsCooperativeExits

    @Test
    void testIsJobThreadDirect() {
        ClassLoader loader = new ClassLoader() {};
        ClassLoader otherLoader = new ClassLoader() {};
        ThreadGroup jobGroup = new ThreadGroup("test-job-group");
        ThreadGroup childGroup = new ThreadGroup(jobGroup, "test-child-group");
        ThreadGroup otherGroup = new ThreadGroup("other-group");

        Thread tLoader = new Thread(() -> {});
        tLoader.setContextClassLoader(loader);
        assertThat(GuestHarness.isJobThread(tLoader, loader, null)).isTrue();

        Thread tChildGroup = new Thread(childGroup, () -> {});
        tChildGroup.setContextClassLoader(otherLoader);
        assertThat(GuestHarness.isJobThread(tChildGroup, loader, jobGroup)).isTrue();

        Thread tOther = new Thread(otherGroup, () -> {});
        tOther.setContextClassLoader(otherLoader);
        assertThat(GuestHarness.isJobThread(tOther, loader, jobGroup)).isFalse();
    } // testIsJobThreadDirect
    @Test
    void staticInitializerFailureDoesNotPreventTheNextJob() {
        GuestHarness.nextClassPath = new File("target/test-classes").getAbsolutePath();
        GuestHarness.nextMainClass = FailingInitializerTarget.class.getName();
        GuestHarness.runJob();
        assertThat(GuestHarness.nextClassPath).isNull();
        assertThat(GuestHarness.lastHarnessFailure).isNull();
        GuestHarness.nextClassPath = new File("target/test-classes").getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        GuestHarness.runJob();
        assertThat(DummyTarget.MARKER.exists()).isTrue();
    }

    public static class FailingInitializerTarget {
        static final Object VALUE = fail();
        private static Object fail() {
            throw new IllegalStateException("initializer failed");
        }
        public static void main(String[] args) {}
    }

} // GuestHarnessTest
