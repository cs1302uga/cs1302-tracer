package cs1302.tracer.trace;

import com.github.javaparser.ast.CompilationUnit;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodExitRequest;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.execution.TraceSession;
import cs1302.tracer.guest.GuestHarness;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a persistent guest JVM worker process and its JDI debugger session.
 *
 * <p>A single persistent session executes multiple tracing jobs sequentially
 * by resetting state on the resident {@link GuestHarness} between runs,
 * eliminating repeated JVM startup and JDWP handshake overhead.</p>
 */
public final class PersistentGuestSession implements AutoCloseable {

    private final VirtualMachine vm;
    private final StreamDrainer vmOut;
    private final StreamDrainer vmErr;
    private final ClassType harnessType;
    private final Field nextClassPathField;
    private final Field nextMainClassField;
    private final Field nextStdinField;
    private final Field shouldTerminateField;
    private final Location readyLocation;
    private final Location completedLocation;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile boolean alive;
    private int completedJobCount;

    /**
     * Private constructor initializing the persistent session components.
     *
     * @param vm The debuggee VirtualMachine.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param harnessType ClassType for GuestHarness.
     * @param readyLocation Location of readyForJob hook.
     * @param completedLocation Location of onJobCompleted hook.
     */
    PersistentGuestSession(
            VirtualMachine vm,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            ClassType harnessType,
            Location readyLocation,
            Location completedLocation) {
        this.vm = vm;
        this.vmOut = vmOut;
        this.vmErr = vmErr;
        this.harnessType = harnessType;
        this.readyLocation = readyLocation;
        this.completedLocation = completedLocation;
        this.nextClassPathField = harnessType.fieldByName("nextClassPath");
        this.nextMainClassField = harnessType.fieldByName("nextMainClass");
        this.nextStdinField = harnessType.fieldByName("nextStdin");
        this.shouldTerminateField = harnessType.fieldByName("shouldTerminate");
        this.alive = true;
        this.completedJobCount = 0;
    } // PersistentGuestSession

