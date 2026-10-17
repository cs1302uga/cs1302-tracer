package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import com.sun.jdi.event.EventSet;
import com.github.javaparser.ast.CompilationUnit;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodExitRequest;
import cs1302.tracer.internal.CompilationHelper.CompilationResult;
import cs1302.tracer.internal.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.internal.execution.TraceSession;
import cs1302.tracer.internal.execution.SnapshotRetention;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A collection of methods that are used to generate a debug trace.
 *
 * <p>Normative References: <ul> <li>The Java Debug Interface (JDI) Specification (Java SE 21 Edition,
 * {@code com.sun.jdi}). <li>Java Platform Debugger Architecture (JPDA) Connection and Invocation Architecture.
 * <li>Online Python Tutor (OPT) Trace Event Format v3 Specification (Philip Guo, 2013). </ul>
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public class DebugTraceHelper {

    /**
     * Private constructor to prevent direct instantiation of utility class.
     */
    private DebugTraceHelper() {} // DebugTraceHelper

    /**
     * Take snapshots of a program's execution state at the given breakpoints.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The source line numbers to snapshot at.
     * @param parsedSource Parsed source code for the compiled program.
     * @return A mapping from breakpoint line numbers to a list of execution snapshots.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static Map<Integer, List<ExecutionSnapshot>> trace(CompilationResult compilationResult,
            Collection<Integer> breakPoints, CompilationUnit parsedSource)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, breakPoints, List.of(parsedSource), "");
    } // trace

    /**
     * Take snapshots of a program's execution state with stdin using a single parsed source.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The source line numbers to snapshot at.
     * @param parsedSource Parsed source code for the compiled program.
     * @param stdin The standard input string.
     * @return A mapping from breakpoint line numbers to a list of execution snapshots.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static Map<Integer, List<ExecutionSnapshot>> trace(CompilationResult compilationResult,
            Collection<Integer> breakPoints, CompilationUnit parsedSource, String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, breakPoints, List.of(parsedSource), stdin);
    } // trace

    /**
     * Take snapshots of a program's execution state using multiple parsed source files.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The source line numbers to snapshot at.
     * @param parsedSources Parsed source codes for the compiled program.
     * @return A mapping from breakpoint line numbers to a list of execution snapshots.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static Map<Integer, List<ExecutionSnapshot>> trace(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, breakPoints, parsedSources, "");
    } // trace

    /**
     * Take snapshots of a program's execution state with stdin using multiple parsed source files.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The source line numbers to snapshot at.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param stdin The standard input string.
     * @return A mapping from breakpoint line numbers to a list of execution snapshots.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static Map<Integer, List<ExecutionSnapshot>> trace(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources, String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        return traceWithSpecs(compilationResult, toSpecs(breakPoints), parsedSources, stdin);
    } // trace

    /**
     * Take snapshots of a program's execution state with stdin using breakpoint specifications.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param specs The breakpoint specifications to snapshot at.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param stdin The standard input string.
     * @return A mapping from breakpoint line numbers to a list of execution snapshots.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceWithSpecs(CompilationResult compilationResult,
            Collection<BreakpointSpec> specs, List<CompilationUnit> parsedSources, String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        if (TraceSession.current() != null && TraceSession.current().threadCapture() != null) {
            throw new IllegalArgumentException("Multithread capture requires chronological tracing");
        } // if
        Collection<BreakpointSpec> safeSpecs = specs != null ? specs : Collections.emptyList();
        boolean snapMainEnd = safeSpecs.isEmpty() || safeSpecs.stream().anyMatch(s -> s.lineNumber() == -1);
        Map<Integer, List<ExecutionSnapshot>> snapshots = new HashMap<>();

        VirtualMachine vm = startVmWithCprs(compilationResult);
        writeGuestStdin(vm, stdin);
        InputObservation inputObservation = new InputObservation(stdin);
        InputObservation.registerRequests(vm, new ArrayList<>());
        try (StreamDrainer vmErrDrainer = new StreamDrainer(vm.process().getErrorStream());
                StreamDrainer vmOutDrainer = new StreamDrainer(vm.process().getInputStream())) {

            if (snapMainEnd) {
                MethodExitRequest methodExitRequest = vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            ExceptionRequest exceptionRequest = vm.eventRequestManager().createExceptionRequest(null, false, true);
            exceptionRequest.enable();

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            SourceAnalysis sourceAnalysis = SourceAnalysis.from(parsedSources);
            processBreakpointsEventLoop(vm, compilationResult, safeSpecs, sourceAnalysis, snapshots, loadedClasses,
                    vmOutDrainer, vmErrDrainer, snapMainEnd, inputObservation);

            awaitGuestExit(vm);
            vmErrDrainer.sync();
            vmOutDrainer.sync();
            syncTrailingStreamOutput(snapshots, vmOutDrainer, vmErrDrainer);

            return snapshots;
        } finally {
            cleanupVm(vm);
        } // try
    } // traceWithSpecs

    /**
     * Captures only the latest state per selected line for non-accumulating CLI output.
     *
     * @param compilationResult Compiled program.
     * @param breakPoints Selected line numbers.
     * @param parsedSources Parsed sources.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatest(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources) throws Exception {
        return traceLatest(compilationResult, breakPoints, parsedSources, "");
    } // traceLatest

    /**
     * Captures only the latest state per selected line with stdin.
     *
     * @param compilationResult Compiled program.
     * @param breakPoints Selected line numbers.
     * @param parsedSources Parsed sources.
     * @param stdin Standard input string.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatest(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources, String stdin) throws Exception {
        return traceLatestWithSpecs(compilationResult, toSpecs(breakPoints), parsedSources, stdin);
    } // traceLatest

    /**
     * Captures only the latest state per selected breakpoint specification.
     *
     * @param compilationResult Compiled program.
     * @param specs Selected breakpoint specifications.
     * @param parsedSources Parsed sources.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatestWithSpecs(CompilationResult compilationResult,
            Collection<BreakpointSpec> specs, List<CompilationUnit> parsedSources) throws Exception {
        return traceLatestWithSpecs(compilationResult, specs, parsedSources, "");
    } // traceLatestWithSpecs

    /**
     * Captures only the latest state per selected breakpoint specification with stdin.
     *
     * @param compilationResult Compiled program.
     * @param specs Selected breakpoint specifications.
     * @param parsedSources Parsed sources.
     * @param stdin Standard input string.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatestWithSpecs(CompilationResult compilationResult,
            Collection<BreakpointSpec> specs, List<CompilationUnit> parsedSources, String stdin) throws Exception {
        Map<Integer, List<ExecutionSnapshot>> raw;
        if (TraceSession.current() != null) {
            raw = traceWithSpecs(compilationResult, specs, parsedSources, stdin);
        } else {
            try (TraceSession session = new TraceSession(cs1302.tracer.api.TraceLimits.unlimited(),
                    cs1302.tracer.api.InspectionPolicy.TRUSTED, false, TraceSession.shouldEvalEnumHash())) {
                session.phase("trace");
                raw = traceWithSpecs(compilationResult, specs, parsedSources, stdin);
            } // try
        } // if
        return keepLatestOnly(raw);
    } // traceLatestWithSpecs

    /**
     * Reduces snapshot lists to only their latest entry per distinct source file and line.
     *
     * @param snapshots Breakpoint snapshot mapping.
     * @return Reduced mapping containing the latest snapshot per source file for each line.
     */
    static Map<Integer, List<ExecutionSnapshot>> keepLatestOnly(Map<Integer, List<ExecutionSnapshot>> snapshots) {
        return SnapshotRetention.keepLatestOnly(snapshots);
    } // keepLatestOnly

    /**
     * Runs the event loop for the breakpoint trace.
     *
     * @param vm The JDI VirtualMachine.
     * @param compilationResult The compilation result.
     * @param breakPoints Breakpoint specifications.
     * @param sourceAnalysis Precomputed source analysis.
     * @param snapshots Target map to accumulate snapshots.
     * @param loadedClasses Set of loaded reference types.
     * @param vmOut Drainer for stdout.
     * @param vmErr Drainer for stderr.
     * @param snapMainEnd True if main exit should be captured.
     * @param inputObservation Input observation.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processBreakpointsEventLoop(VirtualMachine vm, CompilationResult compilationResult,
            Collection<BreakpointSpec> breakPoints, SourceAnalysis sourceAnalysis,
            Map<Integer, List<ExecutionSnapshot>> snapshots, HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut,
            StreamDrainer vmErr, boolean snapMainEnd, InputObservation inputObservation) throws InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        inputObservation.start(vm);
        boolean endEventLoop = false;
        while (!endEventLoop) {
            Iterable<Event> events = nextEvents(vm);
            for (Event event : events) {
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    if (compilationResult.compiledClassNames().contains(cpe.referenceType().name())) {
                        registerBreakpoints(vm, cpe.referenceType(), breakPoints);
                        loadedClasses.add(cpe.referenceType());
                    } // if
                } // case
                case BreakpointEvent bpe -> {
                    Location loc = bpe.location();
                    if (compilationResult.compiledClassNames().contains(loc.declaringType().name())) {
                        int line = loc.lineNumber();
                        ExecutionSnapshot snapshot = SnapshotCapture.capture(bpe.thread(), loadedClasses,
                                sourceAnalysis, inputObservation,
                                JobOutput.fromStart(vmOut, vmErr), null);
                        storeSnapshot(snapshots, line, snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && (snapMainEnd || snapshots.isEmpty())) {
                        ExecutionSnapshot snapshot = SnapshotCapture.capture(mee.thread(), loadedClasses,
                                sourceAnalysis, inputObservation,
                                JobOutput.fromStart(vmOut, vmErr), null);
                        snapshots.put(-1, new ArrayList<>(List.of(snapshot)));
                    } else {
                        inputObservation.record(mee);
                    } // if
                } // case
                case ExceptionEvent ee -> processBreakpointExceptionEvent(ee, compilationResult, loadedClasses, vmOut,
                        vmErr, sourceAnalysis, inputObservation, snapshots);
                case VMDeathEvent vde -> endEventLoop = true;
                case VMDisconnectEvent vde -> endEventLoop = true;
                default -> {
                    // do nothing
                } // default
                } // switch

            } // for
            if (events instanceof com.sun.jdi.event.EventSet set && !endEventLoop) {
                set.resume();
            } // if
        } // while
    } // processBreakpointsEventLoop

    /**
     * Handles an ExceptionEvent during the breakpoint event loop.
     *
     * @param ee The exception event.
     * @param compilationResult The compilation result.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputObservation Input observation.
     * @param snapshots Target snapshot map.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processBreakpointExceptionEvent(ExceptionEvent ee, CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut, StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputObservation inputObservation, Map<Integer, List<ExecutionSnapshot>> snapshots)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        processBreakpointExceptionEvent(ee, compilationResult, loadedClasses, vmOut, vmErr, sourceAnalysis,
                inputObservation, snapshots, 0, 0);
    } // processBreakpointExceptionEvent

    /**
     * Processes an unhandled ExceptionEvent with output offset bounds.
     *
     * @param ee The ExceptionEvent.
     * @param compilationResult The compilation result.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputObservation Input observation.
     * @param snapshots Target snapshot map.
     * @param startOutOffset Starting standard output offset.
     * @param startErrOffset Starting standard error offset.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processBreakpointExceptionEvent(ExceptionEvent ee, CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut, StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputObservation inputObservation, Map<Integer, List<ExecutionSnapshot>> snapshots,
            int startOutOffset, int startErrOffset)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        recordException(ee);
        Location loc = ee.location();
        if (loc != null && compilationResult.compiledClassNames().contains(loc.declaringType().name())) {
            int line = loc.lineNumber();
            ExecutionSnapshot snapshot = SnapshotCapture.capture(ee.thread(), loadedClasses, sourceAnalysis,
                    inputObservation, new JobOutput(vmOut, vmErr, startOutOffset, startErrOffset), null);
            storeSnapshot(snapshots, line, snapshot);
            if (!snapshots.containsKey(-1)) {
                snapshots.put(-1, new ArrayList<>(List.of(snapshot)));
            } // if
        } // if
    } // processBreakpointExceptionEvent

    /**
     * Registers breakpoint requests on a newly prepared class.
     *
     * @param vm The JDI VirtualMachine.
     * @param refType The loaded reference type.
     * @param breakPoints Source-qualified or unqualified breakpoint specifications.
     * @return List of created and enabled breakpoint requests.
     * @throws AbsentInformationException If line info is absent.
     */
    static List<BreakpointRequest> registerBreakpoints(VirtualMachine vm, ReferenceType refType,
            Collection<BreakpointSpec> breakPoints) throws AbsentInformationException {
        List<BreakpointRequest> created = new ArrayList<>();
        Set<Location> registeredLocations = new HashSet<>();
        for (BreakpointSpec spec : breakPoints) {
            if (spec.lineNumber() <= 0) {
                continue;
            } // if
            if (spec.matchesReferenceType(refType)) {
                List<Location> locations = refType.locationsOfLine(spec.lineNumber());
                if (!locations.isEmpty()) {
                    Location loc = locations.get(0);
                    if (registeredLocations.add(loc)) {
                        BreakpointRequest req = vm.eventRequestManager().createBreakpointRequest(loc);
                        req.enable();
                        created.add(req);
                    } // if
                } // if
            } // if
        } // for
        return created;
    } // registerBreakpoints

    /**
     * Converts integer line numbers to BreakpointSpec instances.
     *
     * @param breakPoints The line numbers.
     * @return Collection of BreakpointSpecs, or empty list if breakPoints is null.
     */
    static Collection<BreakpointSpec> toSpecs(Collection<Integer> breakPoints) {
        if (breakPoints == null) {
            return Collections.emptyList();
        } // if
        return breakPoints.stream().map(BreakpointSpec::of).toList();
    } // toSpecs

    /**
     * Recognizes exits from non-private {@code void main(String[])} or {@code void main()} methods,
     * whether static or instance methods.
     *
     * @param method The JDI method.
     * @return True if method is main.
     */
    static boolean isMainMethodExit(Method method) {
        String mainJniSignature = "([Ljava/lang/String;)V";
        String noArgJniSignature = "()V";
        return !method.isPrivate() && method.name().equals("main")
                && (method.signature().equals(mainJniSignature) || method.signature().equals(noArgJniSignature));
    } // isMainMethodExit

    /**
     * Cleans up and disposes the JDI VirtualMachine safely.
     *
     * @param vm Target VM.
     */
    private static void cleanupVm(VirtualMachine vm) {
        try {
            Process process = vm.process();
            if (process != null) {
                process.destroyForcibly();
            } // if
        } catch (Exception ignored) {
            // ignore process error
        } // try
        try {
            vm.dispose();
        } catch (VMDisconnectedException | IllegalStateException ignored) {
            // ignore cleanup error
        } // try
    } // cleanupVm

    /**
     * Briefly waits for final guest output without hiding an interrupt.
     *
     * @param vm Guest debugger connection.
     */
    private static void awaitGuestExit(VirtualMachine vm) {
        TraceSession session = TraceSession.current();
        if (session != null) {
            session.awaitGuestExit();
            return;
        } // if
        try {
            vm.process().waitFor(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // The debugger may already have disconnected.
        } // try
    } // awaitGuestExit

    /**
     * Checks whether two execution snapshots have the same top-level call frame.
     *
     * @param s1 First snapshot.
     * @param s2 Second snapshot.
     * @return True if both have the same method and line number on top.
     */
    static boolean isSameTopFrame(ExecutionSnapshot s1, ExecutionSnapshot s2) {
        if (s1.stack().isEmpty() || s2.stack().isEmpty()) {
            return false;
        } // if
        ExecutionSnapshot.StackSnapshot f1 = s1.stack().getLast();
        ExecutionSnapshot.StackSnapshot f2 = s2.stack().getLast();
        return f1.methodName().equals(f2.methodName()) && f1.methodLine() == f2.methodLine();
    } // isSameTopFrame

    /**
     * Checks whether an execution snapshot is completely redundant with a previous snapshot.
     *
     * @param prev Previous execution snapshot.
     * @param current Current execution snapshot.
     * @return True if both snapshots share identical location and execution state.
     */
    static boolean isRedundantSnapshot(ExecutionSnapshot prev, ExecutionSnapshot current) {
        return SnapshotRetention.isRedundantSnapshot(prev, current);
    } // isRedundantSnapshot

    /**
     * Synchronizes any trailing standard output or standard error bytes to the final snapshot.
     *
     * @param chronologicalSnapshots List of snapshots.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     */
    private static void syncTrailingStreamOutput(List<ExecutionSnapshot> chronologicalSnapshots, StreamDrainer vmOut,
            StreamDrainer vmErr) {
        if (!chronologicalSnapshots.isEmpty()) {
            JobOutput.Finished output = JobOutput.fromStart(vmOut, vmErr).finish();
            chronologicalSnapshots.replaceAll(output::detach);
            OutputSlice finalErr = output.stderr();
            OutputSlice finalOut = output.stdout();
            ExecutionSnapshot last = chronologicalSnapshots.getLast();
            if (finalErr.length() > last.stderrLength() || finalOut.length() > last.stdoutLength()) {
                chronologicalSnapshots.set(chronologicalSnapshots.size() - 1, last.withOutput(finalOut, finalErr));
            } // if
        } // if
    } // syncTrailingStreamOutput

    /**
     * Synchronizes any trailing standard output or standard error bytes to the final snapshot.
     *
     * @param snapshots Map of line numbers to snapshots.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     */
    private static void syncTrailingStreamOutput(Map<Integer, List<ExecutionSnapshot>> snapshots, StreamDrainer vmOut,
            StreamDrainer vmErr) {
        JobOutput.Finished output = JobOutput.fromStart(vmOut, vmErr).finish();
        OutputSlice finalErr = output.stderr();
        OutputSlice finalOut = output.stdout();
        for (Map.Entry<Integer, List<ExecutionSnapshot>> entry : snapshots.entrySet()) {
            List<ExecutionSnapshot> list = entry.getValue();
            if (list != null && !list.isEmpty()) {
                list = new ArrayList<>(list);
                list.replaceAll(output::detach);
                entry.setValue(list);
                ExecutionSnapshot last = list.getLast();
                if (finalErr.length() > last.stderrLength() || finalOut.length() > last.stdoutLength()) {
                    list.set(list.size() - 1, last.withOutput(finalOut, finalErr));
                } // if
            } // if
        } // for
    } // syncTrailingStreamOutput

    /**
     * Sanitizes captured standard error bytes in an OutputSlice by removing JVM diagnostic banner prefixes without
     * allocating unnecessary intermediate byte arrays.
     *
     * @param rawStderr Raw stderr OutputSlice.
     * @return Sanitized stderr OutputSlice.
     */
    public static OutputSlice sanitizeDebuggeeStderrSlice(OutputSlice rawStderr) {
        return JobOutput.sanitizeStderr(rawStderr);
    } // sanitizeDebuggeeStderrSlice

    /**
     * Sanitizes captured standard error bytes from the debuggee VM by removing JVM diagnostic banner lines emitted
     * before user program execution (such as {@code Picked up JAVA_TOOL_OPTIONS}).
     *
     * @param rawStderr Raw stderr bytes from the debuggee process.
     * @return Sanitized stderr bytes.
     */
    static byte[] sanitizeDebuggeeStderr(byte[] rawStderr) {
        return rawStderr == null ? null : JobOutput.sanitizeStderr(OutputSlice.from(rawStderr)).toByteArray();
    } // sanitizeDebuggeeStderr

    /**
     * Take a snapshot of a program's execution state just before the main method returns.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param parsedSource Parsed source code.
     * @return An execution snapshot taken at the end of the main method.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static ExecutionSnapshot trace(CompilationResult compilationResult, CompilationUnit parsedSource)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, parsedSource, "");
    } // trace

    /**
     * Take a snapshot of a program's execution state just before the main method returns with stdin.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param parsedSource Parsed source code.
     * @param stdin Standard input string.
     * @return An execution snapshot taken at the end of the main method.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static ExecutionSnapshot trace(CompilationResult compilationResult, CompilationUnit parsedSource,
            String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, null, List.of(parsedSource), stdin).get(-1).getLast();
    } // trace

    /**
     * Take a snapshot of a program's execution state just before the main method returns.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param parsedSources Parsed source codes.
     * @return An execution snapshot taken at the end of the main method.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static ExecutionSnapshot trace(CompilationResult compilationResult, List<CompilationUnit> parsedSources)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, parsedSources, "");
    } // trace

    /**
     * Take a snapshot of a program's execution state just before the main method returns with stdin.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param parsedSources Parsed source codes.
     * @param stdin Standard input string.
     * @return An execution snapshot taken at the end of the main method.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static ExecutionSnapshot trace(CompilationResult compilationResult, List<CompilationUnit> parsedSources,
            String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return trace(compilationResult, null, parsedSources, stdin).get(-1).getLast();
    } // trace

    /**
     * Run a program under JDI and capture all snapshots in chronological order.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param breakPoints The collection of line numbers where breakpoints should be placed.
     * @param parsedSource Parsed source code for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits.
     * @return A list of execution snapshots in chronological order.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static List<ExecutionSnapshot> traceChronological(CompilationResult compilationResult,
            Collection<Integer> breakPoints, CompilationUnit parsedSource, boolean includeMainExit)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return traceChronological(compilationResult, breakPoints, List.of(parsedSource), includeMainExit, "");
    } // traceChronological

    /**
     * Run a program under JDI and capture all snapshots in chronological order with stdin.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param breakPoints The collection of line numbers where breakpoints should be placed.
     * @param parsedSource Parsed source code for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits.
     * @param stdin Standard input string.
     * @return A list of execution snapshots in chronological order.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static List<ExecutionSnapshot> traceChronological(CompilationResult compilationResult,
            Collection<Integer> breakPoints, CompilationUnit parsedSource, boolean includeMainExit, String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return traceChronological(compilationResult, breakPoints, List.of(parsedSource), includeMainExit, stdin);
    } // traceChronological

    /**
     * Run a program under JDI and capture all snapshots in chronological order.
     *
     * @param compilationResult CompilationResult from compilation.
     * @param breakPoints The collection of line numbers where breakpoints should be placed.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits.
     * @return A list of execution snapshots in chronological order.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static List<ExecutionSnapshot> traceChronological(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources, boolean includeMainExit)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        return traceChronological(compilationResult, breakPoints, parsedSources, includeMainExit, "");
    } // traceChronological

    /**
     * Run a program under JDI and capture all snapshots in chronological order with stdin.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The collection of line numbers where breakpoints should be placed.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits at the end.
     * @param stdin Standard input string.
     * @return A list of execution snapshots in chronological order.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static List<ExecutionSnapshot> traceChronological(CompilationResult compilationResult,
            Collection<Integer> breakPoints, List<CompilationUnit> parsedSources, boolean includeMainExit, String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        return traceChronologicalWithSpecs(compilationResult, toSpecs(breakPoints), parsedSources, includeMainExit,
                stdin);
    } // traceChronological

    /**
     * Run a program under JDI and capture snapshots chronologically with BreakpointSpecs.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param specs The collection of BreakpointSpecs where breakpoints should be placed.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits at the end.
     * @param stdin Standard input string.
     * @return A list of execution snapshots in chronological order.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    public static List<ExecutionSnapshot> traceChronologicalWithSpecs(CompilationResult compilationResult,
            Collection<BreakpointSpec> specs, List<CompilationUnit> parsedSources, boolean includeMainExit,
            String stdin)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        VirtualMachine vm = startVmWithCprs(compilationResult);
        writeGuestStdin(vm, stdin);
        InputObservation inputObservation = new InputObservation(stdin);
        InputObservation.registerRequests(vm, new ArrayList<>());
        List<ExecutionSnapshot> chronologicalSnapshots = new ArrayList<>();

        try (StreamDrainer vmErrDrainer = new StreamDrainer(vm.process().getErrorStream());
                StreamDrainer vmOutDrainer = new StreamDrainer(vm.process().getInputStream())) {

            if (includeMainExit) {
                MethodExitRequest methodExitRequest = vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            ExceptionRequest exceptionRequest = vm.eventRequestManager().createExceptionRequest(null, false, true);
            exceptionRequest.enable();

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            SourceAnalysis sourceAnalysis = SourceAnalysis.from(parsedSources);
            Collection<BreakpointSpec> safeSpecs = specs != null ? specs : Collections.emptyList();
            processChronologicalEventLoop(vm, compilationResult, safeSpecs, sourceAnalysis, chronologicalSnapshots,
                    loadedClasses, vmOutDrainer, vmErrDrainer, includeMainExit, inputObservation);

            awaitGuestExit(vm);
            vmErrDrainer.sync();
            vmOutDrainer.sync();
            syncTrailingStreamOutput(chronologicalSnapshots, vmOutDrainer, vmErrDrainer);

            return chronologicalSnapshots;
        } finally {
            cleanupVm(vm);
        } // try
    } // traceChronologicalWithSpecs

    /**
     * Processes the chronological event loop.
     *
     * @param vm Target VM.
     * @param compilationResult Compilation result.
     * @param breakPoints Breakpoint specifications.
     * @param sourceAnalysis Precomputed source analysis.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Drainer for stdout.
     * @param vmErr Drainer for stderr.
     * @param includeMainExit True to include main exit.
     * @param inputObservation Input observation.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException If class not loaded.
     */
    static void processChronologicalEventLoop(VirtualMachine vm, CompilationResult compilationResult,
            Collection<BreakpointSpec> breakPoints, SourceAnalysis sourceAnalysis,
            List<ExecutionSnapshot> chronologicalSnapshots, HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut,
            StreamDrainer vmErr, boolean includeMainExit,
            InputObservation inputObservation) throws InterruptedException,
            IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        inputObservation.start(vm);
        boolean endEventLoop = false;
        while (!endEventLoop) {
            Iterable<Event> events = nextEvents(vm);
            for (Event event : events) {
                captureLifecycle(event, loadedClasses, vmOut, vmErr, sourceAnalysis, inputObservation,
                        chronologicalSnapshots);
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    if (compilationResult.compiledClassNames().contains(cpe.referenceType().name())) {
                        registerBreakpoints(vm, cpe.referenceType(), breakPoints);
                        loadedClasses.add(cpe.referenceType());
                    } // if
                } // case
                case BreakpointEvent bpe -> {
                    Location breakLocation = bpe.location();
                    if (compilationResult.compiledClassNames().contains(breakLocation.declaringType().name())) {
                        ExecutionSnapshot snapshot = SnapshotCapture.capture(bpe.thread(), loadedClasses,
                                sourceAnalysis, inputObservation,
                                JobOutput.fromStart(vmOut, vmErr), null);
                        chronologicalSnapshots.add(snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && includeMainExit) {
                        ExecutionSnapshot snapshot = SnapshotCapture.capture(mee.thread(), loadedClasses,
                                sourceAnalysis, inputObservation,
                                JobOutput.fromStart(vmOut, vmErr), null);
                        if (chronologicalSnapshots.isEmpty()
                                || !isRedundantSnapshot(chronologicalSnapshots.getLast(), snapshot)) {
                            chronologicalSnapshots.add(snapshot);
                        } // if
                    } else {
                        inputObservation.record(mee);
                    } // if
                } // case
                case ExceptionEvent ee -> processChronologicalExceptionEvent(ee, compilationResult, loadedClasses,
                        vmOut, vmErr, sourceAnalysis, inputObservation, chronologicalSnapshots);
                case VMDeathEvent vde -> endEventLoop = true;
                case VMDisconnectEvent vde -> endEventLoop = true;
                default -> {
                    // do nothing
                } // default
                } // switch
                if (endEventLoop) {
                    break;
                } // if
            } // for
            if (events instanceof com.sun.jdi.event.EventSet set && !endEventLoop) {
                set.resume();
            } // if
        } // while
    } // processChronologicalEventLoop

    /**
     * Captures thread starts and deaths and updates event metadata.
     *
     * @param event Debugger event.
     * @param loadedClasses Submitted loaded types.
     * @param vmOut Output drainer.
     * @param vmErr Error drainer.
     * @param analysis Source analysis.
     * @param input Input tracking.
     * @param snapshots Chronological states.
     * @throws IncompatibleThreadStateException If stacks cannot be read.
     * @throws AbsentInformationException If debug information is unavailable.
     * @throws ClassNotLoadedException If a referenced type is unavailable.
     */
    private static void captureLifecycle(Event event, HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut,
            StreamDrainer vmErr, SourceAnalysis analysis, InputObservation input, List<ExecutionSnapshot> snapshots)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        TraceSession active = TraceSession.current();
        if (active != null && active.threadCapture() != null) {
            ThreadReference lifecycle = active.threadCapture().observe(event);
            if (lifecycle != null) {
                snapshots.add(SnapshotCapture.capture(lifecycle, loadedClasses, analysis, input,
                                JobOutput.fromStart(vmOut, vmErr), null));
            } // if
        } // if
    } // captureLifecycle

    /**
     * Handles an ExceptionEvent during the chronological event loop.
     *
     * @param ee The exception event.
     * @param compilationResult The compilation result.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputObservation Input observation.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processChronologicalExceptionEvent(ExceptionEvent ee, CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut, StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputObservation inputObservation, List<ExecutionSnapshot> chronologicalSnapshots)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        processChronologicalExceptionEvent(ee, compilationResult, loadedClasses, vmOut, vmErr, sourceAnalysis,
                inputObservation, chronologicalSnapshots, 0, 0);
    } // processChronologicalExceptionEvent

    /**
     * Processes an unhandled ExceptionEvent in chronological mode with output offset bounds.
     *
     * @param ee The ExceptionEvent.
     * @param compilationResult The compilation result.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputObservation Input observation.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @param startOutOffset Starting standard output offset.
     * @param startErrOffset Starting standard error offset.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processChronologicalExceptionEvent(ExceptionEvent ee, CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses, StreamDrainer vmOut, StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputObservation inputObservation, List<ExecutionSnapshot> chronologicalSnapshots,
            int startOutOffset, int startErrOffset)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        recordException(ee);
        Location loc = ee.location();
        if ((TraceSession.current() != null && TraceSession.current().threadCapture() != null)
                || (loc != null && compilationResult.compiledClassNames().contains(loc.declaringType().name()))) {
            ExecutionSnapshot snapshot = SnapshotCapture.capture(ee.thread(), loadedClasses, sourceAnalysis,
                    inputObservation, new JobOutput(vmOut, vmErr, startOutOffset, startErrOffset), null);
            if (snapshot.threads() != null || chronologicalSnapshots.isEmpty()
                    || !isSameTopFrame(chronologicalSnapshots.getLast(), snapshot)) {
                chronologicalSnapshots.add(snapshot);
            } // if
        } // if
    } // processChronologicalExceptionEvent

    /**
     * Return a mapping of source file relative path to valid breakpoint line numbers.
     *
     * @param compilationResult The compilation result to inspect.
     * @return A map of source file paths to sets of valid line numbers.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws AbsentInformationException If debug info is missing.
     */
    public static Map<String, Set<Integer>> getValidBreakpointLinesByFile(CompilationResult compilationResult)
            throws IOException, IllegalConnectorArgumentsException, VMStartException, InterruptedException,
            AbsentInformationException {

        return BreakpointReader.read(compilationResult);
    } // getValidBreakpointLinesByFile

    /**
     * Return the set of source lines for the compiled classes that can have breakpoints set.
     *
     * @param compilationResult A CompilationResult holding the classes.
     * @return A set of valid breakpoint line numbers.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If JDI connector arguments are invalid.
     * @throws VMStartException If target VM failed to start.
     * @throws InterruptedException If thread is interrupted.
     * @throws AbsentInformationException If debug info is missing.
     */
    public static HashSet<Integer> getValidBreakpointLines(CompilationResult compilationResult) throws IOException,
            IllegalConnectorArgumentsException, VMStartException, InterruptedException, AbsentInformationException {
        Map<String, Set<Integer>> byFile = getValidBreakpointLinesByFile(compilationResult);
        HashSet<Integer> flattened = new HashSet<>();
        for (Set<Integer> lines : byFile.values()) {
            flattened.addAll(lines);
        } // for
        return flattened;
    } // getValidBreakpointLines

    /**
     * Start a JDI VM prepopulated with ClassPrepareRequests.
     *
     * @param compilationResult The CompilationResult.
     * @return The VirtualMachine for the launched VM.
     * @throws IOException On I/O error.
     * @throws IllegalConnectorArgumentsException If connector arguments are invalid.
     * @throws VMStartException If VM failed to start.
     */
    private static VirtualMachine startVmWithCprs(CompilationResult compilationResult)
            throws IOException, IllegalConnectorArgumentsException, VMStartException {

        VirtualMachine vm = GuestLauncher.launch(compilationResult.mainClass(), compilationResult.classPath(),
                compilationResult.dependencies(), compilationResult.previewEnabled());
        if (TraceSession.current() != null) {
            TraceSession.current().attach(vm);
        } // if

        if (TraceSession.current() != null && TraceSession.current().threadCapture() != null) {
            TraceSession.current().threadCapture().install(vm, compilationResult.compiledClassNames());
        } // if
        for (String className : compilationResult.compiledClassNames()) {
            ClassPrepareRequest classPrepareRequest = vm.eventRequestManager().createClassPrepareRequest();
            classPrepareRequest.addClassFilter(className);
            classPrepareRequest.enable();
        } // for

        return vm;
    } // startVmWithCprs

    /**
     * Writes standard input bytes to the guest process and immediately closes the stream.
     *
     * @param vm The debuggee VirtualMachine.
     * @param stdin The standard input string to supply.
     */
    private static void writeGuestStdin(VirtualMachine vm, String stdin) {
        if (vm == null || vm.process() == null) {
            return;
        } // if
        try (java.io.OutputStream out = vm.process().getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                out.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } // if
        } catch (IOException ignored) {
            // Process might have terminated early or closed its stdin stream
        } // try
    } // writeGuestStdin

    /**
     * Records an uncaught guest exception for the job result.
     *
     * @param event Exception event.
     */
    private static void recordException(ExceptionEvent event) {
        if (TraceSession.current() != null) {
            String description = event.exception().referenceType().name();
            if (TraceSession.current().threadCapture() != null) {
                description += " in thread " + event.thread().name() + " (" + event.thread().uniqueID() + ")";
            } // if
            TraceSession.current().guestException(description);
        } // if
    } // recordException

    /**
     * Waits for debugger events while observing session cancellation.
     *
     * @param vm Guest debugger.
     * @return Events, possibly empty after a poll timeout.
     * @throws InterruptedException On thread cancellation.
     */
    static Iterable<Event> nextEvents(VirtualMachine vm) throws InterruptedException {
        TraceSession session = TraceSession.current();
        if (session != null) {
            session.check();
        } // if
        EventSet events = vm.eventQueue().remove(100);
        return events == null ? List.of() : events;
    } // nextEvents

    /**
     * Stores selected breakpoint state, avoiding a second unbounded history in bounded jobs.
     *
     * @param snapshots Legacy breakpoint mapping.
     * @param line Breakpoint line.
     * @param snapshot Completed state.
     */
    static void storeSnapshot(Map<Integer, List<ExecutionSnapshot>> snapshots, int line, ExecutionSnapshot snapshot) {
        TraceSession session = TraceSession.current();
        SnapshotRetention.storeBreakpoint(snapshots, line, snapshot, session == null || session.accumulates());
    } // storeSnapshot

} // DebugTraceHelper
