package cs1302.tracer.trace;

import static cs1302.tracer.trace.JdiValueContractTest.mirror;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.guest.GuestHarness;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests covering event dispatch, error recovery, and lifecycle of PersistentGuestSession.
 */
class PersistentGuestSessionEventTest {

    private static final class FakeProcess extends Process {
        private boolean alive;

        FakeProcess(boolean alive) {
            this.alive = alive;
        } // FakeProcess

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        } // getOutputStream

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        } // getInputStream

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        } // getErrorStream

        @Override
        public int waitFor() {
            return 0;
        } // waitFor

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        } // waitFor

        @Override
        public int exitValue() {
            return 0;
        } // exitValue

        @Override
        public void destroy() {
            alive = false;
        } // destroy

        @Override
        public Process destroyForcibly() {
            alive = false;
            return this;
        } // destroyForcibly

        @Override
        public boolean isAlive() {
            return alive;
        } // isAlive
    } // FakeProcess

    private static Location location(String declaringType, int line) {
        var m = method(declaringType, "main", "([Ljava/lang/String;)V");
        var refType = mirror(ReferenceType.class, Map.of(
                "name", declaringType,
                "classLoader", mirror(com.sun.jdi.ClassLoaderReference.class, Map.of())));
        return mirror(Location.class, Map.of(
                "declaringType", refType,
                "method", m,
                "lineNumber", line,
                "sourcePath", declaringType + ".java"));
    } // location

    private static Method method(String declaringType, String name, String signature) {
        var refType = mirror(ReferenceType.class, Map.of(
                "name", declaringType,
                "classLoader", mirror(com.sun.jdi.ClassLoaderReference.class, Map.of())));
        return mirror(Method.class, Map.of(
                "declaringType", refType,
                "name", name,
                "signature", signature,
                "isPrivate", false,
                "argumentTypes", List.of()));
    } // method

    private static ThreadReference thread(Location topLoc) {
        var frame = mirror(StackFrame.class, Map.of(
                "location", topLoc,
                "visibleVariables", Collections.emptyList()));
        return mirror(ThreadReference.class, Map.of(
                "frameCount", 1,
                "frames", List.of(frame),
                "frame", frame));
    } // thread

    private static PersistentGuestSession createMockSession(
            VirtualMachine vm, Location readyLoc, Location completedLoc, Process process) {
        var harnessType = mirror(ClassType.class, Map.of(
                "fieldByName", mirror(Field.class, Map.of())));
        var out = new StreamDrainer(InputStream.nullInputStream());
        var err = new StreamDrainer(InputStream.nullInputStream());
        return new PersistentGuestSession(vm, out, err, harnessType, readyLoc, completedLoc);
    } // createMockSession

    @Test
    @DisplayName("dispatchJobEvent handles all JDI event types correctly")
    void testDispatchJobEvents() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var erm = mirror(EventRequestManager.class, Map.of(
                "createBreakpointRequest", mirror(BreakpointRequest.class, Map.of("enable", ""))));
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventRequestManager", erm,
                "classesByName", List.of()));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        CompilationResult cr = new CompilationResult(
                java.nio.file.Path.of("."), Set.of("Student"), "Student");
        List<ExecutionSnapshot> captured = new ArrayList<>();
        PersistentGuestSession.SnapshotSink sink = (line, snap) -> captured.add(snap);

        var ctx = new PersistentGuestSession.JobContext(
                cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                new InputTracker(""), sink, new ArrayList<>(), new HashSet<>(),
                new AtomicReference<>(), 0, 0, true, false);

        boolean[] recorded = new boolean[] {false};

        // 1. ClassPrepareEvent for foreign class
        var foreignRef = mirror(ReferenceType.class, Map.of("name", "ForeignClass",
                "allFields", List.of()));
        var cpeForeign = mirror(ClassPrepareEvent.class, Map.of("referenceType", foreignRef));
        assertThat(session.dispatchJobEvent(cpeForeign, ctx, recorded, null)).isFalse();

        // 2. ClassPrepareEvent for student class
        var studentRef = mirror(ReferenceType.class, Map.of(
                "name", "Student",
                "allFields", List.of(),
                "locationsOfLine", List.of(location("Student", 10))));
        var cpeStudent = mirror(ClassPrepareEvent.class, Map.of("referenceType", studentRef));
        assertThat(session.dispatchJobEvent(cpeStudent, ctx, recorded, null)).isFalse();
        assertThat(ctx.loadedClasses()).contains(studentRef);

        // 3. BreakpointEvent at onJobCompleted
        var bpeCompleted = mirror(BreakpointEvent.class, Map.of("location", completedLoc));
        assertThat(session.dispatchJobEvent(bpeCompleted, ctx, recorded, null)).isTrue();

        // 4. BreakpointEvent at foreign class
        var bpeForeign = mirror(BreakpointEvent.class, Map.of(
                "location", location("ForeignClass", 5)));
        assertThat(session.dispatchJobEvent(bpeForeign, ctx, recorded, null)).isFalse();

        // 5. BreakpointEvent at student class
        Location studentLoc = location("Student", 10);
        var bpeStudent = mirror(BreakpointEvent.class, Map.of(
                "location", studentLoc,
                "thread", thread(studentLoc)));
        assertThat(session.dispatchJobEvent(bpeStudent, ctx, recorded, null)).isFalse();
        assertThat(recorded[0]).isTrue();
        assertThat(captured).hasSize(1);

        // 6. MethodExitEvent non-main
        var helperMethod = method("Student", "helper", "()I");
        var meeHelper = mirror(MethodExitEvent.class, Map.of(
                "method", helperMethod,
                "thread", thread(location("Student", 20))));
        session.dispatchJobEvent(meeHelper, ctx, recorded, null);

        // 7. MethodExitEvent main with snapMainEnd = false and recorded = true
        var ctxNoMain = new PersistentGuestSession.JobContext(
                cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                new InputTracker(""), sink, new ArrayList<>(), new HashSet<>(),
                new AtomicReference<>(), 0, 0, false, false);
        var mainMethod = method("Student", "main", "([Ljava/lang/String;)V");
        var meeMain = mirror(MethodExitEvent.class, Map.of(
                "method", mainMethod,
                "thread", thread(location("Student", 20))));
        session.dispatchJobEvent(meeMain, ctxNoMain, recorded, null);

        // 7b. MethodExitEvent main with snapMainEnd = false and recorded = false
        boolean[] notRecorded = new boolean[] {false};
        session.dispatchJobEvent(meeMain, ctxNoMain, notRecorded, null);
        assertThat(notRecorded[0]).isFalse();

        // 8. MethodExitEvent main with snapMainEnd = true
        session.dispatchJobEvent(meeMain, ctx, recorded, null);
        assertThat(captured).hasSize(2);

        // 8b. MethodExitEvent main but in a different declaring class
        var otherMainMethod = method("OtherClass", "main", "([Ljava/lang/String;)V");
        var meeOtherMain = mirror(MethodExitEvent.class, Map.of(
                "method", otherMainMethod,
                "thread", thread(location("OtherClass", 20))));
        session.dispatchJobEvent(meeOtherMain, ctx, recorded, null);

        // 9. ExceptionEvent with null location
        var eeNullLoc = mirror(ExceptionEvent.class, Map.of(
                "catchLocation", location("Student", 30)));
        session.dispatchJobEvent(eeNullLoc, ctx, recorded, null);

        // 10. ExceptionEvent caught in student
        var eeCaught = mirror(ExceptionEvent.class, Map.of(
                "location", location("Student", 15),
                "catchLocation", location("Student", 25)));
        session.dispatchJobEvent(eeCaught, ctx, recorded, null);

        // 11. ExceptionEvent uncaught in student with active TraceSession
        recorded[0] = false;
        var exRef = mirror(com.sun.jdi.ObjectReference.class, Map.of(
                "referenceType", mirror(ReferenceType.class, Map.of(
                        "name", "java.lang.RuntimeException"))));
        var eeUncaught = mirror(ExceptionEvent.class, Map.of(
                "location", location("Student", 15),
                "catchLocation", location("ForeignClass", 50),
                "exception", exRef,
                "thread", thread(location("Student", 15))));
        try (var ts = new cs1302.tracer.execution.TraceSession(
                cs1302.tracer.execution.TraceLimits.unlimited(),
                cs1302.tracer.execution.InspectionPolicy.TRUSTED, true)) {
            assertThat(ts).isNotNull();
            session.dispatchJobEvent(eeUncaught, ctx, recorded, null);
            assertThat(recorded[0]).isTrue();

            var ctxChron = new PersistentGuestSession.JobContext(
                    cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                    new InputTracker(""), sink, new ArrayList<>(), new HashSet<>(),
                    new AtomicReference<>(), 0, 0, true, true);
            session.dispatchJobEvent(eeUncaught, ctxChron, recorded, null);

            var eeUncaughtNullCatch = mirror(ExceptionEvent.class, Map.of(
                    "location", location("Student", 15),
                    "exception", exRef,
                    "thread", thread(location("Student", 15))));
            session.dispatchJobEvent(eeUncaughtNullCatch, ctx, recorded, null);
        } // try

        // 11b. ClassLoader mismatch tests for event filtering
        var otherLoader = mirror(com.sun.jdi.ClassLoaderReference.class, Map.of());
        var studentLoader = mirror(com.sun.jdi.ClassLoaderReference.class, Map.of());
        ctx.jobLoader().set(studentLoader);

        var studentDiffRef = mirror(ReferenceType.class, Map.of(
                "name", "Student",
                "classLoader", otherLoader));
        var cpeStudentDiff = mirror(ClassPrepareEvent.class, Map.of(
                "referenceType", studentDiffRef));
        session.dispatchJobEvent(cpeStudentDiff, ctx, recorded, null);

        var diffLoc = mirror(Location.class, Map.of("declaringType", studentDiffRef));
        var bpeDiff = mirror(BreakpointEvent.class, Map.of("location", diffLoc));
        session.dispatchJobEvent(bpeDiff, ctx, recorded, null);

        var meeDiff = mirror(MethodExitEvent.class, Map.of(
                "method", mirror(Method.class, Map.of("declaringType", studentDiffRef))));
        session.dispatchJobEvent(meeDiff, ctx, recorded, null);

        var eeDiff = mirror(ExceptionEvent.class, Map.of("location", diffLoc));
        session.dispatchJobEvent(eeDiff, ctx, recorded, null);

        // 12. VMDeathEvent
        var vmDeath = mirror(VMDeathEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmDeath, ctx, recorded, null)).isTrue();
        assertThat(session.isAlive()).isFalse();

        // 13. VMDisconnectEvent
        var vmDisconnect = mirror(VMDisconnectEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmDisconnect, ctx, recorded, null)).isTrue();

        // 14. Default event (VMStartEvent)
        var vmStart = mirror(VMStartEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmStart, ctx, recorded, null)).isFalse();
    } // testDispatchJobEvents

    @Test
    @DisplayName("handleCleanupFailure correctly unwraps or suppresses exceptions")
    void testHandleCleanupFailure() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of("process", proc));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);

        // Case 1: tracingFailure != null -> cleanup failure suppressed
        var tf = new RuntimeException("Primary tracing failure");
        var cf = new RuntimeException("Cleanup failure");
        session.handleCleanupFailure(tf, cf);
        assertThat(tf.getSuppressed()).contains(cf);
        assertThat(proc.isAlive()).isFalse();

        // Case 2: tracingFailure == null, cleanupFailure is InterruptedException
        var ie = new InterruptedException("Interrupted cleanup");
        assertThatThrownBy(() -> session.handleCleanupFailure(null, ie))
                .isSameAs(ie);
        assertThat(Thread.interrupted()).isTrue(); // clears the interrupt flag set by handler

        // Case 3: tracingFailure == null, cleanupFailure is RuntimeException
        var re = new IllegalArgumentException("Runtime cleanup failure");
        assertThatThrownBy(() -> session.handleCleanupFailure(null, re))
                .isSameAs(re);

        // Case 4: tracingFailure == null, cleanupFailure is Error
        var err = new AssertionError("Assertion error in cleanup");
        assertThatThrownBy(() -> session.handleCleanupFailure(null, err))
                .isSameAs(err);

        // Case 5: tracingFailure == null, cleanupFailure is checked Exception -> wrapped in RuntimeException
        var checked = new Exception("Checked exception");
        assertThatThrownBy(() -> session.handleCleanupFailure(null, checked))
                .isInstanceOf(RuntimeException.class)
                .hasCause(checked);
    } // testHandleCleanupFailure

    @Test
    @DisplayName("teardownJobRequests handles VMDisconnectedException cleanly")
    void testTeardownJobRequestsDisconnected() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var erm = (EventRequestManager) java.lang.reflect.Proxy.newProxyInstance(
                EventRequestManager.class.getClassLoader(),
                new Class<?>[] {EventRequestManager.class},
                (self, m, args) -> {
                    throw new VMDisconnectedException();
                });
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventRequestManager", erm));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        var req = mirror(BreakpointRequest.class, Map.of("disable", ""));
        List<EventRequest> requests = new ArrayList<>(List.of(req));

        session.teardownJobRequests(requests);
        assertThat(session.isAlive()).isFalse();
        assertThat(requests).isEmpty();
    } // testTeardownJobRequestsDisconnected

    @Test
    @DisplayName("executeJobEventLoop processes events until completed and terminates on VM death")
    void testExecuteJobEventLoop() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);

        var eventSets = new ArrayList<EventSet>();
        var bpeCompleted = mirror(BreakpointEvent.class, Map.of("location", completedLoc));
        var set1 = mirror(EventSet.class, Map.of("iterator", List.of((Event) bpeCompleted).iterator()));
        eventSets.add(set1);

        var customEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if (m.getName().equals("remove")) {
                        if (!eventSets.isEmpty()) {
                            return eventSets.removeFirst();
                        } // if
                        return null;
                    } // if
                    return null;
                });

        var cpr = mirror(com.sun.jdi.request.ClassPrepareRequest.class, Map.of());
        var mer = mirror(com.sun.jdi.request.MethodExitRequest.class, Map.of());
        var er = mirror(com.sun.jdi.request.ExceptionRequest.class, Map.of());
        var erm = mirror(EventRequestManager.class, Map.of(
                "createClassPrepareRequest", cpr,
                "createMethodExitRequest", mer,
                "createExceptionRequest", er));

        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", customEq,
                "eventRequestManager", erm,
                "mirrorOf", mirror(com.sun.jdi.StringReference.class, Map.of()),
                "classesByName", List.of()));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        CompilationResult cr = new CompilationResult(
                java.nio.file.Path.of("."), Set.of("Student"), "Student");
        var ctx = new PersistentGuestSession.JobContext(
                cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                new InputTracker(""), (line, snap) -> {}, new ArrayList<>(),
                new HashSet<>(), new AtomicReference<>(), 0, 0, true, false);

        session.executeJobEventLoop(ctx);

        // Now test with VMDeathEvent terminating the loop while alive becomes false
        var deathEvent = mirror(VMDeathEvent.class, Map.of());
        var setDeath1 = mirror(EventSet.class, Map.of("iterator", List.of((Event) deathEvent).iterator()));
        eventSets.add(setDeath1);
        session.setAlive(true);
        session.executeJobEventLoop(ctx);
        assertThat(session.isAlive()).isFalse();

        // Test runJobInternal with process dying to exercise finally branch when isAlive() is false
        var setDeath2 = mirror(EventSet.class, Map.of("iterator", List.of((Event) deathEvent).iterator()));
        eventSets.add(setDeath2);
        session.setAlive(true);
        session.runJobInternal(cr, List.of(), List.of(), "", false, (line, snap) -> {});
        assertThat(session.isAlive()).isFalse();

        // Test runJobInternal when currentSession.isStopped() is true
        try (var ts = new cs1302.tracer.execution.TraceSession(
                cs1302.tracer.execution.TraceLimits.unlimited(),
                cs1302.tracer.execution.InspectionPolicy.TRUSTED, true)) {
            ts.stop("timeout");
            var setDeath3 = mirror(EventSet.class, Map.of("iterator", List.of((Event) deathEvent).iterator()));
            eventSets.add(setDeath3);
            session.setAlive(true);
            assertThatThrownBy(() -> {
                session.runJobInternal(cr, List.of(), List.of(), "", false, (line, snap) -> {});
            }).isInstanceOf(cs1302.tracer.execution.TraceSession.Stopped.class);
            assertThat(session.isAlive()).isFalse();
        } // try
    } // testExecuteJobEventLoop

    @Test
    @DisplayName("isAlive and setAlive verify process lifecycle")
    void testIsAlive() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of("process", proc));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        assertThat(session.isAlive()).isTrue();
        assertThat(session.process()).isSameAs(proc);

        session.setAlive(false);
        assertThat(session.isAlive()).isFalse();

        session.setAlive(true);
        var deadProc = new FakeProcess(false);
        var deadVm = mirror(VirtualMachine.class, Map.of("process", deadProc));
        PersistentGuestSession deadSession = createMockSession(deadVm, readyLoc, completedLoc, deadProc);
        assertThat(deadSession.isAlive()).isFalse();
    } // testIsAlive

    @Test
    @DisplayName("waitForReadyBreakpoint throws IllegalStateException when deadline expires")
    void testWaitForReadyBreakpointTimeout() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var emptyEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> null);
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", emptyEq));
        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        assertThatThrownBy(() -> session.waitForReadyBreakpoint(System.currentTimeMillis() - 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out waiting for GuestHarness ready breakpoint");
        assertThat(proc.isAlive()).isFalse();
    } // testWaitForReadyBreakpointTimeout

    @Test
    @DisplayName("awaitAndInstallSentinels throws IllegalStateException when startup times out")
    void testAwaitAndInstallSentinelsTimeout() {
        var proc = new FakeProcess(true);
        var emptyEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> null);
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", emptyEq));
        var cpr = mirror(ClassPrepareRequest.class, Map.of());
        BreakpointRequest[] bps = new BreakpointRequest[2];
        Location[] locs = new Location[2];

        assertThatThrownBy(() -> PersistentGuestSession.awaitAndInstallSentinelsUntil(
                vm, cpr, bps, locs, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out waiting for GuestHarness startup handshake");
        assertThat(proc.isAlive()).isFalse();
    } // testAwaitAndInstallSentinelsTimeout

    @Test
    @DisplayName("close is idempotent and handles already dead sessions")
    void testCloseIdempotent() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of("process", proc));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        session.setAlive(false);
        session.close();
        assertThat(session.isAlive()).isFalse();

        // Calling close again returns immediately
        session.close();
    } // testCloseIdempotent

    @Test
    @DisplayName("attach failure cleans up resources and rethrows")
    void testAttachFailureCleansUp() {
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of(
                "eventRequestManager", new RuntimeException("ERM failure"),
                "process", proc));
        var out = new StreamDrainer(InputStream.nullInputStream());
        var err = new StreamDrainer(InputStream.nullInputStream());

        assertThatThrownBy(() -> PersistentGuestSession.attach(vm, out, err))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("ERM failure");
        assertThat(proc.isAlive()).isFalse();
    } // testAttachFailureCleansUp

    @Test
    @DisplayName("cleanupLaunchFailure handles alive and dead processes with exception resilience")
    void testCleanupLaunchFailureVariants() {
        var procAlive = new FakeProcess(true);
        var vmAlive = mirror(VirtualMachine.class, Map.of(
                "process", procAlive,
                "dispose", new RuntimeException("dispose failed")));
        var out = new StreamDrainer(InputStream.nullInputStream());
        var err = new StreamDrainer(InputStream.nullInputStream());

        PersistentGuestSession.cleanupLaunchFailure(vmAlive, out, err);
        assertThat(procAlive.isAlive()).isFalse();

        var procDead = new FakeProcess(false);
        var vmDead = mirror(VirtualMachine.class, Map.of(
                "process", procDead));
        PersistentGuestSession.cleanupLaunchFailure(vmDead, out, err);
        assertThat(procDead.isAlive()).isFalse();
    } // testCleanupLaunchFailureVariants

    @Test
    @DisplayName("cleanupJobRun terminates session when shouldTerminate is true")
    void testCleanupJobRunShouldTerminate() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", mirror(EventQueue.class, Map.of())));
        var termTrue = mirror(com.sun.jdi.BooleanValue.class, Map.of("value", true));
        var harnessType = mirror(ClassType.class, Map.of(
                "fieldByName", mirror(Field.class, Map.of()),
                "getValue", termTrue));
        var out = new StreamDrainer(InputStream.nullInputStream());
        var err = new StreamDrainer(InputStream.nullInputStream());

        var session = new PersistentGuestSession(vm, out, err, harnessType, readyLoc, completedLoc);
        session.cleanupJobRun(null, 0, 0, new ArrayList<>());
        assertThat(session.isAlive()).isFalse();
        assertThat(proc.isAlive()).isFalse();

        var termFalse = mirror(com.sun.jdi.BooleanValue.class, Map.of("value", false));
        var harnessTypeFalse = mirror(ClassType.class, Map.of(
                "fieldByName", mirror(Field.class, Map.of()),
                "getValue", termFalse));
        var proc2 = new FakeProcess(true);
        var vm2 = mirror(VirtualMachine.class, Map.of(
                "process", proc2));
        var session2 = new PersistentGuestSession(vm2, out, err, harnessTypeFalse, readyLoc, completedLoc);
        session2.setAlive(false);
        session2.cleanupJobRun(null, 0, 0, new ArrayList<>());

        var harnessTypeEx = mirror(ClassType.class, Map.of(
                "fieldByName", mirror(Field.class, Map.of()),
                "getValue", new RuntimeException("getValue failed")));
        var session3 = new PersistentGuestSession(vm2, out, err, harnessTypeEx, readyLoc, completedLoc);
        session3.setAlive(false);
        session3.cleanupJobRun(null, 0, 0, new ArrayList<>());
    } // testCleanupJobRunShouldTerminate

    @Test
    @DisplayName("appendChronologicalSnapshot handles line numbers and redundancy")
    void testAppendChronologicalSnapshot() {
        List<ExecutionSnapshot> chronological = new ArrayList<>();
        ExecutionSnapshot snap1 = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(), new byte[0], new byte[0],
                java.util.Optional.of("Test1.java"), "", 0);
        ExecutionSnapshot snap2 = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(), new byte[0], new byte[0],
                java.util.Optional.of("Test2.java"), "", 0);

        // 1. Line != -1 appends snapshot directly
        PersistentGuestSession.appendChronologicalSnapshot(chronological, 1, snap1);
        assertThat(chronological).containsExactly(snap1);

        // 2. Line == -1 and chronological empty -> appends
        List<ExecutionSnapshot> emptyList = new ArrayList<>();
        PersistentGuestSession.appendChronologicalSnapshot(emptyList, -1, snap1);
        assertThat(emptyList).containsExactly(snap1);

        // 3. Line == -1 and not redundant with last -> appends
        PersistentGuestSession.appendChronologicalSnapshot(chronological, -1, snap2);
        assertThat(chronological).containsExactly(snap1, snap2);

        // 4. Line == -1 and redundant with last -> does not append
        PersistentGuestSession.appendChronologicalSnapshot(chronological, -1, snap2);
        assertThat(chronological).hasSize(2);
    } // testAppendChronologicalSnapshot

    @Test
    @DisplayName("waitForReadyBreakpoint handles events and exceptions")
    void testWaitForReadyBreakpointVariants() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);

        // Case 1: InterruptedException
        var interruptingEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if (m.getName().equals("remove")) {
                        throw new InterruptedException();
                    } // if
                    return null;
                });
        var vm1 = mirror(VirtualMachine.class, Map.of(
                "process", proc, "eventQueue", interruptingEq));
        PersistentGuestSession s1 = createMockSession(vm1, readyLoc, completedLoc, proc);
        assertThatThrownBy(() -> s1.waitForReadyBreakpoint(System.currentTimeMillis() + 5000))
                .isInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue();

        // Case 2: Other exception
        var throwingEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if (m.getName().equals("remove")) {
                        throw new RuntimeException("boom");
                    } // if
                    return null;
                });
        var vm2 = mirror(VirtualMachine.class, Map.of(
                "process", proc, "eventQueue", throwingEq));
        PersistentGuestSession s2 = createMockSession(vm2, readyLoc, completedLoc, proc);
        s2.waitForReadyBreakpoint(System.currentTimeMillis() + 5000);
        assertThat(s2.isAlive()).isFalse();

        // Case 3: Events with null eventSet, non-ready bpe, then ready bpe
        List<EventSet> sets = new ArrayList<>();
        sets.add(null);
        var nonReadyBpe = mirror(BreakpointEvent.class, Map.of(
                "location", location("Other", 1)));
        var readyBpe = mirror(BreakpointEvent.class, Map.of(
                "location", readyLoc));
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) nonReadyBpe).iterator(),
                "resume", "")));
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) readyBpe).iterator())));

        var queue3 = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if (m.getName().equals("remove")) {
                        return sets.isEmpty() ? null : sets.removeFirst();
                    } // if
                    return null;
                });
        var vm3 = mirror(VirtualMachine.class, Map.of(
                "process", proc, "eventQueue", queue3));
        PersistentGuestSession s3 = createMockSession(vm3, readyLoc, completedLoc, proc);
        s3.waitForReadyBreakpoint(System.currentTimeMillis() + 5000);
        assertThat(s3.isAlive()).isTrue();

        // Case 4: VMDeathEvent and VMDisconnectEvent
        sets.clear();
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) mirror(VMDeathEvent.class, Map.of())).iterator())));
        PersistentGuestSession s4 = createMockSession(vm3, readyLoc, completedLoc, proc);
        s4.waitForReadyBreakpoint(System.currentTimeMillis() + 5000);
        assertThat(s4.isAlive()).isFalse();

        sets.clear();
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of(
                        (Event) mirror(VMDisconnectEvent.class, Map.of())).iterator())));
        s4.setAlive(true);
        s4.waitForReadyBreakpoint(System.currentTimeMillis() + 5000);
        assertThat(s4.isAlive()).isFalse();

        // Case 5: already dead session (exercises !isAlive() branch of while condition)
        PersistentGuestSession s5 = createMockSession(vm3, readyLoc, completedLoc, proc);
        s5.setAlive(false);
        s5.waitForReadyBreakpoint(System.currentTimeMillis() + 5000);
    } // testWaitForReadyBreakpointVariants

    @Test
    @DisplayName("awaitAndInstallSentinelsUntil succeeds with event processing and resume")
    void testAwaitAndInstallSentinelsHandshake() throws Exception {
        var proc = new FakeProcess(true);
        var readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        var completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);

        var readyMethod = mirror(Method.class, Map.of("location", readyLoc));
        var completedMethod = mirror(Method.class, Map.of("location", completedLoc));
        var harnessRef = (ClassType) java.lang.reflect.Proxy.newProxyInstance(
                ClassType.class.getClassLoader(),
                new Class<?>[] {ClassType.class},
                (self, m, args) -> {
                    if (m.getName().equals("name")) {
                        return GuestHarness.class.getName();
                    } // if
                    if (m.getName().equals("methodsByName")) {
                        return args[0].equals("readyForJob")
                                ? List.of(readyMethod) : List.of(completedMethod);
                    } // if
                    return null;
                });

        var otherPrep = mirror(ClassPrepareEvent.class, Map.of(
                "referenceType", mirror(ReferenceType.class, Map.of("name", "OtherClass"))));
        var harnessPrep = mirror(ClassPrepareEvent.class, Map.of(
                "referenceType", harnessRef));

        List<EventSet> sets = new ArrayList<>();
        sets.add(null);
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) otherPrep).iterator(),
                "resume", "")));
        sets.add(mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) harnessPrep).iterator(),
                "resume", "")));

        var queue = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if (m.getName().equals("remove")) {
                        return sets.isEmpty() ? null : sets.removeFirst();
                    } // if
                    return null;
                });

        var erm = mirror(EventRequestManager.class, Map.of(
                "deleteEventRequest", "",
                "createBreakpointRequest", mirror(BreakpointRequest.class, Map.of("enable", ""))));
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", queue,
                "eventRequestManager", erm));

        var cpr = mirror(ClassPrepareRequest.class, Map.of("disable", ""));
        BreakpointRequest[] bps = new BreakpointRequest[2];
        Location[] locs = new Location[2];

        ClassType result = PersistentGuestSession.awaitAndInstallSentinelsUntil(
                vm, cpr, bps, locs, System.currentTimeMillis() + 5000);
        assertThat(result).isNotNull();
        assertThat(bps[0]).isNotNull();
        assertThat(locs[0]).isEqualTo(readyLoc);
    } // testAwaitAndInstallSentinelsHandshake

    @Test
    @DisplayName("close handles process termination errors and dispose failure")
    void testCloseWithExceptions() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "resume", new RuntimeException("resume fail"),
                "dispose", new RuntimeException("dispose fail"),
                "mirrorOf", mirror(com.sun.jdi.BooleanValue.class, Map.of())));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        session.setAlive(true);
        session.close();
        assertThat(session.isAlive()).isFalse();
    } // testCloseWithExceptions
} // PersistentGuestSessionEventTest