    /**
     * Launches a persistent guest JVM and attaches the JDI debugger session.
     *
     * @return Initialized PersistentGuestSession paused at the initial ready hook.
     * @throws Exception If launching or handshake fails.
     */
    public static PersistentGuestSession create() throws Exception {
        LaunchingConnector launchingConnector =
                Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> env = launchingConnector.defaultArguments();

        env.get("main").setValue(GuestHarness.class.getName());

        Path harnessCp = Path.of(GuestHarness.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        String options = "-Djava.awt.headless=true --enable-preview -classpath \""
                + harnessCp.toAbsolutePath() + "\"";
        env.get("options").setValue(options);

        VirtualMachine vm = launchingConnector.launch(env);
        StreamDrainer vmOut = new StreamDrainer(vm.process().getInputStream());
        StreamDrainer vmErr = new StreamDrainer(vm.process().getErrorStream());
        return attach(vm, vmOut, vmErr);
    } // create

    /**
     * Completes handshake and initializes a persistent session from a launched VM.
     *
     * @param vm Target debuggee virtual machine.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @return Initialized PersistentGuestSession paused at the initial ready hook.
     * @throws Exception If handshake or sentinel setup fails.
     */
    static PersistentGuestSession attach(
            VirtualMachine vm, StreamDrainer vmOut, StreamDrainer vmErr) throws Exception {
        try {
            ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
            cpr.addClassFilter(GuestHarness.class.getName());
            cpr.enable();

            vm.resume();

            BreakpointRequest[] bps = new BreakpointRequest[2];
            Location[] locs = new Location[2];
            ClassType harnessType = awaitAndInstallSentinels(vm, cpr, bps, locs);

            PersistentGuestSession session = new PersistentGuestSession(
                    vm, vmOut, vmErr, harnessType, locs[0], locs[1]);

            session.waitForReadyBreakpoint();
            return session;
        } catch (Throwable t) {
            cleanupLaunchFailure(vm, vmOut, vmErr);
            throw t;
        } // try
    } // attach

    /**
     * Cleans up resources allocated during a failed guest JVM launch.
     *
     * @param vm Target debuggee virtual machine.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     */
    static void cleanupLaunchFailure(
            VirtualMachine vm, StreamDrainer vmOut, StreamDrainer vmErr) {
        try {
            vm.dispose();
        } catch (Throwable ignored) {
            // ignore
        } // try
        if (vm.process().isAlive()) {
            vm.process().destroyForcibly();
        } // if
        vmOut.close();
        vmErr.close();
    } // cleanupLaunchFailure

    /**
     * Awaits preparation of GuestHarness and installs sentinel breakpoints while suspended.
     *
     * @param vm Debuggee VirtualMachine.
     * @param cpr Class prepare request for GuestHarness.
     * @param outBps Output array storing created BreakpointRequests (ready, completed).
     * @param outLocs Output array storing sentinel Locations (ready, completed).
     * @return Prepared ClassType for GuestHarness.
     * @throws InterruptedException On cancellation.
     */
    static ClassType awaitAndInstallSentinels(
            VirtualMachine vm,
            ClassPrepareRequest cpr,
            BreakpointRequest[] outBps,
            Location[] outLocs) throws InterruptedException {
        return awaitAndInstallSentinels(vm, cpr, outBps, outLocs, 15000);
    } // awaitAndInstallSentinels

    /**
     * Awaits preparation of GuestHarness and installs sentinel breakpoints with bounded timeout.
     *
     * @param vm Debuggee VirtualMachine.
     * @param cpr Class prepare request for GuestHarness.
     * @param outBps Output array storing created BreakpointRequests (ready, completed).
     * @param outLocs Output array storing sentinel Locations (ready, completed).
     * @param timeoutMs Timeout in milliseconds for the handshake.
     * @return Prepared ClassType for GuestHarness.
     * @throws InterruptedException On cancellation.
     */
    static ClassType awaitAndInstallSentinels(
            VirtualMachine vm,
            ClassPrepareRequest cpr,
            BreakpointRequest[] outBps,
            Location[] outLocs,
            long timeoutMs) throws InterruptedException {
        ClassType harnessType = null;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (System.currentTimeMillis() >= deadline) {
                vm.process().destroyForcibly();
                throw new IllegalStateException(
                        "Timed out waiting for GuestHarness startup handshake");
            } // if
            EventSet eventSet = vm.eventQueue().remove(1000);
            if (eventSet != null) {
                for (Event event : eventSet) {
                    if (event instanceof ClassPrepareEvent prep) {
                        if (prep.referenceType().name().equals(GuestHarness.class.getName())) {
                            harnessType = (ClassType) prep.referenceType();
                        } // if
                    } // if
                } // for
                if (harnessType != null) {
                    cpr.disable();
                    vm.eventRequestManager().deleteEventRequest(cpr);
                    Location readyLoc = harnessType.methodsByName(
                            "readyForJob").getFirst().location();
                    Location completedLoc = harnessType.methodsByName(
                            "onJobCompleted").getFirst().location();
                    BreakpointRequest readyBp =
                            vm.eventRequestManager().createBreakpointRequest(readyLoc);
                    readyBp.enable();
                    BreakpointRequest completedBp =
                            vm.eventRequestManager().createBreakpointRequest(completedLoc);
                    completedBp.enable();
                    outBps[0] = readyBp;
                    outBps[1] = completedBp;
                    outLocs[0] = readyLoc;
                    outLocs[1] = completedLoc;
                    eventSet.resume();
                    return harnessType;
                } // if
                eventSet.resume();
            } // if
        } // while
    } // awaitAndInstallSentinels

    /**
     * Package-private setter for lifecycle state (testing only).
     *
     * @param alive New alive flag value.
     */
    void setAlive(boolean alive) {
        this.alive = alive;
    } // setAlive

    /**
     * Returns the underlying target Process.
     *
     * @return Target process.
     */
    public Process process() {
        return vm.process();
    } // process

    /**
     * Returns whether the persistent guest JVM is alive and ready to process jobs.
     *
     * @return True if session is alive.
     */
    public boolean isAlive() {
        return alive && vm.process().isAlive();
    } // isAlive

    /**
     * Returns the total number of jobs completed by this session instance.
     *
     * @return Completed job count.
     */
    public int completedJobCount() {
        return completedJobCount;
    } // completedJobCount

