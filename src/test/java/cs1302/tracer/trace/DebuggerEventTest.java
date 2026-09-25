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
            Object snapshots = chronological ? new ArrayList<ExecutionSnapshot>() : new HashMap<Integer, List<ExecutionSnapshot>>();
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
        var chronological = new ArrayList<ExecutionSnapshot>();
        runLoop(true, vm(List.of(main, main, exception, stop)), chronological, true, null);
        assertThat(chronological).hasSize(1);
        assertThat(chronological.getFirst().stdinConsumed()).isEmpty();
        assertThat(chronological.getFirst().stdinOffset()).isZero();
        var mapped = new HashMap<Integer, List<ExecutionSnapshot>>();
        runLoop(false, vm(List.of(main, exception, stop)), mapped, false, null);
        assertThat(mapped.keySet()).containsExactlyInAnyOrder(-1, 1);
        var priorFinal = mapped.get(-1).getFirst();
        // Main exits without requested capture must not replace previously selected states.
        runLoop(false, vm(List.of(main, stop)), mapped, false, null);
        assertThat(mapped.get(-1).getFirst()).isSameAs(priorFinal);
        var excluded = new ArrayList<ExecutionSnapshot>();
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
        ObjectReference first = mirror(ObjectReference.class, Map.of("uniqueID", 101L));
        ObjectReference second = mirror(ObjectReference.class, Map.of("uniqueID", 102L));
        assertThat(queue.add(first)).isTrue();
        assertThat(queue.add(second)).isTrue();
        assertThat(queue.get(1)).isSameAs(second);
        assertThat(queue.removeFirst()).isSameAs(first);
        assertThat(queue.add(first)).isFalse();
        assertThat(queue.removeFirst()).isSameAs(second);
        assertThat(queue).isEmpty();
        assertThat(queue).isNotInstanceOf(Cloneable.class);
    }

    @Test
    void exceptionAtADifferentLocationAddsAChronologicalState() throws Exception {
        var previous = new ExecutionSnapshot(List.of(new ExecutionSnapshot.StackSnapshot(
                "main", 2, List.of(), Optional.empty())), List.of(), Map.of(), new byte[0], new byte[0]);
        var snapshots = new ArrayList<>(List.of(previous));
        var exception = mirror(ExceptionEvent.class, Map.of("location", location("C"), "thread", thread()));
        runLoop(true, vm(List.of(exception, mirror(VMDisconnectEvent.class, Map.of()))), snapshots, false, null);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.getLast().stack().getLast().methodLine()).isEqualTo(1);
    }
}
