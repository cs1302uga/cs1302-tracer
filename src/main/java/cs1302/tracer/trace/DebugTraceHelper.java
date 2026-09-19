package cs1302.tracer.trace;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedLambdaConstraintType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
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
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import cs1302.tracer.execution.TraceSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A collection of methods that are used to generate a debug trace.
 *
 * <p>Normative References:
 * <ul>
 *   <li>The Java Debug Interface (JDI) Specification (Java SE 21 Edition, {@code com.sun.jdi}).
 *   <li>Java Platform Debugger Architecture (JPDA) Connection and Invocation Architecture.
 *   <li>Online Python Tutor (OPT) Trace Event Format v3 Specification (Philip Guo, 2013).
 * </ul>
 */
public class DebugTraceHelper {

    /**
     * Private constructor to prevent direct instantiation of utility class.
     */
    private DebugTraceHelper() {} // DebugTraceHelper

    /** A simple JavaParser object so we don't have to make a new one every time. */
    private static final JavaParser SIMPLE_JAVA_PARSER =
            new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT));

    /**
     * An assignment or declaration of a lambda to a variable with line information.
     *
     * @param variableName Variable name.
     * @param lineNumber Line number.
     * @param lambdaImplementation Lambda method implementation text.
     */
    record LambdaAssignment(
            String variableName, int lineNumber, String lambdaImplementation) {} // LambdaAssignment

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
    public static Map<Integer, List<ExecutionSnapshot>> trace(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            CompilationUnit parsedSource)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
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
    public static Map<Integer, List<ExecutionSnapshot>> trace(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            CompilationUnit parsedSource,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
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
    public static Map<Integer, List<ExecutionSnapshot>> trace(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
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
    public static Map<Integer, List<ExecutionSnapshot>> trace(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

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
    public static Map<Integer, List<ExecutionSnapshot>> traceWithSpecs(
            CompilationResult compilationResult,
            Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        Collection<BreakpointSpec> safeSpecs = specs != null ? specs : Collections.emptyList();
        boolean snapMainEnd = safeSpecs.isEmpty()
                || safeSpecs.stream().anyMatch(s -> s.lineNumber() == -1);
        Map<Integer, List<ExecutionSnapshot>> snapshots = new HashMap<>();

        VirtualMachine vm = startVmWithCprs(compilationResult);
        writeGuestStdin(vm, stdin);
        InputTracker inputTracker = new InputTracker(stdin);
        registerReaderMethodExitRequests(vm);
        try (StreamDrainer vmErrDrainer =
                        new StreamDrainer(vm.process().getErrorStream());
                StreamDrainer vmOutDrainer =
                        new StreamDrainer(vm.process().getInputStream())) {

            if (snapMainEnd) {
                MethodExitRequest methodExitRequest =
                        vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            ExceptionRequest exceptionRequest =
                    vm.eventRequestManager().createExceptionRequest(null, false, true);
            exceptionRequest.enable();

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            SourceAnalysis sourceAnalysis = SourceAnalysis.from(parsedSources);
            processBreakpointsEventLoop(
                    vm,
                    compilationResult,
                    safeSpecs,
                    sourceAnalysis,
                    snapshots,
                    loadedClasses,
                    vmOutDrainer,
                    vmErrDrainer,
                    snapMainEnd,
                    inputTracker);

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
     * @param compilationResult Compiled program.
     * @param breakPoints Selected line numbers.
     * @param parsedSources Parsed sources.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatest(
            CompilationResult compilationResult, Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources) throws Exception {
        return traceLatest(compilationResult, breakPoints, parsedSources, "");
    } // traceLatest

    /**
     * Captures only the latest state per selected line with stdin.
     * @param compilationResult Compiled program.
     * @param breakPoints Selected line numbers.
     * @param parsedSources Parsed sources.
     * @param stdin Standard input string.
     * @return Latest snapshot mapping in the existing breakpoint shape.
     * @throws Exception On tracing or cleanup failure.
     */
    public static Map<Integer, List<ExecutionSnapshot>> traceLatest(
            CompilationResult compilationResult, Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources, String stdin) throws Exception {
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
    public static Map<Integer, List<ExecutionSnapshot>> traceLatestWithSpecs(
            CompilationResult compilationResult, Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources) throws Exception {
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
    public static Map<Integer, List<ExecutionSnapshot>> traceLatestWithSpecs(
            CompilationResult compilationResult, Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources, String stdin) throws Exception {
        Map<Integer, List<ExecutionSnapshot>> raw;
        if (TraceSession.current() != null) {
            raw = traceWithSpecs(compilationResult, specs, parsedSources, stdin);
        } else {
            try (TraceSession session = new TraceSession(
                    cs1302.tracer.execution.TraceLimits.unlimited(),
                    cs1302.tracer.execution.InspectionPolicy.TRUSTED, false,
                    TraceSession.shouldEvalEnumHash())) {
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
    static Map<Integer, List<ExecutionSnapshot>> keepLatestOnly(
            Map<Integer, List<ExecutionSnapshot>> snapshots) {
        Map<Integer, List<ExecutionSnapshot>> latest = new TreeMap<>();
        for (Map.Entry<Integer, List<ExecutionSnapshot>> entry : snapshots.entrySet()) {
            List<ExecutionSnapshot> list = entry.getValue();
            Map<Optional<String>, ExecutionSnapshot> perSource = new LinkedHashMap<>();
            for (ExecutionSnapshot snap : list) {
                perSource.put(snap.sourcePath(), snap);
            } // for
            latest.put(entry.getKey(), new ArrayList<>(perSource.values()));
        } // for
        return latest;
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
     * @param inputTracker Input tracker.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    private static void processBreakpointsEventLoop(
            VirtualMachine vm,
            CompilationResult compilationResult,
            Collection<BreakpointSpec> breakPoints,
            SourceAnalysis sourceAnalysis,
            Map<Integer, List<ExecutionSnapshot>> snapshots,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            boolean snapMainEnd,
            InputTracker inputTracker)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        ObjectReference systemIn = getSystemIn(vm);
        boolean endEventLoop = false;
        while (!endEventLoop) {
            for (Event event : nextEvents(vm)) {
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    if (compilationResult.compiledClassNames().contains(
                            cpe.referenceType().name())) {
                        registerBreakpoints(vm, cpe.referenceType(), breakPoints);
                        loadedClasses.add(cpe.referenceType());
                    } // if
                } // case
                case BreakpointEvent bpe -> {
                    Location loc = bpe.location();
                    if (compilationResult.compiledClassNames().contains(
                            loc.declaringType().name())) {
                        Integer line = loc.lineNumber();
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                bpe.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                                inputTracker);
                        storeSnapshot(snapshots, line, snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && (snapMainEnd || snapshots.isEmpty())) {
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                mee.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                                inputTracker);
                        snapshots.put(-1, new ArrayList<>(List.of(snapshot)));
                    } else {
                        handleReaderMethodExit(mee, inputTracker, systemIn);
                    } // if
                } // case
                case ExceptionEvent ee -> processBreakpointExceptionEvent(
                        ee, compilationResult, loadedClasses, vmOut, vmErr,
                        sourceAnalysis, inputTracker, snapshots);
                case VMDeathEvent vde -> {
                    endEventLoop = true;
                } // case
                case VMDisconnectEvent vde -> {
                    endEventLoop = true;
                } // case
                default -> {
                    // do nothing
                } // default
                } // switch

                vm.resume();
            } // for
        } // while
    } // processBreakpointsEventLoop

    /**
     * Runs the event loop for the breakpoint trace with raw parsed sources.
     *
     * @param vm The JDI VirtualMachine.
     * @param compilationResult The compilation result.
     * @param breakPoints Collection of breakpoint lines.
     * @param parsedSources List of compilation units.
     * @param snapshots Target map to accumulate snapshots.
     * @param loadedClasses Set of loaded reference types.
     * @param vmOut Drainer for stdout.
     * @param vmErr Drainer for stderr.
     * @param snapMainEnd True if main exit should be captured.
     * @param inputTracker Input tracker.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    private static void processBreakpointsEventLoop(
            VirtualMachine vm,
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources,
            Map<Integer, List<ExecutionSnapshot>> snapshots,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            boolean snapMainEnd,
            InputTracker inputTracker)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        processBreakpointsEventLoop(
                vm,
                compilationResult,
                toSpecs(breakPoints),
                SourceAnalysis.from(parsedSources),
                snapshots,
                loadedClasses,
                vmOut,
                vmErr,
                snapMainEnd,
                inputTracker);
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
     * @param inputTracker Input tracker.
     * @param snapshots Target snapshot map.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processBreakpointExceptionEvent(
            ExceptionEvent ee,
            CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            Map<Integer, List<ExecutionSnapshot>> snapshots)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        processBreakpointExceptionEvent(ee, compilationResult, loadedClasses, vmOut, vmErr,
                sourceAnalysis, inputTracker, snapshots, 0, 0);
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
     * @param inputTracker Input tracker.
     * @param snapshots Target snapshot map.
     * @param startOutOffset Starting standard output offset.
     * @param startErrOffset Starting standard error offset.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processBreakpointExceptionEvent(
            ExceptionEvent ee,
            CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            Map<Integer, List<ExecutionSnapshot>> snapshots,
            int startOutOffset,
            int startErrOffset)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        recordException(ee);
        Location loc = ee.location();
        if (loc != null && compilationResult.compiledClassNames().contains(
                loc.declaringType().name())) {
            Integer line = loc.lineNumber();
            ExecutionSnapshot snapshot = snapshotTheWorld(
                    ee.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                    inputTracker, startOutOffset, startErrOffset);
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
     * @param breakPoints The collection of line numbers.
     * @return List of created and enabled breakpoint requests.
     * @throws AbsentInformationException If line info is absent.
     */
    static List<BreakpointRequest> registerBreakpoints(
            VirtualMachine vm, ReferenceType refType, Collection<BreakpointSpec> breakPoints)
            throws AbsentInformationException {
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
                        BreakpointRequest req =
                                vm.eventRequestManager().createBreakpointRequest(loc);
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
     * Checks if a JDI method matches the main method signature.
     *
     * @param method The JDI method.
     * @return True if method is main.
     */
    static boolean isMainMethodExit(Method method) {
        String mainJniSignature = "([Ljava/lang/String;)V";
        String noArgJniSignature = "()V";
        return !method.isPrivate()
                && method.name().equals("main")
                && (method.signature().equals(mainJniSignature)
                        || method.signature().equals(noArgJniSignature));
    } // isMainMethodExit

    /**
     * Flushes standard output and standard error in the target VM via JDI method invocation.
     *
     * @param vm The JDI VirtualMachine.
     * @param thread The suspended thread on which to invoke flush.
     */
    private static void flushTargetStreams(VirtualMachine vm, ThreadReference thread) {
        if (vm == null || thread == null || !thread.isSuspended()) {
            return;
        } // if
        try {
            List<ReferenceType> systemClasses = vm.classesByName("java.lang.System");
            if (systemClasses.isEmpty()) {
                return;
            } // if
            ReferenceType systemClass = systemClasses.get(0);
            flushPrintStreamField(systemClass, "out", thread);
            flushPrintStreamField(systemClass, "err", thread);
        } catch (Exception e) {
            // Graceful degradation: do not interrupt tracing if flush invocation fails
        } // try
    } // flushTargetStreams

    /**
     * Flushes a specific PrintStream static field on java.lang.System.
     *
     * @param systemClass The java.lang.System reference type.
     * @param fieldName The name of the field ("out" or "err").
     * @param thread The suspended thread on which to invoke flush.
     */
    private static void flushPrintStreamField(
            ReferenceType systemClass, String fieldName, ThreadReference thread) {
        try {
            Field field = systemClass.fieldByName(fieldName);
            if (field == null) {
                return;
            } // if
            Value val = systemClass.getValue(field);
            if (val instanceof ObjectReference printStreamRef) {
                ReferenceType psType = printStreamRef.referenceType();
                List<Method> flushMethods = psType.methodsByName("flush", "()V");
                if (!flushMethods.isEmpty()) {
                    printStreamRef.invokeMethod(
                            thread,
                            flushMethods.get(0),
                            Collections.emptyList(),
                            ObjectReference.INVOKE_SINGLE_THREADED);
                } // if
            } // if
        } catch (Exception e) {
            // Graceful degradation: do not interrupt tracing if flush invocation fails
        } // try
    } // flushPrintStreamField

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
     * @param vm Guest debugger connection.
     */
    private static void awaitGuestExit(VirtualMachine vm) {
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
        if (prev == current) {
            return true;
        } // if
        if (prev == null || current == null) {
            return false;
        } // if
        return Objects.equals(prev.sourcePath(), current.sourcePath())
                && Objects.equals(prev.stack(), current.stack())
                && Objects.equals(prev.statics(), current.statics())
                && Objects.equals(prev.heap(), current.heap())
                && prev.stdoutSlice().contentEquals(current.stdoutSlice())
                && prev.stderrSlice().contentEquals(current.stderrSlice())
                && Objects.equals(prev.stdinConsumed(), current.stdinConsumed())
                && prev.stdinOffset() == current.stdinOffset();
    } // isRedundantSnapshot

    /**
     * Synchronizes any trailing standard output or standard error bytes to the final snapshot.
     *
     * @param chronologicalSnapshots List of snapshots.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     */
    private static void syncTrailingStreamOutput(
            List<ExecutionSnapshot> chronologicalSnapshots,
            StreamDrainer vmOut,
            StreamDrainer vmErr) {
        if (!chronologicalSnapshots.isEmpty()) {
            OutputSlice finalErr = sanitizeDebuggeeStderrSlice(vmErr.snapshotOutput());
            OutputSlice finalOut = vmOut.snapshotOutput();
            ExecutionSnapshot last = chronologicalSnapshots.getLast();
            if (finalErr.length() > last.stderrLength()
                    || finalOut.length() > last.stdoutLength()) {
                chronologicalSnapshots.set(
                        chronologicalSnapshots.size() - 1,
                        new ExecutionSnapshot(
                                last.stack(),
                                last.statics(),
                                last.heap(),
                                finalOut,
                                finalErr,
                                last.sourcePath(),
                                last.stdinConsumed(),
                                last.stdinOffset()));
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
    private static void syncTrailingStreamOutput(
            Map<Integer, List<ExecutionSnapshot>> snapshots,
            StreamDrainer vmOut,
            StreamDrainer vmErr) {
        OutputSlice finalErr = sanitizeDebuggeeStderrSlice(vmErr.snapshotOutput());
        OutputSlice finalOut = vmOut.snapshotOutput();
        for (Map.Entry<Integer, List<ExecutionSnapshot>> entry : snapshots.entrySet()) {
            List<ExecutionSnapshot> list = entry.getValue();
            if (list != null && !list.isEmpty()) {
                ExecutionSnapshot last = list.getLast();
                if (finalErr.length() > last.stderrLength()
                        || finalOut.length() > last.stdoutLength()) {
                    List<ExecutionSnapshot> mutableList = (list instanceof ArrayList)
                            ? list
                            : new ArrayList<>(list);
                    mutableList.set(
                            mutableList.size() - 1,
                            new ExecutionSnapshot(
                                    last.stack(),
                                    last.statics(),
                                    last.heap(),
                                    finalOut,
                                    finalErr,
                                    last.sourcePath(),
                                    last.stdinConsumed(),
                                    last.stdinOffset()));
                    if (mutableList != list) {
                        entry.setValue(mutableList);
                    } // if
                } // if
            } // if
        } // for
    } // syncTrailingStreamOutput

    /**
     * Sanitizes captured standard error slice from the debuggee VM.
     *
     * @param rawStderr Raw stderr OutputSlice.
     * @return Sanitized stderr OutputSlice.
     */
    private static final byte[] JAVA_TOOL_OPTIONS_PREFIX =
            "Picked up JAVA_TOOL_OPTIONS:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAVA_OPTIONS_PREFIX =
            "Picked up _JAVA_OPTIONS:".getBytes(StandardCharsets.UTF_8);

    /**
     * Sanitizes captured standard error bytes in an OutputSlice by removing JVM diagnostic banner
     * prefixes without allocating unnecessary intermediate byte arrays.
     *
     * @param rawStderr Raw stderr OutputSlice.
     * @return Sanitized stderr OutputSlice.
     */
    static OutputSlice sanitizeDebuggeeStderrSlice(OutputSlice rawStderr) {
        if (rawStderr == null || rawStderr.isEmpty()) {
            return rawStderr != null ? rawStderr : OutputSlice.empty();
        } // if
        if (!rawStderr.startsWith(JAVA_TOOL_OPTIONS_PREFIX)
                && !rawStderr.startsWith(JAVA_OPTIONS_PREFIX)) {
            return rawStderr;
        } // if
        OutputSlice current = rawStderr;
        while (current.startsWith(JAVA_TOOL_OPTIONS_PREFIX)
                || current.startsWith(JAVA_OPTIONS_PREFIX)) {
            int newlineIndex = current.indexOf((byte) '\n', 0);
            if (newlineIndex == -1) {
                return OutputSlice.empty();
            } // if
            current = current.subSlice(newlineIndex + 1);
        } // while
        return current;
    } // sanitizeDebuggeeStderrSlice

    /**
     * Sanitizes captured standard error bytes from the debuggee VM by removing JVM diagnostic
     * banner lines emitted before user program execution (such as
     * {@code Picked up JAVA_TOOL_OPTIONS}).
     *
     * @param rawStderr Raw stderr bytes from the debuggee process.
     * @return Sanitized stderr bytes.
     */
    static byte[] sanitizeDebuggeeStderr(byte[] rawStderr) {
        if (rawStderr == null || rawStderr.length == 0) {
            return rawStderr;
        } // if
        String str = new String(rawStderr, StandardCharsets.UTF_8);
        if (!str.startsWith("Picked up JAVA_TOOL_OPTIONS:")
                && !str.startsWith("Picked up _JAVA_OPTIONS:")) {
            return rawStderr;
        } // if
        while (str.startsWith("Picked up JAVA_TOOL_OPTIONS:")
                || str.startsWith("Picked up _JAVA_OPTIONS:")) {
            int newlineIndex = str.indexOf('\n');
            if (newlineIndex == -1) {
                str = "";
                break;
            } else {
                str = str.substring(newlineIndex + 1);
            } // if
        } // while
        return str.getBytes(StandardCharsets.UTF_8);
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
    public static ExecutionSnapshot trace(
            CompilationResult compilationResult, CompilationUnit parsedSource)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return trace(compilationResult, parsedSource, "");
    } // trace

    /**
     * Take a snapshot of a program's execution state just before the main method returns
     * with stdin.
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
    public static ExecutionSnapshot trace(
            CompilationResult compilationResult, CompilationUnit parsedSource, String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
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
    public static ExecutionSnapshot trace(
            CompilationResult compilationResult, List<CompilationUnit> parsedSources)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return trace(compilationResult, parsedSources, "");
    } // trace

    /**
     * Take a snapshot of a program's execution state just before the main method returns
     * with stdin.
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
    public static ExecutionSnapshot trace(
            CompilationResult compilationResult, List<CompilationUnit> parsedSources, String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
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
    public static List<ExecutionSnapshot> traceChronological(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            CompilationUnit parsedSource,
            boolean includeMainExit)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return traceChronological(
                compilationResult, breakPoints, List.of(parsedSource), includeMainExit, "");
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
    public static List<ExecutionSnapshot> traceChronological(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            CompilationUnit parsedSource,
            boolean includeMainExit,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return traceChronological(
                compilationResult, breakPoints, List.of(parsedSource), includeMainExit, stdin);
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
    public static List<ExecutionSnapshot> traceChronological(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources,
            boolean includeMainExit)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return traceChronological(
                compilationResult, breakPoints, parsedSources, includeMainExit, "");
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
    public static List<ExecutionSnapshot> traceChronological(
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources,
            boolean includeMainExit,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        return traceChronologicalWithSpecs(
                compilationResult, toSpecs(breakPoints), parsedSources, includeMainExit, stdin);
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
    public static List<ExecutionSnapshot> traceChronologicalWithSpecs(
            CompilationResult compilationResult,
            Collection<BreakpointSpec> specs,
            List<CompilationUnit> parsedSources,
            boolean includeMainExit,
            String stdin)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        VirtualMachine vm = startVmWithCprs(compilationResult);
        writeGuestStdin(vm, stdin);
        InputTracker inputTracker = new InputTracker(stdin);
        registerReaderMethodExitRequests(vm);
        List<ExecutionSnapshot> chronologicalSnapshots = new ArrayList<>();

        try (StreamDrainer vmErrDrainer =
                        new StreamDrainer(vm.process().getErrorStream());
                StreamDrainer vmOutDrainer =
                        new StreamDrainer(vm.process().getInputStream())) {

            if (includeMainExit) {
                MethodExitRequest methodExitRequest =
                        vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            ExceptionRequest exceptionRequest =
                    vm.eventRequestManager().createExceptionRequest(null, false, true);
            exceptionRequest.enable();

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            SourceAnalysis sourceAnalysis = SourceAnalysis.from(parsedSources);
            Collection<BreakpointSpec> safeSpecs = specs != null ? specs : Collections.emptyList();
            processChronologicalEventLoop(
                    vm,
                    compilationResult,
                    safeSpecs,
                    sourceAnalysis,
                    chronologicalSnapshots,
                    loadedClasses,
                    vmOutDrainer,
                    vmErrDrainer,
                    includeMainExit,
                    inputTracker);

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
     * @param inputTracker Input tracker.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException If class not loaded.
     */
    private static void processChronologicalEventLoop(
            VirtualMachine vm,
            CompilationResult compilationResult,
            Collection<BreakpointSpec> breakPoints,
            SourceAnalysis sourceAnalysis,
            List<ExecutionSnapshot> chronologicalSnapshots,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            boolean includeMainExit,
            InputTracker inputTracker)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        ObjectReference systemIn = getSystemIn(vm);
        boolean endEventLoop = false;
        while (!endEventLoop) {
            for (Event event : nextEvents(vm)) {
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    if (compilationResult.compiledClassNames().contains(
                            cpe.referenceType().name())) {
                        registerBreakpoints(vm, cpe.referenceType(), breakPoints);
                        loadedClasses.add(cpe.referenceType());
                    } // if
                } // case
                case BreakpointEvent bpe -> {
                    Location breakLocation = bpe.location();
                    if (compilationResult.compiledClassNames().contains(
                            breakLocation.declaringType().name())) {
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                bpe.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                                inputTracker);
                        chronologicalSnapshots.add(snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && includeMainExit) {
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                mee.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                                inputTracker);
                        if (chronologicalSnapshots.isEmpty()
                                || !isRedundantSnapshot(
                                        chronologicalSnapshots.getLast(), snapshot)) {
                            chronologicalSnapshots.add(snapshot);
                        } // if
                    } else {
                        handleReaderMethodExit(mee, inputTracker, systemIn);
                    } // if
                } // case
                case ExceptionEvent ee -> processChronologicalExceptionEvent(
                        ee, compilationResult, loadedClasses, vmOut, vmErr,
                        sourceAnalysis, inputTracker, chronologicalSnapshots);
                case VMDeathEvent vde -> {
                    endEventLoop = true;
                } // case
                case VMDisconnectEvent vde -> {
                    endEventLoop = true;
                } // case
                default -> {
                    // do nothing
                } // default
                } // switch
                if (endEventLoop) {
                    break;
                } // if
                vm.resume();
            } // for
        } // while
    } // processChronologicalEventLoop

    /**
     * Processes the chronological event loop with raw parsed sources.
     *
     * @param vm Target VM.
     * @param compilationResult Compilation result.
     * @param breakPoints Breakpoint lines.
     * @param parsedSources Compilation units.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Drainer for stdout.
     * @param vmErr Drainer for stderr.
     * @param includeMainExit True to include main exit.
     * @param inputTracker Input tracker.
     * @throws InterruptedException On interrupt.
     * @throws IncompatibleThreadStateException On thread error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException If class not loaded.
     */
    private static void processChronologicalEventLoop(
            VirtualMachine vm,
            CompilationResult compilationResult,
            Collection<Integer> breakPoints,
            List<CompilationUnit> parsedSources,
            List<ExecutionSnapshot> chronologicalSnapshots,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            boolean includeMainExit,
            InputTracker inputTracker)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        processChronologicalEventLoop(
                vm,
                compilationResult,
                toSpecs(breakPoints),
                SourceAnalysis.from(parsedSources),
                chronologicalSnapshots,
                loadedClasses,
                vmOut,
                vmErr,
                includeMainExit,
                inputTracker);
    } // processChronologicalEventLoop

    /**
     * Handles an ExceptionEvent during the chronological event loop.
     *
     * @param ee The exception event.
     * @param compilationResult The compilation result.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputTracker Input tracker.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processChronologicalExceptionEvent(
            ExceptionEvent ee,
            CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            List<ExecutionSnapshot> chronologicalSnapshots)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        processChronologicalExceptionEvent(ee, compilationResult, loadedClasses, vmOut, vmErr,
                sourceAnalysis, inputTracker, chronologicalSnapshots, 0, 0);
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
     * @param inputTracker Input tracker.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @param startOutOffset Starting standard output offset.
     * @param startErrOffset Starting standard error offset.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static void processChronologicalExceptionEvent(
            ExceptionEvent ee,
            CompilationResult compilationResult,
            HashSet<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            List<ExecutionSnapshot> chronologicalSnapshots,
            int startOutOffset,
            int startErrOffset)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        recordException(ee);
        Location loc = ee.location();
        if (loc != null && compilationResult.compiledClassNames().contains(
                loc.declaringType().name())) {
            ExecutionSnapshot snapshot = snapshotTheWorld(
                    ee.thread(), loadedClasses, vmOut, vmErr, sourceAnalysis,
                    inputTracker, startOutOffset, startErrOffset);
            if (chronologicalSnapshots.isEmpty()
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
    public static Map<String, Set<Integer>> getValidBreakpointLinesByFile(
            CompilationResult compilationResult)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
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
    public static HashSet<Integer> getValidBreakpointLines(CompilationResult compilationResult)
            throws IOException,
            IllegalConnectorArgumentsException,
            VMStartException,
            InterruptedException,
            AbsentInformationException {
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

        LaunchingConnector launchingConnector =
                Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> env = launchingConnector.defaultArguments();

        env.get("main").setValue(compilationResult.mainClass());
        String options =
                "-Djava.awt.headless=true -classpath \"" + compilationResult.classPath() + "\"";
        if (compilationResult.previewEnabled()) {
            options = "--enable-preview " + options;
        } // if
        env.get("options").setValue(options);

        VirtualMachine vm = launchingConnector.launch(env);
        if (TraceSession.current() != null) {
            TraceSession.current().attach(vm);
        } // if

        for (String className : compilationResult.compiledClassNames()) {
            ClassPrepareRequest classPrepareRequest =
                    vm.eventRequestManager().createClassPrepareRequest();
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
     * Finds the System.in object reference in the target VM.
     *
     * @param vm The JDI VirtualMachine.
     * @return The ObjectReference for System.in, or null if not found.
     */
    static ObjectReference getSystemIn(VirtualMachine vm) {
        try {
            List<ReferenceType> systemClasses = vm.classesByName("java.lang.System");
            if (systemClasses != null && !systemClasses.isEmpty()) {
                ReferenceType systemClass = systemClasses.getFirst();
                Field inField = systemClass.fieldByName("in");
                if (inField != null) {
                    Value val = systemClass.getValue(inField);
                    if (val instanceof ObjectReference objRef) {
                        return objRef;
                    } // if
                } // if
            } // if
        } catch (Exception ignored) {
            // Target VM might not have loaded System yet
        } // try
        return null;
    } // getSystemIn

    /**
     * Registers MethodExitRequests for standard reader classes to track input consumption.
     *
     * @param vm The debuggee VirtualMachine.
     */
    static void registerReaderMethodExitRequests(VirtualMachine vm) {
        String[] readerClasses = {
            "java.util.Scanner",
            "java.io.BufferedReader",
            "java.lang.IO",
            "java.io.InputStream",
            "java.io.BufferedInputStream"
        };
        for (String className : readerClasses) {
            MethodExitRequest req = vm.eventRequestManager().createMethodExitRequest();
            req.addClassFilter(className);
            req.enable();
        } // for
    } // registerReaderMethodExitRequests

    /**
     * Handles MethodExitEvents on reader classes and advances the input tracker.
     *
     * @param mee The method exit event.
     * @param inputTracker The active input tracker.
     * @param systemIn Cached reference to System.in in target VM.
     */
    static void handleReaderMethodExit(
            MethodExitEvent mee,
            InputTracker inputTracker,
            ObjectReference systemIn) {
        if (inputTracker == null || inputTracker.isExhausted()) {
            return;
        } // if
        try {
            ReaderTracking.record(mee, inputTracker,
                    systemIn != null ? systemIn : getSystemIn(mee.virtualMachine()));
        } catch (Exception ignored) {
            // Ignore inspection errors on reader exit
        } // try
    } // handleReaderMethodExit

    /**
     * Convert a lambda expression in the AST into an implementation.
     *
     * @param lambda The lambda expression to convert.
     * @return A string containing a valid method implementation.
     */
    static Optional<String> tryImplementLambdaSam(LambdaExpr lambda) {
        Optional<MethodUsage> maybeSam =
                FunctionalInterfaceLogic.getFunctionalMethod(lambda.calculateResolvedType());
        if (maybeSam.isEmpty()) {
            return Optional.empty();
        } // if

        MethodUsage sam = maybeSam.get();
        StringBuilder sb = new StringBuilder();

        String resolvedReturnType =
                lambda.calculateResolvedType().asReferenceType().getTypeParametersMap().stream()
                        .filter(p -> sam.returnType().isTypeVariable())
                        .filter(p -> p.a.getName().equals(
                                sam.returnType().asTypeVariable().describe()))
                        .map(p -> p.b.describe())
                        .findFirst()
                        .orElse(sam.returnType().describe());

        sb.append(resolvedReturnType).append(" ").append(sam.getName());

        sb.append(IntStream.range(0, sam.getDeclaration().getNumberOfParams())
                .mapToObj(i -> formatLambdaParam(lambda, i))
                .collect(Collectors.joining(", ", "(", ")")));

        if (lambda.getBody() instanceof ExpressionStmt e) {
            sb.append("{\n");
            if (!resolvedReturnType.equals("void")) {
                sb.append("return ");
            } // if
            sb.append(e).append("}");
        } else {
            sb.append(lambda.getBody());
        } // if

        return SIMPLE_JAVA_PARSER.parseMethodDeclaration(sb.toString())
                .getResult().map(Object::toString);
    } // tryImplementLambdaSam

    /**
     * Formats a lambda parameter with its resolved type and name.
     *
     * @param lambda Lambda expression.
     * @param i Parameter index.
     * @return Formatted parameter string.
     */
    private static String formatLambdaParam(LambdaExpr lambda, int i) {
        String typeDesc = switch (lambda.getParameter(i).resolve().getType()) {
            case ResolvedLambdaConstraintType c -> c.getBound().describe();
            case ResolvedType d -> d.describe();
        }; // switch
        return String.format("%s %s", typeDesc, lambda.getParameter(i).getName());
    } // formatLambdaParam

    /**
     * Take a snapshot of a thread's memory state at this instant of execution.
     *
     * @param mainThread A suspended thread.
     * @param loadedClasses The loaded classes.
     * @param vmOut Drainer for stdout.
     * @param vmErr Drainer for stderr.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputTracker Input tracker.
     * @return An execution snapshot.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static ExecutionSnapshot snapshotTheWorld(
            ThreadReference mainThread,
            Iterable<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        return snapshotTheWorld(mainThread, loadedClasses, vmOut, vmErr, sourceAnalysis,
                inputTracker, 0, 0);
    } // snapshotTheWorld

    /**
     * Takes an execution snapshot with stream output sliced from specified offsets.
     *
     * @param mainThread The main thread reference.
     * @param loadedClasses Currently loaded reference types.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputTracker Input tracker.
     * @param startOutOffset Starting standard output offset.
     * @param startErrOffset Starting standard error offset.
     * @return An execution snapshot.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static ExecutionSnapshot snapshotTheWorld(
            ThreadReference mainThread,
            Iterable<ReferenceType> loadedClasses,
            StreamDrainer vmOut,
            StreamDrainer vmErr,
            SourceAnalysis sourceAnalysis,
            InputTracker inputTracker,
            int startOutOffset,
            int startErrOffset)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        TraceSession session = TraceSession.current();
        prepareSessionAndStreams(session, mainThread, vmOut, vmErr);

        List<ObjectReference> heapReferencesToWalk = new ReferenceQueue();
        Map<Long, TraceValue> heap = new HashMap<>();
        AstTypeResolver astTypeResolver = sourceAnalysis.astTypeResolver();
        Map<Long, String> objectTypeMap = new HashMap<>();

        prepassObjectTypes(mainThread, astTypeResolver, objectTypeMap);

        List<StackSnapshot> stackSnapshots = collectStackSnapshots(
                mainThread,
                astTypeResolver,
                objectTypeMap,
                sourceAnalysis.lambdaMethodAssignments(),
                sourceAnalysis.finalMethodVariables(),
                heapReferencesToWalk,
                heap);

        List<ExecutionSnapshot.Field> statics = collectStatics(
                loadedClasses, sourceAnalysis, heapReferencesToWalk, heap);

        drainHeapReferences(mainThread, astTypeResolver, objectTypeMap, heapReferencesToWalk, heap);

        int outLen = Math.max(0, vmOut.size() - startOutOffset);
        int errLen = Math.max(0, vmErr.size() - startErrOffset);
        if (session != null) {
            session.allocate((long) outLen + errLen);
        } // if
        OutputSlice vmOutSlice = OutputSlice.from(vmOut, startOutOffset, outLen);
        OutputSlice vmErrSlice = sanitizeDebuggeeStderrSlice(
                OutputSlice.from(vmErr, startErrOffset, errLen));

        String currentStepSourcePath = resolveStepSourcePath(mainThread);
        String stdinConsumed = inputTracker == null ? "" : inputTracker.consumed();
        int stdinOffset = inputTracker == null ? 0 : inputTracker.offset();

        ExecutionSnapshot snapshot = new ExecutionSnapshot(
                stackSnapshots,
                statics,
                heap,
                vmOutSlice,
                vmErrSlice,
                Optional.ofNullable(currentStepSourcePath),
                stdinConsumed,
                stdinOffset);
        if (session != null) {
            session.commit(snapshot);
        } // if
        return snapshot;
    } // snapshotTheWorld

    /**
     * Prepares the session and stream drainers prior to heap inspection.
     *
     * @param session The active trace session.
     * @param mainThread The main thread reference.
     * @param vmOut Standard output drainer.
     * @param vmErr Standard error drainer.
     */
    private static void prepareSessionAndStreams(
            TraceSession session,
            ThreadReference mainThread,
            StreamDrainer vmOut,
            StreamDrainer vmErr) {
        if (session != null) {
            session.beginSnapshot();
        } // if
        if (TraceSession.mayInvoke()) {
            flushTargetStreams(mainThread.virtualMachine(), mainThread);
        } // if
        vmOut.sync();
        vmErr.sync();
    } // prepareSessionAndStreams

    /**
     * Drains reachable heap references into the heap map.
     *
     * @param mainThread Main thread reference.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @param heapReferencesToWalk Queue of object references.
     * @param heap Resulting heap map.
     */
    private static void drainHeapReferences(
            ThreadReference mainThread,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap) {
        while (!heapReferencesToWalk.isEmpty()) {
            ObjectReference workingObject = heapReferencesToWalk.removeFirst();
            if (heap.containsKey(workingObject.uniqueID())) {
                continue;
            } // if
            TraceValue convertedObject = TraceValue.fromJdiValue(
                    mainThread,
                    workingObject,
                    Optional.of(heapReferencesToWalk),
                    astTypeResolver,
                    objectTypeMap);
            heap.put(workingObject.uniqueID(), convertedObject);
        } // while
    } // drainHeapReferences

    /**
     * Resolves the source file path for the current executing step.
     *
     * @param mainThread Main thread reference.
     * @return Source file path or null.
     */
    private static String resolveStepSourcePath(ThreadReference mainThread) {
        try {
            if (!mainThread.frames().isEmpty()) {
                try {
                    return mainThread.frame(0).location().sourcePath();
                } catch (AbsentInformationException e) {
                    try {
                        return mainThread.frame(0).location().sourceName();
                    } catch (AbsentInformationException ignored) {
                        return null;
                    } // try
                } // try
            } // if
        } catch (IncompatibleThreadStateException ignored) {
            return null;
        } // try
        return null;
    } // resolveStepSourcePath

    /**
     * Builds lambda assignment and final variable maps from parsed compilation units.
     *
     * @param parsedSources List of compilation units.
     * @param lambdaMap Target map for lambda assignments.
     * @param finalMap Target map for final variable names.
     */
    static void buildLambdaAndFinalMaps(
            List<CompilationUnit> parsedSources,
            Map<String, List<LambdaAssignment>> lambdaMap,
            Map<String, Set<String>> finalMap) {
        for (CompilationUnit cu : parsedSources) {
            if (cu == null) {
                continue;
            } // if
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                String sig = resolveMethodSignature(m);
                lambdaMap.put(sig, extractMethodLambdaAssignments(m));

                Set<String> finals = m.findAll(VariableDeclarationExpr.class).stream()
                        .filter(v -> v.getModifiers().contains(Modifier.finalModifier()))
                        .map(VariableDeclarationExpr::getVariables)
                        .flatMap(Collection::stream)
                        .map(VariableDeclarator::getNameAsString)
                        .collect(Collectors.toSet());
                finalMap.put(sig, finals);
            } // for
        } // for
    } // buildLambdaAndFinalMaps

    /**
     * Extracts lambda assignments from a method declaration.
     *
     * @param m MethodDeclaration AST node.
     * @return List of lambda assignments.
     */
    private static List<LambdaAssignment> extractMethodLambdaAssignments(MethodDeclaration m) {
        List<LambdaAssignment> assignments = new ArrayList<>();

        for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
            if (d.getInitializer().map(Expression::isLambdaExpr).orElse(false)) {
                try {
                    tryImplementLambdaSam(d.getInitializer().get().asLambdaExpr())
                            .ifPresent(impl -> assignments.add(new LambdaAssignment(
                                    d.getNameAsString(),
                                    d.getRange().map(r -> r.begin.line).orElse(0),
                                    impl)));
                } catch (RuntimeException e) {
                    // Ignore unresolved local lambdas
                } // try
            } // if
        } // for

        for (AssignExpr a : m.findAll(AssignExpr.class)) {
            if (a.getValue().isLambdaExpr()) {
                String varName = null;
                if (a.getTarget().isNameExpr()) {
                    varName = a.getTarget().asNameExpr().getNameAsString();
                } else {
                    if (a.getTarget().isFieldAccessExpr()) {
                        varName = a.getTarget().asFieldAccessExpr().getNameAsString();
                    } // if
                } // if
                if (varName != null) {
                    final String finalVarName = varName;
                    try {
                        tryImplementLambdaSam(a.getValue().asLambdaExpr())
                                .ifPresent(impl -> assignments.add(new LambdaAssignment(
                                        finalVarName,
                                        a.getRange().map(r -> r.begin.line).orElse(0),
                                        impl)));
                    } catch (RuntimeException e) {
                        // Ignore unresolved local lambdas
                    } // try
                } // if
            } // if
        } // for

        assignments.sort(Comparator.comparingInt(LambdaAssignment::lineNumber));
        return assignments;
    } // extractMethodLambdaAssignments

    /**
     * Resolves a method qualified signature string.
     *
     * @param m MethodDeclaration AST node.
     * @return Resolved signature.
     */
    private static String resolveMethodSignature(MethodDeclaration m) {
        try {
            return m.resolve()
                    .getQualifiedSignature()
                    .replaceAll("\\.\\.\\.", "[]")
                    .replaceAll("\\s", "");
        } catch (Throwable t) {
            String params = m.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.joining(","));
            return m.getNameAsString() + "(" + params + ")";
        } // try
    } // resolveMethodSignature

    /**
     * Returns true if declaring class corresponds to internal coordinator harness or reflection.
     * Student classes named GuestHarness loaded by a URLClassLoader are preserved.
     *
     * @param declaringType Declaring ReferenceType.
     * @return True if frame should be filtered from student trace.
     */
    static boolean isGuestHarnessOrReflect(ReferenceType declaringType) {
        String declaringClassFqn = declaringType.name();
        if (declaringClassFqn.startsWith("jdk.internal.reflect.")
                || declaringClassFqn.startsWith("java.lang.reflect.")) {
            return true;
        } // if
        if (declaringClassFqn.equals("cs1302.tracer.guest.GuestHarness")
                || declaringClassFqn.startsWith("cs1302.tracer.guest.GuestHarness$")) {
            ClassLoaderReference cl = declaringType.classLoader();
            return cl == null || !cl.referenceType().name().contains("URLClassLoader");
        } // if
        return false;
    } // isGuestHarnessOrReflect

    /**
     * Returns true if declaring class corresponds to the internal guest harness or reflection.
     *
     * @param declaringClassFqn Declaring class fully qualified name.
     * @return True if frame should be filtered from student trace.
     */
    static boolean isGuestHarnessOrReflect(String declaringClassFqn) {
        return declaringClassFqn.equals("cs1302.tracer.guest.GuestHarness")
                || declaringClassFqn.startsWith("cs1302.tracer.guest.GuestHarness$")
                || declaringClassFqn.startsWith("jdk.internal.reflect.")
                || declaringClassFqn.startsWith("java.lang.reflect.");
    } // isGuestHarnessOrReflect

    /**
     * Pre-pass over frames to propagate types from AST allocations into objectTypeMap.
     *
     * @param mainThread Suspended thread.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Target object type map.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException On unloaded class.
     */
    private static void prepassObjectTypes(
            ThreadReference mainThread,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        List<StackFrame> frameList = mainThread.frames();
        for (int i = 0; i < frameList.size(); i++) {
            StackFrame frame = frameList.get(i);
            ReferenceType declaringType = frame.location().method().declaringType();
            if (isGuestHarnessOrReflect(declaringType)) {
                continue;
            } // if
            String declaringClassFqn = declaringType.name();
            String methodName = frame.location().method().name();
            int currentLine = frame.location().lineNumber();

            if ("<init>".equals(methodName) && i + 1 < frameList.size()) {
                StackFrame caller = frameList.get(i + 1);
                String callerClass = caller.location().method().declaringType().name();
                int callerLine = caller.location().lineNumber();
                Optional<String> allocType =
                        astTypeResolver.getAllocationType(callerClass, callerLine);
                if (allocType.isPresent()
                        && frame.thisObject() instanceof ObjectReference frameThis) {
                    String candidate = allocType.get();
                    String rawAlloc = AstTypeResolver.extractRawTypeName(candidate);
                    String runtimeClass = frameThis.referenceType().name();
                    if (AstTypeResolver.rawTypeMatches(runtimeClass, rawAlloc)) {
                        objectTypeMap.putIfAbsent(frameThis.uniqueID(), candidate);
                    } // if
                } // if
            } // if

            Optional<String> allocType =
                    astTypeResolver.getAllocationType(declaringClassFqn, currentLine);

            prepassFrameVariables(
                    frame,
                    declaringClassFqn,
                    methodName,
                    currentLine,
                    allocType,
                    astTypeResolver,
                    objectTypeMap);

            if (frame.thisObject() instanceof ObjectReference frameThis) {
                if (!objectTypeMap.containsKey(frameThis.uniqueID())) {
                    Optional<AstTypeResolver.ClassGenericInfo> info =
                            astTypeResolver.getClassGenericInfo(declaringClassFqn);
                    if (info.isPresent() && info.get().typeParameters().isEmpty()) {
                        objectTypeMap.put(frameThis.uniqueID(), declaringClassFqn);
                    } // if
                } // if
            } // if
        } // for
    } // prepassObjectTypes

    /**
     * Prepass inspection of visible variables in a stack frame.
     *
     * @param frame Stack frame.
     * @param declaringClassFqn Declaring class FQN.
     * @param methodName Method name.
     * @param currentLine Current line number.
     * @param allocType Optional allocation type at line.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Target object type map.
     * @throws AbsentInformationException On absent debug info.
     */
    private static void prepassFrameVariables(
            StackFrame frame,
            String declaringClassFqn,
            String methodName,
            int currentLine,
            Optional<String> allocType,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap)
            throws AbsentInformationException {
        List<LocalVariable> variables;
        try {
            variables = frame.visibleVariables();
        } catch (AbsentInformationException ignored) {
            return;
        } // try
        for (LocalVariable lv : variables) {
            Value val = frame.getValue(lv);
            if (val instanceof ObjectReference or) {
                prepassVariableReference(
                        frame,
                        lv,
                        or,
                        declaringClassFqn,
                        methodName,
                        currentLine,
                        allocType,
                        astTypeResolver,
                        objectTypeMap);
            } // if
        } // for
    } // prepassFrameVariables

    /**
     * Prepass inspection of a single variable object reference.
     *
     * @param frame Stack frame.
     * @param lv Local variable.
     * @param or Object reference.
     * @param declaringClassFqn Declaring class FQN.
     * @param methodName Method name.
     * @param currentLine Current line number.
     * @param allocType Optional allocation type at line.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Target object type map.
     */
    private static void prepassVariableReference(
            StackFrame frame,
            LocalVariable lv,
            ObjectReference or,
            String declaringClassFqn,
            String methodName,
            int currentLine,
            Optional<String> allocType,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap) {
        String runtimeFqn = or.referenceType().name();
        Optional<String> varType = astTypeResolver.resolveVariableType(
                declaringClassFqn, methodName, lv.name(), currentLine);
        String candidateType = null;

        if (allocType.isPresent() && allocType.get().startsWith(runtimeFqn)) {
            candidateType = allocType.get();
        } else {
            if (varType.isPresent()) {
                String typeStr = varType.get();
                if (frame.thisObject() instanceof ObjectReference frameThis
                        && objectTypeMap.containsKey(frameThis.uniqueID())) {
                    Map<String, String> bindings = astTypeResolver.getTypeBindings(
                            declaringClassFqn, objectTypeMap.get(frameThis.uniqueID()));
                    typeStr = AstTypeResolver.substituteType(typeStr, bindings);
                } // if
                candidateType = astTypeResolver.reconcileRuntimeType(runtimeFqn, typeStr);
            } else {
                if (allocType.isPresent()) {
                    candidateType =
                            astTypeResolver.reconcileRuntimeType(runtimeFqn, allocType.get());
                } // if
            } // if
        } // if

        if (candidateType != null) {
            if (!objectTypeMap.containsKey(or.uniqueID())
                    || isMoreSpecific(
                            candidateType,
                            objectTypeMap.get(or.uniqueID()),
                            runtimeFqn)) {
                objectTypeMap.put(or.uniqueID(), candidateType);
            } // if
        } // if
    } // prepassVariableReference

    /**
     * Processes visible local variables in a single stack frame and appends their fields.
     *
     * @param frame Stack frame.
     * @param visibleVars Visible local variables.
     * @param finalVariableNames Set of final variable names.
     * @param methodLambdaAssignments Precomputed lambda assignments.
     * @param currentLine Current execution line number.
     * @param declaringClassFqn FQN of declaring class.
     * @param methodName Method name.
     * @param astTypeResolver AST type resolver.
     * @param objectTypeMap Reified object type map.
     * @param heapReferencesToWalk Heap reference queue.
     * @param heap Heap mapping.
     * @param stackFrameFields Target list for frame fields.
     * @throws ClassNotLoadedException If a variable class is not loaded.
     */
    private static void processStackFrameVariables(
            StackFrame frame,
            List<LocalVariable> visibleVars,
            Set<String> finalVariableNames,
            List<LambdaAssignment> methodLambdaAssignments,
            int currentLine,
            String declaringClassFqn,
            String methodName,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap,
            List<ExecutionSnapshot.Field> stackFrameFields)
            throws ClassNotLoadedException {
        for (LocalVariable lv : visibleVars) {
            TraceSession.elements(1);
            boolean isFinal = finalVariableNames.contains(lv.name());
            Optional<String> lvLambdaImplementation =
                    findLambdaImplementation(methodLambdaAssignments, lv.name(), currentLine);
            String resolvedTypeName = resolveLocalVariableType(
                    frame, lv, astTypeResolver, objectTypeMap, declaringClassFqn, methodName);
            appendStackField(
                    frame,
                    lv,
                    isFinal,
                    resolvedTypeName,
                    lvLambdaImplementation,
                    astTypeResolver,
                    objectTypeMap,
                    heapReferencesToWalk,
                    heap,
                    stackFrameFields);
        } // for
    } // processStackFrameVariables

    /**
     * Collects stack frame snapshots for all visible frames on the main thread.
     *
     * @param mainThread Suspended thread.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Object type map.
     * @param lambdaMap Lambda assignments map.
     * @param finalMap Final variable names map.
     * @param heapReferencesToWalk Heap references list.
     * @param heap Heap trace values map.
     * @return List of StackSnapshots in call order.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException On unloaded class.
     */

    private static List<StackSnapshot> collectStackSnapshots(
            ThreadReference mainThread,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap,
            Map<String, List<LambdaAssignment>> lambdaMap,
            Map<String, Set<String>> finalMap,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {
        List<StackSnapshot> stackSnapshots = new LinkedList<>();
        TraceSession.elements(mainThread.frameCount());
        for (StackFrame frame : mainThread.frames()) {
            Method frameMethod = frame.location().method();
            ReferenceType declaringType = frameMethod.declaringType();
            if (isGuestHarnessOrReflect(declaringType)) {
                continue;
            } // if
            String declaringClassFqn = declaringType.name();
            String frameMethodSignature = String.format(
                    "%s.%s(%s)",
                    declaringClassFqn,
                    frameMethod.name(),
                    frameMethod.argumentTypes().stream()
                            .map(Type::name)
                            .collect(Collectors.joining(",")));
            Set<String> finalVariableNames =
                    finalMap.getOrDefault(frameMethodSignature, Collections.emptySet());
            List<ExecutionSnapshot.Field> stackFrameFields = new ArrayList<>();
            List<LambdaAssignment> methodLambdaAssignments =
                    lambdaMap.getOrDefault(frameMethodSignature, Collections.emptyList());
            int currentLine = frame.location().lineNumber();
            String methodName = frameMethod.name();
            List<LocalVariable> visibleVars;
            try {
                visibleVars = frame.visibleVariables();
            } catch (AbsentInformationException ignored) {
                continue;
            } // try
            processStackFrameVariables(
                    frame, visibleVars, finalVariableNames, methodLambdaAssignments,
                    currentLine, declaringClassFqn, methodName, astTypeResolver,
                    objectTypeMap, heapReferencesToWalk, heap, stackFrameFields);
            Optional<ThisObject> thisObject = resolveThisObject(
                    frame, declaringClassFqn, objectTypeMap, heapReferencesToWalk);
            String frameSourcePath = resolveFrameSourcePath(frame);
            stackSnapshots.addFirst(new StackSnapshot(
                    frame.location().method().name(),
                    frame.location().lineNumber(),
                    stackFrameFields,
                    thisObject,
                    Optional.ofNullable(frameSourcePath)));
        } // for
        return stackSnapshots;
    } // collectStackSnapshots

    /**
     * Resolves the source path of a StackFrame.
     *
     * @param frame The StackFrame.
     * @return Source file path or null.
     */
    private static String resolveFrameSourcePath(StackFrame frame) {
        try {
            return frame.location().sourcePath();
        } catch (AbsentInformationException e) {
            try {
                return frame.location().sourceName();
            } catch (AbsentInformationException ignored) {
                return null;
            } // try
        } // try
    } // resolveFrameSourcePath

    /**
     * Finds lambda implementation for a variable.
     *
     * @param assignments List of assignments.
     * @param varName Variable name.
     * @param currentLine Current line.
     * @return Optional containing lambda implementation string.
     */
    private static Optional<String> findLambdaImplementation(
            List<LambdaAssignment> assignments, String varName, int currentLine) {
        return assignments.stream()
                .filter(la -> la.variableName().equals(varName) && la.lineNumber() <= currentLine)
                .reduce((first, second) -> second)
                .map(LambdaAssignment::lambdaImplementation)
                .or(() -> assignments.stream()
                        .filter(la -> la.variableName().equals(varName))
                        .findFirst()
                        .map(LambdaAssignment::lambdaImplementation));
    } // findLambdaImplementation

    /**
     * Resolves declared type for a local variable.
     *
     * @param frame StackFrame.
     * @param lv LocalVariable.
     * @param astTypeResolver AstTypeResolver.
     * @param objectTypeMap Reified type map.
     * @param declaringClassFqn Declaring class FQN.
     * @param methodName Method name.
     * @return Resolved type name string.
     */
    private static String resolveLocalVariableType(
            StackFrame frame,
            LocalVariable lv,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap,
            String declaringClassFqn,
            String methodName) {
        String resolvedTypeName = lv.typeName();
        Optional<String> astType = astTypeResolver.resolveVariableType(
                declaringClassFqn, methodName, lv.name(), frame.location().lineNumber());
        if (astType.isPresent()) {
            String candidate = astType.get();
            if (frame.thisObject() instanceof ObjectReference frameThis
                    && objectTypeMap.containsKey(frameThis.uniqueID())) {
                Map<String, String> bindings = astTypeResolver.getTypeBindings(
                        declaringClassFqn, objectTypeMap.get(frameThis.uniqueID()));
                candidate = AstTypeResolver.substituteType(candidate, bindings);
            } // if
            resolvedTypeName = candidate;
        } // if
        return resolvedTypeName;
    } // resolveLocalVariableType

    /**
     * Determines whether a new type candidate is more specific than an existing type string
     * for a given concrete runtime class.
     *
     * @param newType The new type candidate.
     * @param existingType The existing type string from objectTypeMap.
     * @param runtimeClassFqn The concrete runtime class FQN.
     * @return True if newType is more specific.
     */
    public static boolean isMoreSpecific(
            String newType, String existingType, String runtimeClassFqn) {
        if (newType == null) {
            return false;
        } // if
        if (existingType == null) {
            return true;
        } // if
        if (newType.equals(existingType)) {
            return false;
        } // if
        boolean newMatchesRuntime = newType.startsWith(runtimeClassFqn);
        boolean existingMatchesRuntime = existingType.startsWith(runtimeClassFqn);
        if (newMatchesRuntime && !existingMatchesRuntime) {
            return true;
        } // if
        if (!newMatchesRuntime && existingMatchesRuntime) {
            return false;
        } // if
        boolean newHasGenerics = newType.contains("<");
        boolean existingHasGenerics = existingType.contains("<");
        if (newHasGenerics && !existingHasGenerics) {
            return true;
        } // if
        if (!newHasGenerics && existingHasGenerics) {
            return false;
        } // if
        if (newHasGenerics) {
            boolean existingHasWildcard = existingType.contains("?");
            boolean newHasWildcard = newType.contains("?");
            if (existingHasWildcard && !newHasWildcard) {
                return true;
            } // if
        } // if
        return false;
    } // isMoreSpecific

    /**
     * Appends a stack variable field to the snapshot fields list.
     *
     * @param frame StackFrame.
     * @param lv LocalVariable.
     * @param isFinal True if variable is final.
     * @param resolvedTypeName Resolved type name.
     * @param lvLambdaImplementation Optional lambda implementation.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @param heapReferencesToWalk Heap references list.
     * @param heap Heap map.
     * @param stackFrameFields Target fields list.
     */
    private static void appendStackField(
            StackFrame frame,
            LocalVariable lv,
            boolean isFinal,
            String resolvedTypeName,
            Optional<String> lvLambdaImplementation,
            AstTypeResolver astTypeResolver,
            Map<Long, String> objectTypeMap,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap,
            List<ExecutionSnapshot.Field> stackFrameFields) {
        switch (frame.getValue(lv)) {
        case PrimitiveValue pv -> stackFrameFields.add(new ExecutionSnapshot.Field(
                isFinal,
                resolvedTypeName,
                lv.name(),
                TraceValue.Primitive.fromJdiPrimitive(pv)));
        case ObjectReference or when lvLambdaImplementation.isPresent() -> {
            stackFrameFields.add(new ExecutionSnapshot.Field(
                isFinal,
                resolvedTypeName,
                lv.name(),
                new TraceValue.Reference(or.uniqueID())));
            heapReferencesToWalk.add(or);
            TraceSession.elements(lvLambdaImplementation.get().length());
            heap.put(or.uniqueID(), new TraceValue.Lambda(lvLambdaImplementation.get()));
        } // case
        case ObjectReference or -> {
            String runtimeFqn = or.referenceType().name();
            if (resolvedTypeName != null) {
                String candidate =
                        astTypeResolver.reconcileRuntimeType(runtimeFqn, resolvedTypeName);
                if (!objectTypeMap.containsKey(or.uniqueID())
                        || isMoreSpecific(
                                candidate,
                                objectTypeMap.get(or.uniqueID()),
                                runtimeFqn)) {
                    objectTypeMap.put(or.uniqueID(), candidate);
                } // if
            } // if
            stackFrameFields.add(new ExecutionSnapshot.Field(
                    isFinal,
                    resolvedTypeName,
                    lv.name(),
                    new TraceValue.Reference(or.uniqueID())));
            heapReferencesToWalk.add(or);
        } // case
        case null -> stackFrameFields.add(new ExecutionSnapshot.Field(
                isFinal, resolvedTypeName, lv.name(), new TraceValue.Null()));
        default -> {
            // do nothing
        } // default
        } // switch
    } // appendStackField

    /**
     * Resolves the thisObject for a stack frame.
     *
     * @param frame StackFrame.
     * @param declaringClassFqn Declaring class FQN.
     * @param objectTypeMap Reified type map.
     * @param heapReferencesToWalk Heap references list.
     * @return Optional containing ThisObject.
     */
    private static Optional<ThisObject> resolveThisObject(
            StackFrame frame,
            String declaringClassFqn,
            Map<Long, String> objectTypeMap,
            List<ObjectReference> heapReferencesToWalk) {
        if (frame.thisObject() instanceof ObjectReference frameThis) {
            String thisType = declaringClassFqn;
            if (objectTypeMap.containsKey(frameThis.uniqueID())) {
                thisType = objectTypeMap.get(frameThis.uniqueID());
            } // if
            TraceValue.Reference thisReference = new TraceValue.Reference(frameThis.uniqueID());
            heapReferencesToWalk.add(frameThis);
            return Optional.of(new ThisObject(thisType, thisReference));
        } // if
        return Optional.empty();
    } // resolveThisObject

    /**
     * Collects static fields from all loaded classes.
     *
     * @param loadedClasses Loaded reference types.
     * @param sourceAnalysis Precomputed source analysis.
     * @param heapReferencesToWalk Heap references list.
     * @param heap Heap map.
     * @return List of static Field snapshots.
     */
    private static List<ExecutionSnapshot.Field> collectStatics(
            Iterable<ReferenceType> loadedClasses,
            SourceAnalysis sourceAnalysis,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap) {
        List<ExecutionSnapshot.Field> statics = new ArrayList<>();
        for (ReferenceType loadedClass : loadedClasses) {
            Optional<ClassOrInterfaceDeclaration> loadedClassDeclaration =
                    sourceAnalysis.findClassDeclaration(loadedClass.name());
            for (Field f : loadedClass.allFields()) {
                TraceSession.elements(1);
                if (!f.isStatic()) {
                    continue;
                } // if
                Optional<String> lambdaImplementation =
                        sourceAnalysis.findStaticLambdaImplementation(loadedClass.name(), f.name());
                String fieldName = String.join(".", loadedClass.name(), f.name());
                switch (loadedClass.getValue(f)) {
                case PrimitiveValue pv -> statics.add(new ExecutionSnapshot.Field(
                        f.isFinal(),
                        f.typeName(),
                        fieldName,
                        TraceValue.Primitive.fromJdiPrimitive(pv)));
                case ObjectReference or when lambdaImplementation.isPresent() -> {
                    heapReferencesToWalk.add(or);
                    TraceSession.elements(lambdaImplementation.get().length());
                    heap.put(or.uniqueID(), new TraceValue.Lambda(lambdaImplementation.get()));
                    statics.add(new ExecutionSnapshot.Field(
                            f.isFinal(),
                            f.typeName(),
                            fieldName,
                            new TraceValue.Reference(or.uniqueID())));
                } // case
                case ObjectReference or -> {
                    statics.add(new ExecutionSnapshot.Field(
                            f.isFinal(),
                            f.typeName(),
                            fieldName,
                            new TraceValue.Reference(or.uniqueID())));
                    heapReferencesToWalk.add(or);
                } // case
                case null -> statics.add(new ExecutionSnapshot.Field(
                        f.isFinal(), f.typeName(), fieldName, new TraceValue.Null()));
                default -> {
                    // do nothing
                } // default
                } // switch
            } // for
        } // for
        return statics;
    } // collectStatics

    /**
     * Collects static fields from all loaded classes with raw parsed sources.
     *
     * @param loadedClasses Loaded reference types.
     * @param parsedSources Compilation units.
     * @param heapReferencesToWalk Heap references list.
     * @param heap Heap map.
     * @return List of static Field snapshots.
     */
    private static List<ExecutionSnapshot.Field> collectStatics(
            Iterable<ReferenceType> loadedClasses,
            List<CompilationUnit> parsedSources,
            List<ObjectReference> heapReferencesToWalk,
            Map<Long, TraceValue> heap) {
        return collectStatics(
                loadedClasses, SourceAnalysis.from(parsedSources), heapReferencesToWalk, heap);
    } // collectStatics

    /**
     * Records an uncaught guest exception for the job result.
     * @param event Exception event.
     */
    private static void recordException(ExceptionEvent event) {
        if (TraceSession.current() != null) {
            TraceSession.current().guestException(event.exception().referenceType().name());
        } // if
    } // recordException

    /**
     * Waits for debugger events while observing session cancellation.
     * @param vm Guest debugger.
     * @return Events, possibly empty after a poll timeout.
     * @throws InterruptedException On thread cancellation.
     */
    static Iterable<Event> nextEvents(VirtualMachine vm) throws InterruptedException {
        TraceSession session = TraceSession.current();
        if (session != null) {
            session.check();
        } // if
        var events = vm.eventQueue().remove(100);
        return events == null ? List.of() : events;
    } // nextEvents

    /**
     * Stores selected breakpoint state, avoiding a second unbounded history in bounded jobs.
     * @param snapshots Legacy breakpoint mapping.
     * @param line Breakpoint line.
     * @param snapshot Completed state.
     */
    static void storeSnapshot(Map<Integer, List<ExecutionSnapshot>> snapshots,
            int line, ExecutionSnapshot snapshot) {
        List<ExecutionSnapshot> entries = snapshots.computeIfAbsent(line, key -> new ArrayList<>());
        if (TraceSession.current() != null && !TraceSession.current().accumulates()) {
            Optional<String> src = snapshot.sourcePath();
            entries.removeIf(existing -> Objects.equals(existing.sourcePath(), src));
        } // if
        entries.add(snapshot);
    } // storeSnapshot

    /** Deduplicates and budgets references before retaining them for heap traversal. */
    private static final class ReferenceQueue extends LinkedList<ObjectReference> {
        private static final long serialVersionUID = 1L;
        private final transient Set<Long> queued = new HashSet<>();

        /** Constructs an empty reference work queue. */
        ReferenceQueue() {} // ReferenceQueue

        @Override
        public boolean add(ObjectReference reference) {
            if (reference == null || queued.contains(reference.uniqueID())) {
                return false;
            } // if
            TraceSession session = TraceSession.current();
            if (session != null) {
                session.encounter(reference.uniqueID());
            } // if
            queued.add(reference.uniqueID());
            return super.add(reference);
        } // add
    } // ReferenceQueue
} // DebugTraceHelper