    /**
     * Executes a trace job and returns snapshots mapped by line number.
     *
     * @param cr Compilation result containing class files and metadata.
     * @param specs Breakpoint specifications.
     * @param parsedSources Parsed AST compilation units.
     * @param stdin Standard input string supplied to guest program.
     * @param accumulate Whether to retain all snapshots per line.
     * @return Map of line number to execution snapshots.
     * @throws Exception On tracing or execution failure.
     */
    public Map<Integer, List<ExecutionSnapshot>> traceWithSpecs(
            CompilationResult cr,
            Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources,
            String stdin,
            boolean accumulate) throws Exception {
        Map<Integer, List<ExecutionSnapshot>> snapshots = new TreeMap<>();
        runJobInternal(cr, specs, parsedSources, stdin, false, (line, snap) -> {
            DebugTraceHelper.storeSnapshot(snapshots, line, snap.materializeOutput());
        });
        return accumulate ? snapshots : DebugTraceHelper.keepLatestOnly(snapshots);
    } // traceWithSpecs

    /**
     * Executes a trace job and returns snapshots in chronological order.
     *
     * @param cr Compilation result containing class files and metadata.
     * @param specs Breakpoint specifications.
     * @param parsedSources Parsed AST compilation units.
     * @param stdin Standard input string supplied to guest program.
     * @return List of execution snapshots in chronological order.
     * @throws Exception On tracing or execution failure.
     */
    public List<ExecutionSnapshot> traceChronologicalWithSpecs(
            CompilationResult cr,
            Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources,
            String stdin) throws Exception {
        List<ExecutionSnapshot> chronological = new ArrayList<>();
        runJobInternal(cr, specs, parsedSources, stdin, true, (line, snap) -> {
            ExecutionSnapshot mat = snap.materializeOutput();
            if (chronological.isEmpty()
                    || !DebugTraceHelper.isSameTopFrame(chronological.getLast(), mat)) {
                chronological.add(mat);
            } // if
        });
        return chronological;
    } // traceChronologicalWithSpecs

    /**
     * Functional consumer for captured execution snapshots.
     */
    @FunctionalInterface
    public interface SnapshotSink {
        /**
         * Consumes an execution snapshot recorded at a given line number.
         *
         * @param line Line number or -1 for entry exit.
         * @param snapshot Recorded execution snapshot.
         */
        void accept(int line, ExecutionSnapshot snapshot);
    } // SnapshotSink

    /**
     * Context record holding state for a single trace job execution loop.
     *
     * @param cr Compilation result.
     * @param safeSpecs Breakpoint specifications.
     * @param sourceAnalysis Precomputed AST analysis.
     * @param inputTracker Guest input tracker.
     * @param sink Snapshot consumer.
     * @param jobRequests List collecting active event requests.
     * @param loadedClasses Loaded reference types.
     * @param jobLoader Tracked child classloader for active job.
     * @param startOut Initial standard output stream length.
     * @param startErr Initial standard error stream length.
     * @param snapMainEnd Whether to capture main exit.
     */
    record JobContext(
            CompilationResult cr,
            Collection<BreakpointSpec> safeSpecs,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            SnapshotSink sink,
            List<EventRequest> jobRequests,
            HashSet<ReferenceType> loadedClasses,
            AtomicReference<ClassLoaderReference> jobLoader,
            int startOut,
            int startErr,
            boolean snapMainEnd) {} // JobContext

    /**
     * Internal execution loop coordinating guest harness dispatch and JDI events.
     *
     * @param cr Compilation result.
     * @param specs Breakpoint specs.
     * @param parsedSources AST units.
     * @param stdin Guest input.
     * @param isChronological True if running chronological trace.
     * @param sink Snapshot consumer.
     * @throws Exception On tracing failure.
     */
    void runJobInternal(
            CompilationResult cr,
            Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources,
            String stdin,
            boolean isChronological,
            SnapshotSink sink) throws Exception {

        if (!isAlive()) {
            throw new IllegalStateException("Persistent guest session is not alive");
        } // if

        vmOut.reset();
        vmErr.reset();
        TraceSession currentSession = TraceSession.current();
        if (currentSession != null) {
            vmOut.attachSession(currentSession);
            vmErr.attachSession(currentSession);
        } // if

        int startOut = 0;
        int startErr = 0;

        harnessType.setValue(nextClassPathField, vm.mirrorOf(cr.classPath().toString()));
        harnessType.setValue(nextMainClassField, vm.mirrorOf(cr.mainClass()));
        harnessType.setValue(nextStdinField, vm.mirrorOf(stdin != null ? stdin : ""));

        List<EventRequest> jobRequests = new ArrayList<>();
        Collection<BreakpointSpec> safeSpecs = specs != null ? specs : Collections.emptyList();
        setupJobRequests(cr, safeSpecs, jobRequests);

        JobContext ctx = new JobContext(
                cr, safeSpecs, SourceAnalysis.from(parsedSources), new InputTracker(stdin),
                sink, jobRequests, new HashSet<>(), new AtomicReference<>(), startOut, startErr,
                safeSpecs.isEmpty() || safeSpecs.stream().anyMatch(s -> s.lineNumber() == -1));

        vm.resume();

        try {
            executeJobEventLoop(ctx);
        } finally {
            cleanupJobRun(currentSession, startOut, startErr, jobRequests);
        } // try

        completedJobCount++;
    } // runJobInternal

