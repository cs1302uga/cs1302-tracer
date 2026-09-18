package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import cs1302.tracer.CompilationHelper.CompilationResult;
import java.util.*;
import org.junit.jupiter.api.Test;

class DebuggerEventTest {
    static Object call(String name, Class<?>[] signature, Object... args) throws Exception {
        var method = DebugTraceHelper.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw (Exception) e.getCause(); }
    }

    private static com.sun.jdi.Method mainMethod(String signature) {
        return mirror(com.sun.jdi.Method.class, Map.of("name", "main", "signature", signature,
                "isPrivate", false, "argumentTypes", List.of(),
                "declaringType", mirror(ReferenceType.class, Map.of("name", "C"))));
    }

    private static Location location(String owner) {
        return mirror(Location.class, Map.of("declaringType", mirror(ReferenceType.class, Map.of("name", owner)),
                "method", mainMethod("()V"), "lineNumber", 1, "sourcePath", "C.java"));
    }

    private static ThreadReference thread() {
        var frame = mirror(StackFrame.class, Map.of("location", location("C"), "visibleVariables", List.of()));
        return mirror(ThreadReference.class, Map.of("frames", List.of(frame), "frameCount", 1, "frame", frame));
    }

    private static VirtualMachine vm(List<Event> events) {
        var set = mirror(EventSet.class, Map.of("iterator", events.iterator()));
        return mirror(VirtualMachine.class, Map.of("classesByName", List.of(),
                "eventQueue", mirror(EventQueue.class, Map.of("remove", set))));
    }

    @Test
    void eventLoopsIgnoreForeignEventsAndStopOnDisconnect() throws Exception {
        var foreign = mirror(ReferenceType.class, Map.of("name", "Foreign"));
        for (boolean chronological : List.of(false, true)) {
            var events = List.<Event>of(
                    mirror(ClassPrepareEvent.class, Map.of("referenceType", foreign)),
                    mirror(BreakpointEvent.class, Map.of("location", location("Foreign"))),
                    mirror(MethodExitEvent.class, Map.of("method", mainMethod("(I)V"))),
                    mirror(ExceptionEvent.class, Map.of()),
                    mirror(ExceptionEvent.class, Map.of("location", location("Foreign"))),
                    mirror(VMStartEvent.class, Map.of()),
                    mirror(VMDisconnectEvent.class, Map.of()));
            Object snapshots = chronological ? new ArrayList<Snapshot>() : new HashMap<Integer, List<Snapshot>>();
            runLoop(chronological, vm(events), snapshots, false, null);
            assertThat(snapshots).isEqualTo(chronological ? List.of() : Map.of());
        }
    }

    private static void runLoop(boolean chronological, VirtualMachine vm, Object snapshots,
            boolean mainExit, InputTracker input) throws Exception {
        try (var out = new StreamDrainer(java.io.InputStream.nullInputStream());
                var err = new StreamDrainer(java.io.InputStream.nullInputStream())) {
            out.waitForEof(1000);
            err.waitForEof(1000);
            call(chronological ? "processChronologicalEventLoop" : "processBreakpointsEventLoop",
                    new Class<?>[] {VirtualMachine.class, CompilationResult.class, Collection.class, List.class,
                            chronological ? List.class : Map.class, HashSet.class, StreamDrainer.class,
                            StreamDrainer.class, boolean.class, InputTracker.class},
                    vm, new CompilationResult(null, Set.of("C"), "C"), List.of(), List.of(), snapshots,
                    new HashSet<>(), out, err, mainExit, input);
        }
    }

    @Test
    void repeatedMainExitAndExceptionEventsDoNotDuplicateFinalStates() throws Exception {
        var thread = thread();
        var main = mirror(MethodExitEvent.class, Map.of("method", mainMethod("()V"), "thread", thread));
        var exception = mirror(ExceptionEvent.class, Map.of("location", location("C"), "thread", thread));
        var stop = mirror(VMDisconnectEvent.class, Map.of());
        var chronological = new ArrayList<Snapshot>();
        runLoop(true, vm(List.of(main, main, exception, stop)), chronological, true, null);
        assertThat(chronological).hasSize(1);
        assertThat(chronological.getFirst().stdinConsumed()).isEmpty();
        assertThat(chronological.getFirst().stdinOffset()).isZero();
        var mapped = new HashMap<Integer, List<Snapshot>>();
        runLoop(false, vm(List.of(main, exception, stop)), mapped, false, null);
        assertThat(mapped.keySet()).containsExactlyInAnyOrder(-1, 1);
        var priorFinal = mapped.get(-1).getFirst();
        // Main exits without requested capture must not replace previously selected states.
        runLoop(false, vm(List.of(main, stop)), mapped, false, null);
        assertThat(mapped.get(-1).getFirst()).isSameAs(priorFinal);
        var excluded = new ArrayList<Snapshot>();
        runLoop(true, vm(List.of(main, stop)), excluded, false, null);
        assertThat(excluded).isEmpty();
    }

    @Test
    void waitErrorsPreserveInterruptAndTolerateDisconnectedProcesses() throws Exception {
        var signature = new Class<?>[] {VirtualMachine.class};
        call("awaitGuestExit", signature, mirror(VirtualMachine.class, Map.of("process", new VMDisconnectedException())));
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public java.io.InputStream getInputStream() { throw new UnsupportedOperationException(); }
            @Override public java.io.InputStream getErrorStream() { throw new UnsupportedOperationException(); }
            @Override public int waitFor() throws InterruptedException { throw new InterruptedException(); }
            @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException { throw new InterruptedException(); }
            @Override public int exitValue() { throw new UnsupportedOperationException(); }
            @Override public void destroy() {}
        };
        try {
            call("awaitGuestExit", signature, mirror(VirtualMachine.class, Map.of("process", process)));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void referenceQueueIgnoresNullReferences() throws Exception {
        var constructor = Class.forName("cs1302.tracer.trace.DebugTraceHelper$ReferenceQueue").getDeclaredConstructor();
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        var queue = (List<ObjectReference>) constructor.newInstance();
        assertThat(queue.add(null)).isFalse();
        assertThat(queue).isEmpty();
    }

    @Test
    void exceptionAtADifferentLocationAddsAChronologicalState() throws Exception {
        var previous = new ExecutionSnapshot(List.of(new ExecutionSnapshot.StackSnapshot(
                "main", 2, List.of(), Optional.empty())), List.of(), Map.of(), new byte[0], new byte[0]);
        var snapshots = new ArrayList<Snapshot>(List.of(previous));
        var exception = mirror(ExceptionEvent.class, Map.of("location", location("C"), "thread", thread()));
        runLoop(true, vm(List.of(exception, mirror(VMDisconnectEvent.class, Map.of()))), snapshots, false, null);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.getLast().stack().getLast().methodLine()).isEqualTo(1);
    }
    @Test void initializationFailureDestroysLaunchedGuest() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "exec sleep 30").start();
        assertThat(process.isAlive()).isTrue();
        var vm = mirror(VirtualMachine.class, Map.of("process", process,
                "eventRequestManager", new IllegalStateException("initialization failed")));
        assertThatThrownBy(() -> call("prepareVm", new Class<?>[] {VirtualMachine.class, CompilationResult.class},
                vm, new CompilationResult(null, Set.of("Main"), "Main")))
                .isInstanceOf(IllegalStateException.class).hasMessage("initialization failed");
        assertThat(process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.isAlive()).isFalse();
    }

    @Test void inputWriterJoinHandlesAbsentWriterAndCancellation() throws Exception {
        var signature = new Class<?>[] {Thread.class};
        call("awaitInputWriter", signature, (Object) null);
        Thread writer = new Thread(() -> {});
        Thread.currentThread().interrupt();
        try {
            call("awaitInputWriter", signature, writer);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread blocked = Thread.ofPlatform().start(() -> {
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        var preserved = new java.util.concurrent.atomic.AtomicBoolean();
        Thread joining = Thread.ofPlatform().start(() -> {
            try { call("awaitInputWriter", signature, blocked); preserved.set(Thread.currentThread().isInterrupted()); }
            catch (Exception e) { throw new AssertionError(e); }
        });
        try {
            long deadline = System.nanoTime() + 1_000_000_000;
            while (joining.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            joining.interrupt();
            joining.join(2000);
            assertThat(joining.isAlive()).isFalse();
            assertThat(preserved.get()).isTrue();
        } finally { release.countDown(); blocked.join(2000); }
    }
    @Test void partialIoInitializationAlwaysReleasesOwnership() throws Exception {
        var constructor = Class.forName("cs1302.tracer.trace.DebugTraceHelper$GuestRuntime")
                .getDeclaredConstructor(VirtualMachine.class, String.class);
        constructor.setAccessible(true);
        for (int failurePoint = 0; failurePoint < 3; failurePoint++) {
            int point = failurePoint;
            var destroyed = new java.util.concurrent.atomic.AtomicBoolean();
            Process process = new Process() {
                @Override public java.io.InputStream getErrorStream() {
                    if (point == 0) throw new IllegalStateException("stderr unavailable");
                    return java.io.InputStream.nullInputStream();
                }
                @Override public java.io.InputStream getInputStream() {
                    if (point == 1) throw new IllegalStateException("stdout unavailable");
                    return java.io.InputStream.nullInputStream();
                }
                @Override public java.io.OutputStream getOutputStream() { return java.io.OutputStream.nullOutputStream(); }
                @Override public int waitFor() { return 0; }
                @Override public int exitValue() { return 0; }
                @Override public void destroy() { destroyed.set(true); }
            };
            var vm = mirror(VirtualMachine.class, Map.of("process", process,
                    "eventRequestManager", new IllegalStateException("requests unavailable")));
            assertThatThrownBy(() -> constructor.newInstance(vm, "input"))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(destroyed.get()).isTrue();
        }
    }
}
