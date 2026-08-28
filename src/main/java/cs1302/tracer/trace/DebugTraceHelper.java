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
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic;
import com.github.javaparser.resolution.types.ResolvedLambdaConstraintType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
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
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.MethodExitRequest;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** A collection of methods that are used to generate a debug trace. */
public class DebugTraceHelper {

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
    private record LambdaAssignment(
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
        return trace(compilationResult, breakPoints, List.of(parsedSource));
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

        boolean snapMainEnd =
                breakPoints == null || breakPoints.isEmpty() || breakPoints.contains(-1);
        Map<Integer, List<ExecutionSnapshot>> snapshots = new HashMap<>();

        VirtualMachine vm = startVmWithCprs(compilationResult);
        try {
            ByteArrayOutputStream vmErrSink = new ByteArrayOutputStream();
            drainStream(vm.process().getErrorStream(), vmErrSink);

            ByteArrayOutputStream vmOutSink = new ByteArrayOutputStream();
            drainStream(vm.process().getInputStream(), vmOutSink);

            if (snapMainEnd) {
                MethodExitRequest methodExitRequest =
                        vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            processBreakpointsEventLoop(
                    vm,
                    compilationResult,
                    breakPoints,
                    parsedSources,
                    snapshots,
                    loadedClasses,
                    vmOutSink,
                    vmErrSink,
                    snapMainEnd);

            return snapshots;
        } finally {
            cleanupVm(vm);
        } // try
    } // trace

    /**
     * Runs the event loop for the breakpoint trace.
     *
     * @param vm The JDI VirtualMachine.
     * @param compilationResult The compilation result.
     * @param breakPoints Collection of breakpoint lines.
     * @param parsedSources List of compilation units.
     * @param snapshots Target map to accumulate snapshots.
     * @param loadedClasses Set of loaded reference types.
     * @param vmOut Output stream for stdout.
     * @param vmErr Output stream for stderr.
     * @param snapMainEnd True if main exit should be captured.
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
            ByteArrayOutputStream vmOut,
            ByteArrayOutputStream vmErr,
            boolean snapMainEnd)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        boolean endEventLoop = false;
        while (!endEventLoop) {
            for (Event event : vm.eventQueue().remove()) {
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
                                bpe.thread(), loadedClasses, vmOut, vmErr, parsedSources);
                        snapshots.computeIfAbsent(
                                line, ArrayList<ExecutionSnapshot>::new).add(snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && (snapMainEnd || snapshots.isEmpty())) {
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                mee.thread(), loadedClasses, vmOut, vmErr, parsedSources);
                        snapshots.put(-1, List.of(snapshot));
                    } // if
                } // case
                case VMDeathEvent vde -> {
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
     * Registers breakpoint requests on a newly prepared class.
     *
     * @param vm The JDI VirtualMachine.
     * @param refType The loaded reference type.
     * @param breakPoints The collection of line numbers.
     * @throws AbsentInformationException If line info is absent.
     */
    private static void registerBreakpoints(
            VirtualMachine vm, ReferenceType refType, Collection<Integer> breakPoints)
            throws AbsentInformationException {
        if (breakPoints != null) {
            for (int breakLine : breakPoints) {
                List<Location> locations = refType.locationsOfLine(breakLine);
                if (!locations.isEmpty()) {
                    vm.eventRequestManager().createBreakpointRequest(locations.get(0)).enable();
                } // if
            } // for
        } // if
    } // registerBreakpoints

    /**
     * Checks if a JDI method matches the main method signature.
     *
     * @param method The JDI method.
     * @return True if method is main.
     */
    private static boolean isMainMethodExit(Method method) {
        String mainJniSignature = "([Ljava/lang/String;)V";
        return method.isPublic()
                && method.isStatic()
                && method.name().equals("main")
                && method.signature().equals(mainJniSignature);
    } // isMainMethodExit

    /**
     * Drains an input stream into a byte array output stream asynchronously.
     *
     * @param source The input stream to read.
     * @param sink The byte array output stream to write to.
     */
    private static void drainStream(InputStream source, ByteArrayOutputStream sink) {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    int data = source.read();
                    if (data == -1) {
                        break;
                    } // if
                    synchronized (sink) {
                        sink.write(data);
                    }
                } catch (IOException ioe) {
                    break;
                } // try
            } // while
        });
    } // drainStream

    /**
     * Cleans up and disposes the JDI VirtualMachine safely.
     *
     * @param vm Target VM.
     */
    private static void cleanupVm(VirtualMachine vm) {
        try {
            vm.exit(0);
        } catch (VMDisconnectedException | IllegalStateException ignored) {
            // ignore cleanup error
        } // try
        try {
            vm.dispose();
        } catch (VMDisconnectedException | IllegalStateException ignored) {
            // ignore cleanup error
        } // try
    } // cleanupVm

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
        return trace(compilationResult, null, List.of(parsedSource)).get(-1).getLast();
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
        return trace(compilationResult, null, parsedSources).get(-1).getLast();
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
                compilationResult, breakPoints, List.of(parsedSource), includeMainExit);
    } // traceChronological

    /**
     * Run a program under JDI and capture all snapshots in chronological order.
     *
     * @param compilationResult A properly filled CompilationResult.
     * @param breakPoints The collection of line numbers where breakpoints should be placed.
     * @param parsedSources Parsed source codes for the compiled program.
     * @param includeMainExit If true, includes the snapshot when main exits at the end.
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

        VirtualMachine vm = startVmWithCprs(compilationResult);
        List<ExecutionSnapshot> chronologicalSnapshots = new ArrayList<>();

        try {
            ByteArrayOutputStream vmErrSink = new ByteArrayOutputStream();
            drainStream(vm.process().getErrorStream(), vmErrSink);

            ByteArrayOutputStream vmOutSink = new ByteArrayOutputStream();
            drainStream(vm.process().getInputStream(), vmOutSink);

            if (includeMainExit) {
                MethodExitRequest methodExitRequest =
                        vm.eventRequestManager().createMethodExitRequest();
                methodExitRequest.addClassFilter(compilationResult.mainClass());
                methodExitRequest.enable();
            } // if

            HashSet<ReferenceType> loadedClasses = new HashSet<>();
            processChronologicalEventLoop(
                    vm,
                    compilationResult,
                    breakPoints,
                    parsedSources,
                    chronologicalSnapshots,
                    loadedClasses,
                    vmOutSink,
                    vmErrSink,
                    includeMainExit);

            return chronologicalSnapshots;
        } finally {
            cleanupVm(vm);
        } // try
    } // traceChronological

    /**
     * Processes the chronological event loop.
     *
     * @param vm Target VM.
     * @param compilationResult Compilation result.
     * @param breakPoints Breakpoint lines.
     * @param parsedSources Compilation units.
     * @param chronologicalSnapshots List to accumulate snapshots.
     * @param loadedClasses Set of loaded classes.
     * @param vmOut Stdout sink.
     * @param vmErr Stderr sink.
     * @param includeMainExit True to include main exit.
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
            ByteArrayOutputStream vmOut,
            ByteArrayOutputStream vmErr,
            boolean includeMainExit)
            throws InterruptedException,
            IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        boolean endEventLoop = false;
        while (!endEventLoop) {
            for (Event event : vm.eventQueue().remove()) {
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
                                bpe.thread(), loadedClasses, vmOut, vmErr, parsedSources);
                        chronologicalSnapshots.add(snapshot);
                    } // if
                } // case
                case MethodExitEvent mee -> {
                    if (isMainMethodExit(mee.method()) && includeMainExit) {
                        ExecutionSnapshot snapshot = snapshotTheWorld(
                                mee.thread(), loadedClasses, vmOut, vmErr, parsedSources);
                        chronologicalSnapshots.add(snapshot);
                    } // if
                } // case
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

        VirtualMachine vm = startVmWithCprs(compilationResult);
        try {
            Map<String, Set<Integer>> fileLines = new HashMap<>();
            HashSet<String> compiledClasses =
                    new HashSet<>(compilationResult.compiledClassNames());

            while (!compiledClasses.isEmpty()) {
                for (Event event : vm.eventQueue().remove()) {
                    switch (event) {
                    case ClassPrepareEvent cpe -> {
                        for (Location loc : cpe.referenceType().allLineLocations()) {
                            String path = resolveLocationPath(loc, cpe.referenceType());
                            fileLines.computeIfAbsent(
                                    path, k -> new HashSet<>()).add(loc.lineNumber());
                        } // for
                        compiledClasses.remove(cpe.referenceType().name());
                    } // case
                    case VMDeathEvent vde -> {
                        return fileLines;
                    } // case
                    default -> {
                        // do nothing
                    } // default
                    } // switch
                    vm.resume();
                } // for
            } // while

            return fileLines;
        } finally {
            cleanupVm(vm);
        } // try
    } // getValidBreakpointLinesByFile

    /**
     * Resolves source path from a Location object.
     *
     * @param loc The Location.
     * @param refType The declaring ReferenceType.
     * @return Resolved file path string.
     */
    private static String resolveLocationPath(Location loc, ReferenceType refType) {
        try {
            return loc.sourcePath();
        } catch (AbsentInformationException e) {
            try {
                return loc.sourceName();
            } catch (AbsentInformationException ex) {
                return refType.name().replace('.', '/') + ".java";
            } // try
        } // try
    } // resolveLocationPath

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
        env.get("options").setValue("-classpath " + compilationResult.classPath());

        VirtualMachine vm = launchingConnector.launch(env);

        for (String className : compilationResult.compiledClassNames()) {
            ClassPrepareRequest classPrepareRequest =
                    vm.eventRequestManager().createClassPrepareRequest();
            classPrepareRequest.addClassFilter(className);
            classPrepareRequest.enable();
        } // for

        return vm;
    } // startVmWithCprs

    /**
     * Convert a lambda expression in the AST into an implementation.
     *
     * @param lambda The lambda expression to convert.
     * @return A string containing a valid method implementation.
     */
    private static Optional<String> tryImplementLambdaSam(LambdaExpr lambda) {
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
            if (lambda.getBody() instanceof BlockStmt b) {
                sb.append(b);
            } // if
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
     * @param vmOut Output stream for stdout.
     * @param vmErr Output stream for stderr.
     * @param parsedSources Parsed source codes.
     * @return An execution snapshot.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    private static ExecutionSnapshot snapshotTheWorld(
            ThreadReference mainThread,
            Iterable<ReferenceType> loadedClasses,
            ByteArrayOutputStream vmOut,
            ByteArrayOutputStream vmErr,
            List<CompilationUnit> parsedSources)
            throws IncompatibleThreadStateException,
            AbsentInformationException,
            ClassNotLoadedException {

        List<ObjectReference> heapReferencesToWalk = new ArrayList<>();
        Map<Long, TraceValue> heap = new HashMap<>();
        AstTypeResolver astTypeResolver = new AstTypeResolver(parsedSources);
        Map<Long, String> objectTypeMap = new HashMap<>();

        Map<String, List<LambdaAssignment>> lambdaMethodAssignments = new HashMap<>();
        Map<String, Set<String>> finalMethodVariables = new HashMap<>();
        buildLambdaAndFinalMaps(
                parsedSources, lambdaMethodAssignments, finalMethodVariables);

        prepassObjectTypes(mainThread, astTypeResolver, objectTypeMap);

        List<StackSnapshot> stackSnapshots = collectStackSnapshots(
                mainThread,
                astTypeResolver,
                objectTypeMap,
                lambdaMethodAssignments,
                finalMethodVariables,
                heapReferencesToWalk,
                heap);

        List<ExecutionSnapshot.Field> statics = collectStatics(
                loadedClasses, parsedSources, heapReferencesToWalk, heap);

        drainHeapReferences(mainThread, astTypeResolver, objectTypeMap, heapReferencesToWalk, heap);

        byte[] vmOutBytes;
        synchronized (vmOut) {
            vmOutBytes = vmOut.toByteArray();
        }
        byte[] vmErrBytes;
        synchronized (vmErr) {
            vmErrBytes = vmErr.toByteArray();
        }

        String currentStepSourcePath = resolveStepSourcePath(mainThread);

        return new ExecutionSnapshot(
                stackSnapshots,
                statics,
                heap,
                vmOutBytes,
                vmErrBytes,
                Optional.ofNullable(currentStepSourcePath));
    } // snapshotTheWorld

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
    private static void buildLambdaAndFinalMaps(
            List<CompilationUnit> parsedSources,
            Map<String, List<LambdaAssignment>> lambdaMap,
            Map<String, Set<String>> finalMap) {
        for (CompilationUnit cu : parsedSources) {
            if (cu == null) {
                continue;
            } // if
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
                String sig = resolveMethodSignature(m);
                List<LambdaAssignment> assignments = new ArrayList<>();

                for (VariableDeclarator d : m.findAll(VariableDeclarator.class)) {
                    if (d.getInitializer().map(Expression::isLambdaExpr).orElse(false)) {
                        tryImplementLambdaSam(d.getInitializer().get().asLambdaExpr())
                                .ifPresent(impl -> assignments.add(new LambdaAssignment(
                                        d.getNameAsString(),
                                        d.getRange().map(r -> r.begin.line).orElse(0),
                                        impl)));
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
                            tryImplementLambdaSam(a.getValue().asLambdaExpr())
                                    .ifPresent(impl -> assignments.add(new LambdaAssignment(
                                            finalVarName,
                                            a.getRange().map(r -> r.begin.line).orElse(0),
                                            impl)));
                        } // if
                    } // if
                } // for

                assignments.sort(Comparator.comparingInt(LambdaAssignment::lineNumber));
                lambdaMap.put(sig, assignments);

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
            String declaringClassFqn = frame.location().method().declaringType().name();
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
                    objectTypeMap.putIfAbsent(frameThis.uniqueID(), allocType.get());
                } // if
            } // if

            Optional<String> allocType =
                    astTypeResolver.getAllocationType(declaringClassFqn, currentLine);

            for (LocalVariable lv : frame.visibleVariables()) {
                Value val = frame.getValue(lv);
                if (val instanceof ObjectReference or) {
                    Optional<String> varType = astTypeResolver.resolveVariableType(
                            declaringClassFqn, methodName, lv.name(), currentLine);
                    if (varType.isPresent()) {
                        String typeStr = varType.get();
                        if (frame.thisObject() instanceof ObjectReference frameThis
                                && objectTypeMap.containsKey(frameThis.uniqueID())) {
                            Map<String, String> bindings = astTypeResolver.getTypeBindings(
                                    declaringClassFqn, objectTypeMap.get(frameThis.uniqueID()));
                            typeStr = AstTypeResolver.substituteType(typeStr, bindings);
                        } // if
                        if (typeStr.contains("<")
                                || astTypeResolver.getClassGenericInfo(typeStr).isPresent()) {
                            objectTypeMap.putIfAbsent(or.uniqueID(), typeStr);
                        } // if
                    } else {
                        if (allocType.isPresent()) {
                            objectTypeMap.putIfAbsent(or.uniqueID(), allocType.get());
                        } // if
                    } // if
                } // if
            } // for

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
        for (StackFrame frame : mainThread.frames()) {
            Method frameMethod = frame.location().method();
            String frameMethodSignature = String.format(
                    "%s.%s(%s)",
                    frameMethod.declaringType().name(),
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
            String declaringClassFqn = frameMethod.declaringType().name();
            String methodName = frameMethod.name();

            for (LocalVariable lv : frame.visibleVariables()) {
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
                        objectTypeMap,
                        heapReferencesToWalk,
                        heap,
                        stackFrameFields);
            } // for

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
     * Appends a stack variable field to the snapshot fields list.
     *
     * @param frame StackFrame.
     * @param lv LocalVariable.
     * @param isFinal True if variable is final.
     * @param resolvedTypeName Resolved type name.
     * @param lvLambdaImplementation Optional lambda implementation.
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
            heap.put(or.uniqueID(), new TraceValue.Lambda(lvLambdaImplementation.get()));
        } // case
        case ObjectReference or -> {
            if (resolvedTypeName != null && resolvedTypeName.contains("<")) {
                objectTypeMap.putIfAbsent(or.uniqueID(), resolvedTypeName);
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
        List<ExecutionSnapshot.Field> statics = new ArrayList<>();
        for (ReferenceType loadedClass : loadedClasses) {
            Optional<ClassOrInterfaceDeclaration> loadedClassDeclaration =
                    findClassDeclaration(parsedSources, loadedClass.name());

            for (Field f : loadedClass.allFields()) {
                if (!f.isStatic()) {
                    continue;
                } // if

                Optional<String> lambdaImplementation =
                        findStaticLambdaImplementation(loadedClassDeclaration, f.name());
                String fieldName = String.join(".", loadedClass.name(), f.name());

                switch (loadedClass.getValue(f)) {
                case PrimitiveValue pv -> statics.add(new ExecutionSnapshot.Field(
                        f.isFinal(),
                        f.typeName(),
                        fieldName,
                        TraceValue.Primitive.fromJdiPrimitive(pv)));
                case ObjectReference or when lambdaImplementation.isPresent() -> {
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
     * Finds class declaration in compilation units by FQN.
     *
     * @param parsedSources Compilation units.
     * @param className Class FQN.
     * @return Optional containing ClassOrInterfaceDeclaration.
     */
    private static Optional<ClassOrInterfaceDeclaration> findClassDeclaration(
            List<CompilationUnit> parsedSources, String className) {
        for (CompilationUnit cu : parsedSources) {
            if (cu != null) {
                Optional<ClassOrInterfaceDeclaration> decl = cu.findFirst(
                        ClassOrInterfaceDeclaration.class,
                        c -> className.equals(
                                c.getFullyQualifiedName().orElseGet(c::getNameAsString)));
                if (decl.isPresent()) {
                    return decl;
                } // if
            } // if
        } // for
        return Optional.empty();
    } // findClassDeclaration

    /**
     * Finds static lambda implementation in class declaration.
     *
     * @param classDecl Class declaration.
     * @param fieldName Field identifier.
     * @return Optional containing lambda implementation text.
     */
    private static Optional<String> findStaticLambdaImplementation(
            Optional<ClassOrInterfaceDeclaration> classDecl, String fieldName) {
        return classDecl.flatMap(d -> d.findFirst(
                VariableDeclarator.class,
                vd -> vd.getNameAsString().equals(fieldName)))
                .filter(vd -> vd.getInitializer().map(Expression::isLambdaExpr).orElse(false))
                .map(vd -> vd.getInitializer().get().asLambdaExpr())
                .flatMap(DebugTraceHelper::tryImplementLambdaSam);
    } // findStaticLambdaImplementation
} // DebugTraceHelper