    /**
     * Cleans up event requests, output drainers, and checks harness termination after job run.
     *
     * @param currentSession Active trace session.
     * @param startOut Initial standard output stream length.
     * @param startErr Initial standard error stream length.
     * @param jobRequests List of event requests to tear down.
     * @throws InterruptedException On interruption while waiting for ready breakpoint.
     */
    void cleanupJobRun(
            TraceSession currentSession,
            int startOut,
            int startErr,
            List<EventRequest> jobRequests) throws InterruptedException {
        teardownJobRequests(jobRequests);
        vmOut.sync();
        vmErr.sync();
        vmOut.detachSession();
        vmErr.detachSession();
        if (currentSession != null) {
            OutputSlice finalOut = OutputSlice.from(
                    vmOut, startOut, Math.max(0, vmOut.size() - startOut)).materialize();
            OutputSlice finalErr = OutputSlice.from(
                    vmErr, startErr, Math.max(0, vmErr.size() - startErr)).materialize();
            currentSession.finishOutput(finalOut, finalErr);
            currentSession.materializeSnapshots();
            currentSession.setCapturedOutput(
                    finalOut.asUtf8String(), finalErr.asUtf8String(),
                    finalOut.length(), finalErr.length());
            if (currentSession.isStopped()) {
                alive = false;
                vm.process().destroyForcibly();
            } // if
        } // if
        try {
            com.sun.jdi.Value termVal = harnessType.getValue(shouldTerminateField);
            if (termVal instanceof com.sun.jdi.BooleanValue b && b.value()) {
                alive = false;
                vm.process().destroyForcibly();
            } // if
        } catch (Exception ignored) {
            // ignore inspection error
        } // try
        if (isAlive()) {
            vm.resume();
            waitForReadyBreakpoint();
        } // if
    } // cleanupJobRun

    /**
     * Populates and enables event requests for class preparation, exits, and exceptions.
     *
     * @param cr Compilation result.
     * @param safeSpecs Breakpoint specifications.
     * @param jobRequests Target list of event requests.
     */
    private void setupJobRequests(
            CompilationResult cr,
            Collection<BreakpointSpec> safeSpecs,
            List<EventRequest> jobRequests) {

        for (String className : cr.compiledClassNames()) {
            ClassPrepareRequest req = vm.eventRequestManager().createClassPrepareRequest();
            req.addClassFilter(className);
            req.enable();
            jobRequests.add(req);
        } // for

        MethodExitRequest mainExitReq = vm.eventRequestManager().createMethodExitRequest();
        mainExitReq.addClassFilter(cr.mainClass());
        mainExitReq.enable();
        jobRequests.add(mainExitReq);

        String[] readerClasses = {
            "java.util.Scanner", "java.io.BufferedReader", "java.lang.IO",
            "java.io.InputStream", "java.io.BufferedInputStream"
        };
        for (String rc : readerClasses) {
            MethodExitRequest mer = vm.eventRequestManager().createMethodExitRequest();
            mer.addClassFilter(rc);
            mer.enable();
            jobRequests.add(mer);
        } // for

        ExceptionRequest er = vm.eventRequestManager().createExceptionRequest(null, true, true);
        er.enable();
        jobRequests.add(er);
    } // setupJobRequests

