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

    static class FakeProcess extends Process {
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

    private static Method method(String declaringClass, String name, String signature) {
        return mirror(Method.class, Map.of(
                "name", name,
                "signature", signature,
                "isPrivate", false,
                "argumentTypes", List.of(),
                "declaringType", mirror(ReferenceType.class, Map.of("name", declaringClass))));
    } // method

    private static Location location(String owner, int lineNumber) {
        return mirror(Location.class, Map.of(
                "declaringType", mirror(ReferenceType.class, Map.of("name", owner)),
                "method", method(owner, "main", "([Ljava/lang/String;)V"),
                "lineNumber", lineNumber,
                "sourcePath", owner + ".java"));
    } // location

    private static ThreadReference thread(Location loc) {
        var frame = mirror(StackFrame.class, Map.of("location", loc, "visibleVariables", List.of()));
        return mirror(ThreadReference.class, Map.of(
                "frames", List.of(frame),
                "frameCount", 1,
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
        CompilationResult cr = new CompilationResult(java.nio.file.Path.of("."), Set.of("Student"), "Student");
        List<ExecutionSnapshot> captured = new ArrayList<>();
        PersistentGuestSession.SnapshotSink sink = (line, snap) -> captured.add(snap);

        var ctx = new PersistentGuestSession.JobContext(
                cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                new InputTracker(""), sink, new ArrayList<>(), new HashSet<>(), new AtomicReference<>(), 0, 0, true);

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
        var bpeForeign = mirror(BreakpointEvent.class, Map.of("location", location("ForeignClass", 5)));
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
                new InputTracker(""), sink, new ArrayList<>(), new HashSet<>(), new AtomicReference<>(), 0, 0, false);
        var mainMethod = method("Student", "main", "([Ljava/lang/String;)V");
        var meeMain = mirror(MethodExitEvent.class, Map.of(
                "method", mainMethod,
                "thread", thread(location("Student", 20))));
        session.dispatchJobEvent(meeMain, ctxNoMain, recorded, null);

        // 7b. MethodExitEvent main with snapMainEnd = false and recorded = false
        boolean[] notRecorded = new boolean[] {false};
        session.dispatchJobEvent(meeMain, ctxNoMain, notRecorded, null);
        assertThat(notRecorded[0]).isTrue();

        // 8. MethodExitEvent main with snapMainEnd = true
        session.dispatchJobEvent(meeMain, ctx, recorded, null);
        assertThat(captured).hasSize(3);

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
                "referenceType", mirror(ReferenceType.class, Map.of("name", "java.lang.RuntimeException"))));
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
        } // try

        // 11b. ClassLoader mismatch tests for event filtering
        var otherLoader = mirror(com.sun.jdi.ClassLoaderReference.class, Map.of());
        var studentLoader = mirror(com.sun.jdi.ClassLoaderReference.class, Map.of());
        ctx.jobLoader().set(studentLoader);

        var studentDiffRef = mirror(ReferenceType.class, Map.of(
                "name", "Student",
                "classLoader", otherLoader));
        var cpeStudentDiff = mirror(ClassPrepareEvent.class, Map.of("referenceType", studentDiffRef));
        session.dispatchJobEvent(cpeStudentDiff, ctx, recorded, null);

        var diffLoc = (Location) java.lang.reflect.Proxy.newProxyInstance(
                Location.class.getClassLoader(),
                new Class<?>[] {Location.class},
                (self, m, args) -> {
                    if ("equals".equals(m.getName())) return false;
                    if ("declaringType".equals(m.getName())) {
                        return mirror(ReferenceType.class, Map.of(
                                "classLoader", otherLoader,
                                "name", "Student"));
                    } // if
                    return null;
                });
        var bpeDiffLoader = mirror(BreakpointEvent.class, Map.of("location", diffLoc));
        assertThat(session.dispatchJobEvent(bpeDiffLoader, ctx, recorded, null)).isFalse();

        var diffMethod = (Method) java.lang.reflect.Proxy.newProxyInstance(
                Method.class.getClassLoader(),
                new Class<?>[] {Method.class},
                (self, m, args) -> {
                    if ("declaringType".equals(m.getName())) {
                        return mirror(ReferenceType.class, Map.of(
                                "classLoader", otherLoader,
                                "name", "Student"));
                    } // if
                    return null;
                });
        var meeDiffLoader = mirror(MethodExitEvent.class, Map.of("method", diffMethod));
        session.dispatchJobEvent(meeDiffLoader, ctx, recorded, null);

        var eeDiffLoader = mirror(ExceptionEvent.class, Map.of(
                "location", diffLoc,
                "catchLocation", location("Student", 25)));
        session.dispatchJobEvent(eeDiffLoader, ctx, recorded, null);

        // 12. ExceptionEvent in foreign class
        var eeForeign = mirror(ExceptionEvent.class, Map.of(
                "location", location("ForeignClass", 15)));
        session.dispatchJobEvent(eeForeign, ctx, recorded, null);

        // 13. VMDeathEvent and VMDisconnectEvent
        var vmDeath = mirror(VMDeathEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmDeath, ctx, recorded, null)).isTrue();
        assertThat(session.isAlive()).isFalse();

        session.setAlive(true);
        var vmDisconnect = mirror(VMDisconnectEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmDisconnect, ctx, recorded, null)).isTrue();
        assertThat(session.isAlive()).isFalse();

        // 14. Default event (VMStartEvent)
        session.setAlive(true);
        var vmStart = mirror(VMStartEvent.class, Map.of());
        assertThat(session.dispatchJobEvent(vmStart, ctx, recorded, null)).isFalse();
    } // testDispatchJobEvents

    @Test
    @DisplayName("waitForReadyBreakpoint handles various event sets and exceptions")
    void testWaitForReadyBreakpoint() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);

        // 1. Foreign event followed by ready event
        var foreignEvent = mirror(Event.class, Map.of());
        var foreignSet = mirror(EventSet.class, Map.of(
                "iterator", List.of(foreignEvent).iterator()));
        var readyEvent = mirror(BreakpointEvent.class, Map.of("location", readyLoc));
        var readySet = mirror(EventSet.class, Map.of("iterator", List.of(readyEvent).iterator()));

        List<EventSet> queueItems = new ArrayList<>();
        queueItems.add(null);
        queueItems.addAll(List.of(foreignSet, readySet));
        var customEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if ("remove".equals(m.getName())) {
                        if (!queueItems.isEmpty()) {
                            return queueItems.remove(0);
                        } // if
                        return null;
                    } // if
                    return null;
                });

        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "eventQueue", customEq));

        // foreign breakpoint event
        var foreignBp = mirror(BreakpointEvent.class, Map.of("location", completedLoc));
        var foreignBpSet = mirror(EventSet.class, Map.of(
                "iterator", List.of(foreignBp).iterator()));
        queueItems.add(0, foreignBpSet);

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        session.waitForReadyBreakpoint();
        assertThat(session.isAlive()).isTrue();

        // 2. VMDeathEvent stops wait and marks session dead
        var deathSet = mirror(EventSet.class, Map.of(
                "iterator", List.of(mirror(VMDeathEvent.class, Map.of())).iterator()));
        queueItems.add(deathSet);
        session.waitForReadyBreakpoint();
        assertThat(session.isAlive()).isFalse();

        // 2b. VMDisconnectEvent stops wait and marks session dead
        session.setAlive(true);
        var disconnectSet = mirror(EventSet.class, Map.of(
                "iterator", List.of(mirror(VMDisconnectEvent.class, Map.of())).iterator()));
        queueItems.add(disconnectSet);
        session.waitForReadyBreakpoint();
        assertThat(session.isAlive()).isFalse();

        // 3. Exception in remove marks dead
        session.setAlive(true);
        var errEq = mirror(EventQueue.class, Map.of("remove", new VMDisconnectedException()));
        var errVm = mirror(VirtualMachine.class, Map.of("process", proc, "eventQueue", errEq));
        PersistentGuestSession sessionErr = createMockSession(errVm, readyLoc, completedLoc, proc);
        sessionErr.waitForReadyBreakpoint();
        assertThat(sessionErr.isAlive()).isFalse();

        // 4. InterruptedException in remove re-throws
        session.setAlive(true);
        var interruptEq = mirror(EventQueue.class, Map.of("remove", new InterruptedException()));
        var intVm = mirror(VirtualMachine.class, Map.of("process", proc, "eventQueue", interruptEq));
        PersistentGuestSession sessionInt = createMockSession(intVm, readyLoc, completedLoc, proc);
        assertThatThrownBy(sessionInt::waitForReadyBreakpoint).isInstanceOf(InterruptedException.class);

        // 5. Already dead session exits immediately
        session.setAlive(false);
        session.waitForReadyBreakpoint();
    } // testWaitForReadyBreakpoint

    @Test
    @DisplayName("awaitHarnessType waits until GuestHarness class prepare event")
    void testAwaitHarnessType() throws Exception {
        var nonPrepEvent = mirror(Event.class, Map.of());
        var foreignPrep = mirror(ClassPrepareEvent.class, Map.of(
                "referenceType", mirror(ReferenceType.class, Map.of("name", "Foreign"))));
        var foreignSet = (EventSet) java.lang.reflect.Proxy.newProxyInstance(
                EventSet.class.getClassLoader(),
                new Class<?>[] {EventSet.class},
                (self, m, args) -> {
                    if ("iterator".equals(m.getName())) {
                        return List.of((Event) foreignPrep).iterator();
                    } // if
                    return null;
                });

        var harnessType = mirror(ClassType.class, Map.of(
                "name", GuestHarness.class.getName()));
        var harnessPrep = mirror(ClassPrepareEvent.class, Map.of("referenceType", harnessType));
        var harnessSet = mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) harnessPrep).iterator()));

        var nonPrepSet = mirror(EventSet.class, Map.of("iterator", List.of(nonPrepEvent).iterator()));
        List<EventSet> sets = new ArrayList<>();
        sets.add(null);
        sets.addAll(List.of(nonPrepSet, foreignSet, harnessSet));
        var customEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if ("remove".equals(m.getName())) {
                        return sets.isEmpty() ? null : sets.remove(0);
                    } // if
                    return null;
                });

        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var readyMethod = mirror(Method.class, Map.of("location", readyLoc));
        var completedMethod = mirror(Method.class, Map.of("location", completedLoc));

        var harnessTypeDynamic = (ClassType) java.lang.reflect.Proxy.newProxyInstance(
                ClassType.class.getClassLoader(),
                new Class<?>[] {ClassType.class},
                (self, m, args) -> {
                    if ("name".equals(m.getName())) return GuestHarness.class.getName();
                    if ("methodsByName".equals(m.getName())) {
                        return "readyForJob".equals(args[0]) ? List.of(readyMethod) : List.of(completedMethod);
                    } // if
                    return null;
                });
        var harnessPrepDynamic = mirror(ClassPrepareEvent.class, Map.of("referenceType", harnessTypeDynamic));
        var harnessSetDynamic = mirror(EventSet.class, Map.of(
                "iterator", List.of((Event) harnessPrepDynamic).iterator()));

        List<EventSet> dynamicSets = new ArrayList<>();
        dynamicSets.add(null);
        dynamicSets.addAll(List.of(nonPrepSet, foreignSet, harnessSetDynamic));
        var dynamicEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if ("remove".equals(m.getName())) {
                        return dynamicSets.isEmpty() ? null : dynamicSets.remove(0);
                    } // if
                    return null;
                });

        var cpr = mirror(ClassPrepareRequest.class, Map.of());
        var bpReq = mirror(BreakpointRequest.class, Map.of());
        var erm = (EventRequestManager) java.lang.reflect.Proxy.newProxyInstance(
                EventRequestManager.class.getClassLoader(),
                new Class<?>[] {EventRequestManager.class},
                (self, m, args) -> {
                    if ("createBreakpointRequest".equals(m.getName())) return bpReq;
                    return null;
                });

        var vm = mirror(VirtualMachine.class, Map.of(
                "eventQueue", dynamicEq,
                "eventRequestManager", erm));
        BreakpointRequest[] bps = new BreakpointRequest[2];
        Location[] locs = new Location[2];
        ClassType resolved = PersistentGuestSession.awaitAndInstallSentinels(vm, cpr, bps, locs);
        assertThat(resolved).isSameAs(harnessTypeDynamic);
        assertThat(bps[0]).isSameAs(bpReq);
        assertThat(locs[0]).isSameAs(readyLoc);
    } // testAwaitHarnessType

    @Test
    @DisplayName("teardownJobRequests propagates exception when disabling request fails")
    void testTeardownJobRequests() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var erm = mirror(EventRequestManager.class, Map.of());
        var vm = mirror(VirtualMachine.class, Map.of("process", proc, "eventRequestManager", erm));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        var badReq = mirror(EventRequest.class, Map.of("disable", new RuntimeException("fail")));
        var goodReq = mirror(EventRequest.class, Map.of());

        List<EventRequest> requests = new ArrayList<>(List.of(badReq, goodReq));
        assertThatThrownBy(() -> session.teardownJobRequests(requests))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("fail");
        assertThat(requests).isEmpty();
    } // testTeardownJobRequests

    @Test
    @DisplayName("close handles graceful exit, errors, and already closed states")
    void testCloseScenarios() {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);
        var vm = mirror(VirtualMachine.class, Map.of(
                "process", proc,
                "mirrorOf", mirror(ObjectReference.class, Map.of())));

        PersistentGuestSession session = createMockSession(vm, readyLoc, completedLoc, proc);
        session.close();
        assertThat(session.isAlive()).isFalse();

        // Idempotent close
        session.close();

        // Close with failing dispose and dead process
        var procDead = new FakeProcess(false);
        var failingVm = mirror(VirtualMachine.class, Map.of(
                "process", procDead,
                "dispose", new RuntimeException("failed dispose")));
        PersistentGuestSession sessionFailing = createMockSession(
                failingVm, readyLoc, completedLoc, procDead);
        sessionFailing.close();
        assertThat(sessionFailing.isAlive()).isFalse();
    } // testCloseScenarios

    @Test
    @DisplayName("executeJobEventLoop processes events and stops on job completion or VM death")
    void testExecuteJobEventLoop() throws Exception {
        Location readyLoc = location("cs1302.tracer.guest.GuestHarness", 69);
        Location completedLoc = location("cs1302.tracer.guest.GuestHarness", 79);
        var proc = new FakeProcess(true);

        var normalEvent = mirror(Event.class, Map.of());
        var completeEvent = mirror(BreakpointEvent.class, Map.of("location", completedLoc));

        var set1 = mirror(EventSet.class, Map.of("iterator", List.of(normalEvent).iterator()));
        var set2 = mirror(EventSet.class, Map.of("iterator", List.of((Event) completeEvent).iterator()));

        List<EventSet> eventSets = new ArrayList<>(List.of(set1, set2));
        var customEq = (EventQueue) java.lang.reflect.Proxy.newProxyInstance(
                EventQueue.class.getClassLoader(),
                new Class<?>[] {EventQueue.class},
                (self, m, args) -> {
                    if ("remove".equals(m.getName())) {
                        return eventSets.isEmpty() ? null : eventSets.remove(0);
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
        CompilationResult cr = new CompilationResult(java.nio.file.Path.of("."), Set.of("Student"), "Student");
        var ctx = new PersistentGuestSession.JobContext(
                cr, List.of(BreakpointSpec.of(10)), SourceAnalysis.empty(),
                new InputTracker(""), (line, snap) -> {}, new ArrayList<>(), new HashSet<>(), new AtomicReference<>(), 0, 0, true);

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

        assertThatThrownBy(() -> PersistentGuestSession.awaitAndInstallSentinels(
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
}
