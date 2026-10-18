package cs1302.tracer.internal.trace;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import cs1302.tracer.internal.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.internal.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import cs1302.tracer.internal.execution.TraceSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Assembles complete snapshots, owning prepass ordering and shared heap traversal.
 * Execution supplies suspended threads and job observations; the active session retains budget and admission ownership.
 */
final class SnapshotCapture {

    /** Prevents utility construction. */
    private SnapshotCapture() {} // SnapshotCapture

    /**
     * Takes an execution snapshot with explicit internal harness class-loader identity.
     *
     * @param mainThread The main thread reference.
     * @param loadedClasses Currently loaded reference types.
     * @param sourceAnalysis Precomputed source analysis.
     * @param inputObservation Input observation.
     * @param output Per-job output owner.
     * @param harnessClassLoader Internal harness class loader, or null for ordinary tracing.
     * @return An execution snapshot.
     * @throws IncompatibleThreadStateException If thread state is incompatible.
     * @throws AbsentInformationException If debug info is missing.
     * @throws ClassNotLoadedException If class is not loaded.
     */
    static ExecutionSnapshot capture(ThreadReference mainThread, Iterable<ReferenceType> loadedClasses,
            SourceAnalysis sourceAnalysis, InputObservation inputObservation,
            JobOutput output, ClassLoaderReference harnessClassLoader)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        TraceSession session = TraceSession.current();
        prepareSessionAndStreams(session, mainThread, output);

        AstTypeResolver astTypeResolver = sourceAnalysis.astTypeResolver();
        ReifiedTypeMetadata metadata = new ReifiedTypeMetadata(astTypeResolver);
        HeapInspection heapInspection = new HeapInspection(mainThread, metadata);

        ThreadCapture capture = session == null ? null : session.threadCapture();
        List<ExecutionSnapshot.ThreadSnapshot> captured = collectThreadStacks(mainThread, sourceAnalysis, metadata,
                heapInspection, harnessClassLoader);
        List<StackSnapshot> stackSnapshots = capture == null ? captured.getFirst().stack()
                : captured.stream().filter(t -> t.id() == mainThread.uniqueID()).findFirst().orElseThrow().stack();
        List<ExecutionSnapshot.ThreadSnapshot> threads = capture == null ? null : captured;

        List<ExecutionSnapshot.Field> statics = collectStatics(loadedClasses, sourceAnalysis, heapInspection);

        Map<Long, TraceValue> heap = heapInspection.finish();

        JobOutput.Prefix prefix = output.capture();
        if (session != null) {
            session.allocate(prefix.rawBytes());
        } // if
        OutputSlice vmOutSlice = prefix.stdout();
        OutputSlice vmErrSlice = prefix.stderr();

        String currentStepSourcePath = capture == null ? resolveStepSourcePath(mainThread)
                : stackSnapshots.isEmpty() ? null : stackSnapshots.getLast().sourcePath().orElse(null);
        String stdinConsumed = inputObservation.consumed();
        int stdinOffset = inputObservation.offset();