    /**
     * Executes the JDI event processing loop for the active job.
     *
     * @param ctx Active job context.
     * @throws Exception On event handling error.
     */
    void executeJobEventLoop(JobContext ctx) throws Exception {
        boolean[] recorded = new boolean[] {false};
        boolean jobComplete = false;
        ObjectReference systemIn = DebugTraceHelper.getSystemIn(vm);

        while (!jobComplete) {
            for (Event event : DebugTraceHelper.nextEvents(vm)) {
                jobComplete = dispatchJobEvent(event, ctx, recorded, systemIn);
                if (jobComplete) {
                    break;
                } // if
                vm.resume();
            } // for
        } // while
    } // executeJobEventLoop

    /**
     * Dispatches a single JDI event during active job execution.
     *
     * @param event JDI event.
     * @param ctx Job execution context.
     * @param recorded Flag array tracking whether any snapshot was recorded.
     * @param systemIn Cached System.in reference.
     * @return True if the job has completed and the event loop should terminate.
     * @throws Exception On snapshot capture failure.
     */
    boolean dispatchJobEvent(
            Event event, JobContext ctx, boolean[] recorded, ObjectReference systemIn)
            throws Exception {
        switch (event) {
        case ClassPrepareEvent cpe -> {
            if (ctx.cr().compiledClassNames().contains(cpe.referenceType().name())) {
                ClassLoaderReference cl = cpe.referenceType().classLoader();
                ctx.jobLoader().compareAndSet(null, cl);
                if (Objects.equals(ctx.jobLoader().get(), cl)) {
                    List<BreakpointRequest> bprs = DebugTraceHelper.registerBreakpoints(
                            vm, cpe.referenceType(), ctx.safeSpecs());
                    ctx.jobRequests().addAll(bprs);
                    ctx.loadedClasses().add(cpe.referenceType());
                } // if
            } // if
        } // case
        case BreakpointEvent bpe -> {
            if (handleBreakpointEvent(bpe, ctx, recorded)) {
                return true;
            } // if
        } // case
        case MethodExitEvent mee -> {
            handleMethodExit(mee, ctx, recorded, systemIn);
        } // case
        case ExceptionEvent ee -> {
            handleExceptionEvent(ee, ctx, recorded);
        } // case
        case VMDeathEvent ignored -> {
            alive = false;
            return true;
        } // case
        case VMDisconnectEvent ignored -> {
            alive = false;
            return true;
        } // case
        default -> {
            // ignore other events
        } // default
        } // switch
        return false;
    } // dispatchJobEvent

    /**
     * Handles a breakpoint event during job execution.
     *
     * @param bpe BreakpointEvent instance.
     * @param ctx Job execution context.
     * @param recorded Flag tracking snapshot recording.
     * @return True if onJobCompleted breakpoint was reached.
     * @throws Exception On snapshot capture failure.
     */
    boolean handleBreakpointEvent(
            BreakpointEvent bpe, JobContext ctx, boolean[] recorded) throws Exception {
        Location loc = bpe.location();
        if (loc.equals(completedLocation)) {
            return true;
        } // if
        ClassLoaderReference cl = loc.declaringType().classLoader();
        if (ctx.jobLoader().get() != null && !Objects.equals(ctx.jobLoader().get(), cl)) {
            return false;
        } // if
        if (ctx.cr().compiledClassNames().contains(loc.declaringType().name())) {
            ExecutionSnapshot snap = DebugTraceHelper.snapshotTheWorld(
                    bpe.thread(), ctx.loadedClasses(), vmOut, vmErr, ctx.sourceAnalysis(),
                    ctx.inputTracker(), ctx.startOut(), ctx.startErr()).materializeOutput();
            ctx.sink().accept(loc.lineNumber(), snap);
            recorded[0] = true;
        } // if
        return false;
    } // handleBreakpointEvent

    /**
     * Handles method exit events during job execution.
     *
     * @param mee MethodExitEvent instance.
     * @param ctx Job execution context.
     * @param recorded Flag tracking snapshot recording.
     * @param systemIn System.in reference.
     * @throws Exception On snapshot capture failure.
     */
    void handleMethodExit(
            MethodExitEvent mee, JobContext ctx, boolean[] recorded, ObjectReference systemIn)
            throws Exception {
        ClassLoaderReference cl = mee.method().declaringType().classLoader();
        if (ctx.jobLoader().get() != null && cl != null
                && !Objects.equals(ctx.jobLoader().get(), cl)) {
            return;
        } // if
        if (DebugTraceHelper.isMainMethodExit(mee.method())
                && ctx.cr().mainClass().equals(mee.method().declaringType().name())) {
            if (ctx.snapMainEnd() || !recorded[0]) {
                ExecutionSnapshot snap = DebugTraceHelper.snapshotTheWorld(
                        mee.thread(), ctx.loadedClasses(), vmOut, vmErr, ctx.sourceAnalysis(),
                        ctx.inputTracker(), ctx.startOut(), ctx.startErr()).materializeOutput();
                ctx.sink().accept(-1, snap);
                recorded[0] = true;
            } // if
        } else {
            DebugTraceHelper.handleReaderMethodExit(mee, ctx.inputTracker(),
                    systemIn != null ? systemIn : DebugTraceHelper.getSystemIn(vm));
        } // if
    } // handleMethodExit

