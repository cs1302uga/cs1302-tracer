package cs1302.tracer.trace;

import com.sun.jdi.*;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.*;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.MethodExitRequest;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;

import java.io.IOException;
import java.util.*;

/** A collection of methods that are used to generate a debug trace. */
public class DebugTraceHelper {

    /**
     * Take snapshots of a program's execution state at the given breakpoints.
     *
     * @param compilationResult A properly filled CompilationResult (probably from a
     *                          call to CompilationHelper.compile()).
     * @param breakPoints       The source line numbers that you want to take
     *                          snapshots at. if it contains the special value -1,
     *                          is null, or is empty, a snapshot will be taken at
     *                          the time the main method exits.
     * @return A mapping from line numbers to execution snapshots. if a snapshot was
     *         taken at the end of main (as described above), it is provided under
     *         the special key -1.
     */
    public static Map<Integer, ExecutionSnapshot> trace(
        CompilationResult compilationResult,
        Collection<Integer> breakPoints)
        throws IOException, IllegalConnectorArgumentsException, VMStartException,
        InterruptedException, IncompatibleThreadStateException, AbsentInformationException {

        boolean snapMainEnd = breakPoints == null
            || breakPoints.isEmpty()
            || breakPoints.contains(-1);

        Map<Integer, ExecutionSnapshot> snapshots = new HashMap<>();

        VirtualMachine vm = startVmWithCprs(compilationResult);

        // TODO the JVM will crash if output buffers (stdout/err) are filled, so we need
        // to empty them as they're populated during debugging

        if (snapMainEnd) {
            // fire an event when exiting main
            MethodExitRequest methodExitRequest =
                vm.eventRequestManager().createMethodExitRequest();
            methodExitRequest.addClassFilter(compilationResult.mainClass());
            methodExitRequest.enable();
        }

        List<ReferenceType> loadedClasses = new ArrayList<>();

        boolean endEventLoop = false;
        while (!endEventLoop) {
            for (Event event : vm.eventQueue().remove()) {
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    if (compilationResult.compiledClassNames()
                        .contains(cpe.referenceType().name())) {
                        if (breakPoints != null) {
                            for (int breakLine : breakPoints) {
                                List<Location> locations =
                                    cpe.referenceType().locationsOfLine(breakLine);
                                if (locations.isEmpty()) {
                                    break;
                                }
                                vm.eventRequestManager().createBreakpointRequest(locations.get(0))
                                    .enable();
                            }
                        }
                        loadedClasses.add(cpe.referenceType());
                    }
                }
                case BreakpointEvent bpe -> {
                    Location breakLocation = bpe.location();
                    if (compilationResult.compiledClassNames()
                        .contains(breakLocation.declaringType().name())) {
                        snapshots.put(breakLocation.lineNumber(),
                            snapshotTheWorld(bpe.thread(), loadedClasses));
                    }
                }
                case MethodExitEvent mee -> {
                    Method method = mee.method();
                    // this is the JNI signature for a method with one string array parameter that
                    // returns void. for details, see
                    // https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/types.html#type_signatures
                    String mainJniSignature = "([Ljava/lang/String;)V";
                    boolean isMain =
                        method.isPublic() && method.isStatic() && method.name().equals("main")
                            && method.signature().equals(mainJniSignature);

                    if (isMain && (snapMainEnd || snapshots.isEmpty())) {
                        snapshots.put(-1, snapshotTheWorld(mee.thread(), loadedClasses));
                    }
                }
                case VMDeathEvent vde -> {
                    endEventLoop = true;
                    break;
                }
                default -> {
                }
                }

                vm.resume();
            }
        }

        return snapshots;
    }

    /**
     * Take a snapshot of a program's execution state just before the main method returns.
     *
     * @param compilationResult A properly filled CompilationResult (probably from a
     *                          call to CompilationHelper.compile()).
     * @return An execution snapshot taken at the end of the main method, or null if execution
     *         terminated before the main method ended.
     */
    public static ExecutionSnapshot trace(
        CompilationResult compilationResult)
        throws IOException, IllegalConnectorArgumentsException, VMStartException,
        InterruptedException, IncompatibleThreadStateException, AbsentInformationException {
        return trace(compilationResult, null).get(-1);
    } // trace

    /**
     * Get the lines of a Java program that are valid breakpoint targets.
     *
     * @param compilationResult The CompilationResult of the program that you want to find the
     *                          valid breakpoints for.
     * @return The lines of compilationResult that are valid breakpoint targets.
     */
    public static HashSet<Integer> getValidBreakpointLines(CompilationResult compilationResult)
        throws IOException, IllegalConnectorArgumentsException, VMStartException,
        InterruptedException, AbsentInformationException {

        VirtualMachine vm = startVmWithCprs(compilationResult);

        HashSet<Integer> validBreakLines = new HashSet<>();
        HashSet<String> compiledClasses = new HashSet<>(compilationResult.compiledClassNames());

        while (!compiledClasses.isEmpty()) {
            for (Event event : vm.eventQueue().remove()) {
                switch (event) {
                case ClassPrepareEvent cpe -> {
                    validBreakLines.addAll(cpe.referenceType().allLineLocations()
                        .stream()
                        .map(ll -> ll.lineNumber())
                        .toList());
                    compiledClasses.remove(cpe.referenceType().name());
                } // case ClassPrepareEvent

                case VMDeathEvent vde -> {
                    return validBreakLines;
                } // case VMDeathEvent

                default -> {
                    System.err.printf(
                        "[getValidBreakpointLines] Unused Event: %s\n",
                        event);
                } // default
                } // switch
                vm.resume();
            } // for
        } // while

        return validBreakLines;
    } // getValidBreakpointLines

    /**
     * Start a JDI VM prepopulated with ClassPrepareRequests for the
     * compiledClassNames present in compilationResult.
     *
     * @param  compilationResult The CompilationResult that contains the classes for which class
     *                           preparation requests should be registered.
     * @return The VirtualMachine for the launched VM.
     */
    private static VirtualMachine startVmWithCprs(
        CompilationResult compilationResult)
        throws IOException, IllegalConnectorArgumentsException, VMStartException {

        LaunchingConnector launchingConnector = Bootstrap
            .virtualMachineManager()
            .defaultConnector();

        Map<String, Connector.Argument> env = launchingConnector
            .defaultArguments();

        env.get("main").setValue(compilationResult.mainClass());
        env.get("options").setValue("-classpath " + compilationResult.classPath());

        VirtualMachine vm = launchingConnector.launch(env);

        for (String className : compilationResult.compiledClassNames()) {
            // fire an event each time one of our classes is prepared, mostly as a
            // springboard for setting up further eventrequests
            ClassPrepareRequest classPrepareRequest = vm
                .eventRequestManager()
                .createClassPrepareRequest();
            classPrepareRequest.addClassFilter(className);
            classPrepareRequest.enable();
        } // for

        return vm;
    } // startVmWithCprs

    /**
     * Take a snapshot of a thread's memory state at this instant of execution.
     *
     * @param mainThread A suspended thread that you want to take a snapshot of.
     * @param loadedClasses The loaded classes whose static fields you want included in the
     *                      snapshot.
     * @return An execution snapshot of the thread's memory state at the time of calling.
     */
    private static ExecutionSnapshot snapshotTheWorld(
        ThreadReference mainThread,
        Iterable<ReferenceType> loadedClasses)
        throws IncompatibleThreadStateException, AbsentInformationException {

        List<ObjectReference> heapReferencesToWalk = new ArrayList<>();

        // collect stack frames and their fields
        List<StackSnapshot> stackSnapshots = new ArrayList<>();
        for (StackFrame frame : mainThread.frames()) {
            List<ExecutionSnapshot.Field> stackFrameFields = new ArrayList<>();

            for (LocalVariable lv : frame.visibleVariables()) {
                switch (frame.getValue(lv)) {
                case PrimitiveValue pv -> stackFrameFields
                    .add(new ExecutionSnapshot.Field(lv.typeName(), lv.name(),
                        TraceValue.Primitive.fromJdiPrimitive(pv)));
                case ObjectReference or -> {
                    stackFrameFields
                        .add(new ExecutionSnapshot.Field(lv.typeName(), lv.name(),
                            new TraceValue.Reference(or.uniqueID())));
                    heapReferencesToWalk.add(or);
                }
                case null -> stackFrameFields.add(
                    new ExecutionSnapshot.Field(lv.typeName(), lv.name(), new TraceValue.Null()));
                default -> {
                }
                }
            }

            stackSnapshots
                .add(new StackSnapshot(frame.location().method().name(),
                    frame.location().lineNumber(),
                    stackFrameFields));
        }

        // collect static values that have been loaded
        List<ExecutionSnapshot.Field> statics = new ArrayList<>();
        for (ReferenceType loadedClass : loadedClasses) {
            for (Field f : loadedClass.allFields()) {
                if (!f.isStatic()) {
                    continue;
                }

                String fieldName = String.join(".", loadedClass.name(), f.name());
                switch (loadedClass.getValue(f)) {
                case PrimitiveValue pv -> statics.add(new ExecutionSnapshot.Field(f.typeName(),
                      fieldName, TraceValue.Primitive.fromJdiPrimitive(pv)));
                case ObjectReference or -> {
                    statics.add(new ExecutionSnapshot.Field(f.typeName(), fieldName,
                        new TraceValue.Reference(or.uniqueID())));
                    heapReferencesToWalk.add(or);
                }
                case null ->
                    statics.add(new ExecutionSnapshot.Field(f.typeName(), fieldName,
                          new TraceValue.Null()));
                default -> {
                }
                }
            }
        }

        // recursively collect heap values reachable from the roots contained in
        // heapReferencesToWalk
        Map<Long, TraceValue> heap = new HashMap<>();
        while (!heapReferencesToWalk.isEmpty()) {
            ObjectReference workingObject = heapReferencesToWalk.removeFirst();
            if (heap.containsKey(workingObject.uniqueID())) {
                continue;
            }

            TraceValue convertedObject = TraceValue.fromJdiValue(mainThread, workingObject,
                Optional.of(heapReferencesToWalk));
            heap.put(workingObject.uniqueID(), convertedObject);
        }

        return new ExecutionSnapshot(stackSnapshots, statics, heap);
    }
}
