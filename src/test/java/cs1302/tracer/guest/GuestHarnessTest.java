package cs1302.tracer.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GuestHarness} and {@link GuestHarness.VirtualInputStream}.
 */
public class GuestHarnessTest {

    @BeforeEach
    @AfterEach
    void resetHarness() {
        GuestHarness.nextClassPath = null;
        GuestHarness.nextMainClass = null;
        GuestHarness.nextStdin = null;
        GuestHarness.shouldTerminate = false;
        GuestHarness.cleanState();
    } // resetHarness

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
        // Compile a dummy class or use current test class as target
        File targetDir = new File("target/test-classes");
        GuestHarness.nextClassPath = targetDir.getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        GuestHarness.nextStdin = "sample input";

        DummyTarget.invoked = false;
        GuestHarness.runJob();

        assertThat(DummyTarget.invoked).isTrue();
        assertThat(GuestHarness.nextClassPath).isNull();
        assertThat(GuestHarness.nextMainClass).isNull();
    } // testRunJobExecution

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
        public static boolean invoked = false;

        public static void main(String[] args) {
            invoked = true;
        } // main
    } // DummyTarget

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
        } catch (InterruptedException ignored) {}
        GuestHarness.shouldTerminate = true;
        try {
            t1.join(500);
        } catch (InterruptedException ignored) {}

        // nextClassPath null, nextMainClass set
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = null;
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        Thread t2 = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });
        try {
            Thread.sleep(30);
        } catch (InterruptedException ignored) {}
        GuestHarness.shouldTerminate = true;
        try {
            t2.join(500);
        } catch (InterruptedException ignored) {}

        // both set -> calls runJob
        GuestHarness.shouldTerminate = false;
        GuestHarness.nextClassPath = new File("target/test-classes").getAbsolutePath();
        GuestHarness.nextMainClass = DummyTarget.class.getName();
        DummyTarget.invoked = false;
        Thread t3 = Thread.ofVirtual().start(() -> {
            GuestHarness.main(new String[0]);
        });
        try {
            Thread.sleep(50);
        } catch (InterruptedException ignored) {}
        GuestHarness.shouldTerminate = true;
        try {
            t3.join(500);
        } catch (InterruptedException ignored) {}
        assertThat(DummyTarget.invoked).isTrue();
    } // testMainCombinations
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
}