    /**
     * Handles exception events during job execution.
     *
     * @param ee ExceptionEvent instance.
     * @param ctx Job execution context.
     * @param recorded Flag tracking snapshot recording.
     * @throws Exception On snapshot capture failure.
     */
    void handleExceptionEvent(
            ExceptionEvent ee, JobContext ctx, boolean[] recorded) throws Exception {
        Location loc = ee.location();
        Location catchLoc = ee.catchLocation();
        ClassLoaderReference cl = loc != null ? loc.declaringType().classLoader() : null;
        if (ctx.jobLoader().get() != null && cl != null
                && !Objects.equals(ctx.jobLoader().get(), cl)) {
            return;
        } // if
        boolean uncaughtInStudent = catchLoc == null
                || !ctx.cr().compiledClassNames().contains(catchLoc.declaringType().name());
        if (loc != null && ctx.cr().compiledClassNames().contains(loc.declaringType().name())
                && uncaughtInStudent) {
            TraceSession currentSession = TraceSession.current();
            if (currentSession != null) {
                currentSession.guestException(ee.exception().referenceType().name());
            } // if
            ExecutionSnapshot snap = DebugTraceHelper.snapshotTheWorld(
                    ee.thread(), ctx.loadedClasses(), vmOut, vmErr, ctx.sourceAnalysis(),
                    ctx.inputTracker(), ctx.startOut(), ctx.startErr()).materializeOutput();
            ctx.sink().accept(loc.lineNumber(), snap);
            if (!recorded[0]) {
                ctx.sink().accept(-1, snap);
            } // if
            recorded[0] = true;
        } // if
    } // handleExceptionEvent

    /**
     * Disables and removes event requests registered for a completed job.
     *
     * @param jobRequests List of event requests to clean up.
     */
    void teardownJobRequests(List<EventRequest> jobRequests) {
        for (EventRequest req : jobRequests) {
            try {
                req.disable();
                vm.eventRequestManager().deleteEventRequest(req);
            } catch (Exception ignored) {
                // ignore teardown errors
            } // try
        } // for
        jobRequests.clear();
    } // teardownJobRequests

    /**
     * Suspends execution and waits for the guest harness to reach the ready hook.
     */
    void waitForReadyBreakpoint() throws InterruptedException {
        boolean ready = false;
        while (!ready && isAlive()) {
            EventSet eventSet = null;
            try {
                eventSet = vm.eventQueue().remove(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                alive = false;
                break;
            } // try
            if (eventSet != null) {
                for (Event event : eventSet) {
                    if (event instanceof BreakpointEvent bpe) {
                        if (bpe.location().equals(readyLocation)) {
                            ready = true;
                            break;
                        } // if
                    } // if
                    if (event instanceof VMDeathEvent
                            || event instanceof VMDisconnectEvent) {
                        alive = false;
                        ready = true;
                        break;
                    } // if
                } // for
                if (!ready) {
                    eventSet.resume();
                } // if
            } // if
        } // while
    } // waitForReadyBreakpoint

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        } // if
        boolean wasAlive = alive;
        alive = false;
        if (wasAlive) {
            try {
                harnessType.setValue(shouldTerminateField, vm.mirrorOf(true));
                vm.resume();
                vm.process().waitFor(500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // ignore termination errors
            } // try
        } // if
        try {
            vm.dispose();
        } catch (Exception ignored) {
            // ignore dispose errors
        } // try
        if (vm.process().isAlive()) {
            vm.process().destroyForcibly();
        } // if
        vmOut.close();
        vmErr.close();
    } // close
} // PersistentGuestSession