        ExecutionSnapshot snapshot = new ExecutionSnapshot(stackSnapshots, statics, heap, vmOutSlice, vmErrSlice,
                Optional.ofNullable(currentStepSourcePath), stdinConsumed, stdinOffset, threads,
                capture == null ? null : mainThread.uniqueID(), capture == null ? null : capture.event());
        if (session != null) {
            session.commit(snapshot);
        } // if
        return snapshot;
    } // capture

    /**
     * Captures all selected stacks into a shared heap traversal.
     *
     * @param mainThread Event thread.
     * @param sourceAnalysis Source metadata.
     * @param metadata Inferred object types.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @param harnessClassLoader Harness loader for legacy jobs.
     * @return Captured application thread states.
     * @throws IncompatibleThreadStateException If stacks cannot be read.
     * @throws AbsentInformationException If debug information is unavailable.
     * @throws ClassNotLoadedException If a referenced type is unavailable.
     */
    private static List<ExecutionSnapshot.ThreadSnapshot> collectThreadStacks(ThreadReference mainThread,
            SourceAnalysis sourceAnalysis, ReifiedTypeMetadata metadata, HeapInspection heapInspection,
            ClassLoaderReference harnessClassLoader)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        TraceSession session = TraceSession.current();
        ThreadCapture capture = session == null ? null : session.threadCapture();
        AstTypeResolver astTypeResolver = sourceAnalysis.astTypeResolver();
        if (capture == null) {
            prepassObjectTypes(mainThread, astTypeResolver, metadata, harnessClassLoader);
            List<StackSnapshot> stack = collectStackSnapshots(mainThread, astTypeResolver, metadata,
                    sourceAnalysis,
                    heapInspection, harnessClassLoader);
            return List.of(new ExecutionSnapshot.ThreadSnapshot(0, "", "", stack));
        } // if
        List<ThreadReference> capturedThreads = capture.threads(mainThread);
        long frameCount = 0;
        for (ThreadReference thread : capturedThreads) {
            frameCount += thread.frameCount();
            session.checkThreadCounts(capturedThreads.size(), frameCount);
        } // for
        List<ExecutionSnapshot.ThreadSnapshot> threads = new ArrayList<>();
        for (ThreadReference thread : capturedThreads) {
            prepassObjectTypes(thread, astTypeResolver, metadata, harnessClassLoader);
        } // for
        for (ThreadReference thread : capturedThreads) {
            List<StackSnapshot> stack = collectStackSnapshots(thread, astTypeResolver, metadata,
                    sourceAnalysis,
                    heapInspection, harnessClassLoader);
            threads.add(new ExecutionSnapshot.ThreadSnapshot(thread.uniqueID(), thread.name(),
                    ThreadCapture.state(thread), stack));
        } // for
        if (capture.isDying(mainThread)) {
            threads.add(new ExecutionSnapshot.ThreadSnapshot(mainThread.uniqueID(), mainThread.name(), "TERMINATED",
                    List.of()));
        } // if

        return threads;
    } // collectThreadStacks

    /**
     * Begins snapshot accounting and synchronizes job output prior to heap inspection.
     *
     * @param session The active trace session.
     * @param output Per-job output owner.
     * @param mainThread The main thread reference.
     */
    private static void prepareSessionAndStreams(TraceSession session, ThreadReference mainThread, JobOutput output) {
        if (session != null) {
            session.beginSnapshot();
        } // if
        if (TraceSession.mayInvoke()) {
            flushTargetStreams(mainThread.virtualMachine(), mainThread);
        } // if
        output.drain(0, 0);
    } // prepareSessionAndStreams

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
     * Returns true if declaring class corresponds to internal coordinator harness or reflection. Student classes named
     * GuestHarness are preserved unless loaded by the known harness loader.
     *
     * @param declaringType Declaring ReferenceType.
     * @param harnessClassLoader Internal harness class loader, or null for ordinary tracing.
     * @return True if frame should be filtered from student trace.
     */
    private static boolean isGuestHarnessOrReflect(ReferenceType declaringType,
            ClassLoaderReference harnessClassLoader) {
        String declaringClassFqn = declaringType.name();
        if (declaringClassFqn.startsWith("jdk.internal.reflect.") || declaringClassFqn.startsWith("java.lang.reflect.")
                || declaringClassFqn.equals("java.lang.Thread")) {
            return true;
        } // if
        if (declaringClassFqn.equals("cs1302.tracer.internal.guest.GuestHarness")
                || declaringClassFqn.startsWith("cs1302.tracer.internal.guest.GuestHarness$")) {
            return harnessClassLoader != null && harnessClassLoader.equals(declaringType.classLoader());
        } // if
        return false;
    } // isGuestHarnessOrReflect

    /**
     * Restricts multithread frames and static fields to submitted classes.
     *
     * @param type Declaring class.
     * @return True when the declaring class should be inspected.
     */
    private static boolean isCapturedClass(ReferenceType type) {
        TraceSession session = TraceSession.current();
        return session == null || session.threadCapture() == null
                || session.threadCapture().applicationClass(type.name());
    } // isCapturedClass

    /**
     * Pre-pass over frames to propagate types from AST allocations into metadata.
     *
     * @param mainThread Suspended thread.
     * @param astTypeResolver AstTypeResolver instance.
     * @param metadata Snapshot-owned reified type evidence.
     * @param harnessClassLoader Internal harness class loader, or null for ordinary tracing.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException On unloaded class.
     */
    private static void prepassObjectTypes(ThreadReference mainThread, AstTypeResolver astTypeResolver,
            ReifiedTypeMetadata metadata, ClassLoaderReference harnessClassLoader)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {

        List<StackFrame> frameList = mainThread.frames();
        ReifiedTypeMetadata.FrameBindings contexts = metadata.frames(frameList);
        for (int i = 0; i < frameList.size(); i++) {
            StackFrame frame = frameList.get(i);
            ReferenceType declaringType = frame.location().method().declaringType();
            if (isGuestHarnessOrReflect(declaringType, harnessClassLoader) || !isCapturedClass(declaringType)) {
                continue;
            } // if
            String declaringClassFqn = declaringType.name();
            String methodName = frame.location().method().name();
            int currentLine = frame.location().lineNumber();

            if ("<init>".equals(methodName) && i + 1 < frameList.size()) {
                StackFrame caller = frameList.get(i + 1);
                String callerClass = caller.location().method().declaringType().name();
                int callerLine = caller.location().lineNumber();
                Optional<String> allocType = astTypeResolver.getAllocationType(callerClass, callerLine);
                if (allocType.isPresent() && frame.thisObject() instanceof ObjectReference frameThis) {
                    String candidate = allocType.get();
                    String rawAlloc = AstTypeResolver.extractRawTypeName(candidate);
                    String runtimeClass = frameThis.referenceType().name();
                    if (AstTypeResolver.rawTypeMatches(runtimeClass, rawAlloc)) {
                        metadata.recordFallback(frameThis.uniqueID(), candidate);
                    } // if
                } // if
            } // if

            Optional<String> allocType = astTypeResolver.getAllocationType(declaringClassFqn, currentLine);

            prepassFrameVariables(frame, allocType, astTypeResolver, metadata, contexts);

            if (frame.thisObject() instanceof ObjectReference frameThis) {
                if (metadata.typeOf(frameThis.uniqueID()) == null) {
                    Optional<AstTypeResolver.ClassGenericInfo> info = astTypeResolver
                            .getClassGenericInfo(declaringClassFqn);
                    if (info.isPresent() && info.get().typeParameters().isEmpty()) {
                        metadata.recordFallback(frameThis.uniqueID(), declaringClassFqn);
                    } // if
                } // if
            } // if
        } // for
    } // prepassObjectTypes

    /**
     * Prepass inspection of visible variables in a stack frame.
     *
     * @param frame Stack frame.
     * @param allocType Optional allocation type at line.
     * @param astTypeResolver AstTypeResolver instance.
     * @param metadata Snapshot-owned reified type evidence.
     * @param contexts Extraction-local invocation contexts.
     * @throws AbsentInformationException On absent debug info.
     */
    private static void prepassFrameVariables(StackFrame frame, Optional<String> allocType,
            AstTypeResolver astTypeResolver, ReifiedTypeMetadata metadata, ReifiedTypeMetadata.FrameBindings contexts)
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
                prepassVariableReference(frame, lv, or, allocType, astTypeResolver, metadata, contexts.bindings(frame));
            } // if
        } // for
    } // prepassFrameVariables

    /**
     * Prepass inspection of a single variable object reference.
     *
     * @param frame Stack frame.
     * @param lv Local variable.
     * @param or Object reference.
     * @param allocType Optional allocation type at line.
     * @param astTypeResolver AstTypeResolver instance.
     * @param metadata Snapshot-owned reified type evidence.
     * @param bindings Current frame's lexical bindings.
     */
    private static void prepassVariableReference(StackFrame frame, LocalVariable lv, ObjectReference or,
            Optional<String> allocType, AstTypeResolver astTypeResolver, ReifiedTypeMetadata metadata,
            Map<String, String> bindings) {
        String runtimeFqn = or.referenceType().name();
        Optional<String> varType = astTypeResolver.resolveFrameVariableType(frame, lv.name());
        String candidateType = null;

        if (allocType.isPresent() && allocType.get().startsWith(runtimeFqn)) {
            candidateType = allocType.get();
        } else {
            if (varType.isPresent()) {
                String typeStr = varType.get();
                typeStr = AstTypeResolver.substituteType(typeStr, bindings);
                candidateType = astTypeResolver.reconcileRuntimeType(runtimeFqn, typeStr);
            } else {
                if (allocType.isPresent()) {
                    candidateType = astTypeResolver.reconcileRuntimeType(runtimeFqn, allocType.get());
                } // if
            } // if
        } // if

        if (candidateType != null) {
            metadata.refine(or.uniqueID(), candidateType, runtimeFqn);
        } // if
    } // prepassVariableReference

    /**
     * Processes visible local variables in a single stack frame and appends their fields.
     *
     * @param frame Stack frame.
     * @param visibleVars Visible local variables.
     * @param observations Source observations for the captured location.
     * @param astTypeResolver AST type resolver.
     * @param metadata Snapshot-owned reified type evidence.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @param stackFrameFields Target list for frame fields.
     * @param contexts Extraction-local invocation contexts.
     * @throws ClassNotLoadedException If a variable class is not loaded.
     */
    private static void processStackFrameVariables(StackFrame frame, List<LocalVariable> visibleVars,
            SourceAnalysis.Observations observations,
            AstTypeResolver astTypeResolver, ReifiedTypeMetadata metadata, HeapInspection heapInspection,
            List<ExecutionSnapshot.Field> stackFrameFields,
            ReifiedTypeMetadata.FrameBindings contexts) throws ClassNotLoadedException {
        for (LocalVariable lv : visibleVars) {
            TraceSession.elements(1);
            boolean isFinal = observations.isFinal(lv.name());
            Optional<String> lvLambdaImplementation = observations.lambdaImplementation(lv.name());
            String resolvedTypeName = resolveLocalVariableType(frame, lv, astTypeResolver, contexts.bindings(frame));
            appendStackField(frame, lv, isFinal, resolvedTypeName, lvLambdaImplementation, metadata,
                    heapInspection, stackFrameFields);
        } // for
    } // processStackFrameVariables

    /**
     * Collects visible stack frames from a suspended thread, applying harness and capture-policy filtering.
     *
     * @param mainThread Suspended thread.
     * @param astTypeResolver AstTypeResolver instance.
     * @param metadata Snapshot-owned reified type evidence.
     * @param sourceAnalysis Source observations for captured locations.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @param harnessClassLoader Internal harness class loader, or null for ordinary tracing.
     * @return List of StackSnapshots in call order.
     * @throws IncompatibleThreadStateException On thread state error.
     * @throws AbsentInformationException On absent debug info.
     * @throws ClassNotLoadedException On unloaded class.
     */

    private static List<StackSnapshot> collectStackSnapshots(ThreadReference mainThread,
            AstTypeResolver astTypeResolver, ReifiedTypeMetadata metadata,
            SourceAnalysis sourceAnalysis,
            HeapInspection heapInspection, ClassLoaderReference harnessClassLoader)
            throws IncompatibleThreadStateException, AbsentInformationException, ClassNotLoadedException {
        List<StackSnapshot> stackSnapshots = new LinkedList<>();
        List<StackFrame> frames = mainThread.frames();
        ReifiedTypeMetadata.FrameBindings contexts = metadata.frames(frames);
        for (StackFrame frame : frames) {
            Method frameMethod = frame.location().method();
            ReferenceType declaringType = frameMethod.declaringType();
            if (isGuestHarnessOrReflect(declaringType, harnessClassLoader) || !isCapturedClass(declaringType)) {
                continue;
            } // if
            TraceSession.elements(1);
            String declaringClassFqn = declaringType.name();
            SourceAnalysis.Observations observations = sourceAnalysis.observations(declaringClassFqn,
                    frameMethod.name(), frameMethod.argumentTypes().stream().map(Type::name).toList(),
                    frame.location().lineNumber());
            List<ExecutionSnapshot.Field> stackFrameFields = new ArrayList<>();
            List<LocalVariable> visibleVars;
            try {
                visibleVars = frame.visibleVariables();
            } catch (AbsentInformationException ignored) {
                continue;
            } // try
            processStackFrameVariables(frame, visibleVars, observations,
                    astTypeResolver, metadata, heapInspection, stackFrameFields, contexts);
            Optional<ThisObject> thisObject = resolveThisObject(frame, declaringClassFqn, metadata,
                    heapInspection);
            String frameSourcePath = resolveFrameSourcePath(frame);
            stackSnapshots.addFirst(new StackSnapshot(frame.location().method().name(), frame.location().lineNumber(),
                    stackFrameFields, thisObject, Optional.ofNullable(frameSourcePath)));
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
     * Resolves declared type for a local variable.
     *
     * @param frame StackFrame.
     * @param lv LocalVariable.
     * @param astTypeResolver AstTypeResolver.
     * @param bindings Current frame's lexical bindings.
     * @return Resolved type name string.
     */
    private static String resolveLocalVariableType(StackFrame frame, LocalVariable lv, AstTypeResolver astTypeResolver,
            Map<String, String> bindings) {
        String resolvedTypeName = lv.typeName();
        Optional<String> astType = astTypeResolver.resolveFrameVariableType(frame, lv.name());
        if (astType.isPresent()) {
            String candidate = astType.get();
            candidate = AstTypeResolver.substituteType(candidate, bindings);
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
     * @param metadata Snapshot-owned reified type evidence.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @param stackFrameFields Target fields list.
     */
    private static void appendStackField(StackFrame frame, LocalVariable lv, boolean isFinal, String resolvedTypeName,
            Optional<String> lvLambdaImplementation, ReifiedTypeMetadata metadata,
            HeapInspection heapInspection, List<ExecutionSnapshot.Field> stackFrameFields) {
        switch (frame.getValue(lv)) {
        case PrimitiveValue pv -> stackFrameFields.add(new ExecutionSnapshot.Field(isFinal, resolvedTypeName, lv.name(),
                HeapInspection.primitive(pv)));
        case ObjectReference or when lvLambdaImplementation.isPresent() -> {
            stackFrameFields.add(new ExecutionSnapshot.Field(isFinal, resolvedTypeName, lv.name(),
                    new TraceValue.Reference(or.uniqueID())));
            heapInspection.sourceLambda(or, lvLambdaImplementation.get());
        } // case
        case ObjectReference or -> {
            if (resolvedTypeName != null) {
                metadata.refineDeclared(or, resolvedTypeName);
            } // if
            stackFrameFields.add(new ExecutionSnapshot.Field(isFinal, resolvedTypeName, lv.name(),
                    new TraceValue.Reference(or.uniqueID())));
            heapInspection.encounter(or);
        } // case
        case null -> stackFrameFields
                .add(new ExecutionSnapshot.Field(isFinal, resolvedTypeName, lv.name(), new TraceValue.Null()));
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
     * @param metadata Snapshot-owned reified type evidence.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @return Optional containing ThisObject.
     */
    private static Optional<ThisObject> resolveThisObject(StackFrame frame, String declaringClassFqn,
            ReifiedTypeMetadata metadata, HeapInspection heapInspection) {
        if (frame.thisObject() instanceof ObjectReference frameThis) {
            String thisType = declaringClassFqn;
            if (metadata.typeOf(frameThis.uniqueID()) != null) {
                thisType = metadata.typeOf(frameThis.uniqueID());
            } // if
            TraceValue.Reference thisReference = new TraceValue.Reference(frameThis.uniqueID());
            heapInspection.encounter(frameThis);
            return Optional.of(new ThisObject(thisType, thisReference));
        } // if
        return Optional.empty();
    } // resolveThisObject

    /**
     * Collects static fields from the supplied loaded classes, applying capture-policy filtering.
     *
     * @param loadedClasses Loaded reference types.
     * @param sourceAnalysis Precomputed source analysis.
     * @param heapInspection Snapshot-owned reachable-heap inspection.
     * @return List of static Field snapshots.
     */
    private static List<ExecutionSnapshot.Field> collectStatics(Iterable<ReferenceType> loadedClasses,
            SourceAnalysis sourceAnalysis, HeapInspection heapInspection) {
        List<ExecutionSnapshot.Field> statics = new ArrayList<>();
        for (ReferenceType loadedClass : loadedClasses) {
            for (Field f : loadedClass.allFields()) {
                TraceSession.elements(1);
                if (!f.isStatic() || !isCapturedClass(f.declaringType())) {
                    continue;
                } // if
                Optional<String> lambdaImplementation = sourceAnalysis
                        .findStaticLambdaImplementation(loadedClass.name(), f.name());
                String fieldName = String.join(".", loadedClass.name(), f.name());
                switch (loadedClass.getValue(f)) {
                case PrimitiveValue pv -> statics.add(new ExecutionSnapshot.Field(f.isFinal(), f.typeName(), fieldName,
                        HeapInspection.primitive(pv)));
                case ObjectReference or when lambdaImplementation.isPresent() -> {
                    heapInspection.sourceLambda(or, lambdaImplementation.get());
                    statics.add(new ExecutionSnapshot.Field(f.isFinal(), f.typeName(), fieldName,
                            new TraceValue.Reference(or.uniqueID())));
                } // case
                case ObjectReference or -> {
                    statics.add(new ExecutionSnapshot.Field(f.isFinal(), f.typeName(), fieldName,
                            new TraceValue.Reference(or.uniqueID())));
                    heapInspection.encounter(or);
                } // case
                case null -> statics
                        .add(new ExecutionSnapshot.Field(f.isFinal(), f.typeName(), fieldName, new TraceValue.Null()));
                default -> {
                    // do nothing
                } // default
                } // switch
            } // for
        } // for
        return statics;
    } // collectStatics

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
    private static void flushPrintStreamField(ReferenceType systemClass, String fieldName, ThreadReference thread) {
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
                    printStreamRef.invokeMethod(thread, flushMethods.get(0), Collections.emptyList(),
                            ObjectReference.INVOKE_SINGLE_THREADED);
                } // if
            } // if
        } catch (Exception e) {
            // Graceful degradation: do not interrupt tracing if flush invocation fails
        } // try
    } // flushPrintStreamField

} // SnapshotCapture
