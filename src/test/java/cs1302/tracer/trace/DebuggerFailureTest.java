package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DebuggerFailureTest {
    private static Object call(String name, Class<?>[] signature, Object... arguments) throws Exception {
        var method = DebugTraceHelper.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    @Test
    void sourceMetadataFallsBackToFilenameThenUnknown() throws Exception {
        for (boolean filenameAvailable : List.of(false, true)) {
            var location = mirror(Location.class, Map.of("sourcePath", new AbsentInformationException(),
                    "sourceName", filenameAvailable ? "Main.java" : new AbsentInformationException()));
            var frame = mirror(StackFrame.class, Map.of("location", location));
            var thread = mirror(ThreadReference.class, Map.of("frames", List.of(frame), "frame", frame));
            assertThat(call("resolveFrameSourcePath", new Class<?>[] {StackFrame.class}, frame))
                    .isEqualTo(filenameAvailable ? "Main.java" : null);
            assertThat(call("resolveStepSourcePath", new Class<?>[] {ThreadReference.class}, thread))
                    .isEqualTo(filenameAvailable ? "Main.java" : null);
        }
        for (Object frames : List.of(List.of(), new IncompatibleThreadStateException())) {
            var thread = mirror(ThreadReference.class, Map.of("frames", frames));
            assertThat(call("resolveStepSourcePath", new Class<?>[] {ThreadReference.class}, thread)).isNull();
        }
    }

    @Test
    void flushingUnavailableGuestStreamsDoesNotAbortTracing() throws Exception {
        var suspended = mirror(ThreadReference.class, Map.of("isSuspended", true));
        var running = mirror(ThreadReference.class, Map.of("isSuspended", false));
        var vm = mirror(VirtualMachine.class, Map.of("classesByName", List.of()));
        var signature = new Class<?>[] {VirtualMachine.class, ThreadReference.class};
        call("flushTargetStreams", signature, null, suspended);
        call("flushTargetStreams", signature, vm, null);
        call("flushTargetStreams", signature, vm, running);
        call("flushTargetStreams", signature, vm, suspended);
        call("flushTargetStreams", signature,
                mirror(VirtualMachine.class, Map.of("classesByName", new VMDisconnectedException())), suspended);
        var missingField = mirror(ReferenceType.class, Map.of());
        var field = mirror(Field.class, Map.of());
        var primitive = mirror(ReferenceType.class, Map.of("fieldByName", field,
                "getValue", mirror(IntegerValue.class, Map.of("value", 1))));
        var missingMethod = mirror(ReferenceType.class, Map.of("methodsByName", List.of()));
        var stream = mirror(ObjectReference.class, Map.of("referenceType", missingMethod));
        var noFlush = mirror(ReferenceType.class, Map.of("fieldByName", field, "getValue", stream));
        for (var system : List.of(missingField, primitive, noFlush)) {
            call("flushPrintStreamField", new Class<?>[] {ReferenceType.class, String.class,
                    ThreadReference.class}, system, "out", suspended);
        }
    }

    @Test
    void systemInputLookupToleratesMissingOrDisconnectedVmMetadata() throws Exception {
        var field = mirror(Field.class, Map.of());
        var noField = mirror(ReferenceType.class, Map.of());
        var nonObject = mirror(ReferenceType.class, Map.of("fieldByName", field,
                "getValue", mirror(IntegerValue.class, Map.of("value", 1))));
        for (var values : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("classesByName", List.of()),
                Map.<String, Object>of("classesByName", List.of(noField)),
                Map.<String, Object>of("classesByName", List.of(nonObject)),
                Map.<String, Object>of("classesByName", new VMDisconnectedException()))) {
            assertThat(call("getSystemIn", new Class<?>[] {VirtualMachine.class},
                    mirror(VirtualMachine.class, values))).isNull();
        }
    }

    @Test
    void disposalStillRunsWhenProcessLookupFails() throws Exception {
        var disposed = new AtomicBoolean();
        var vm = (VirtualMachine) Proxy.newProxyInstance(VirtualMachine.class.getClassLoader(),
                new Class<?>[] {VirtualMachine.class}, (self, method, args) -> {
                    if (method.getName().equals("process")) throw new IllegalStateException("gone");
                    if (method.getName().equals("dispose")) {
                        disposed.set(true);
                        throw new VMDisconnectedException();
                    }
                    return null;
                });
        call("cleanupVm", new Class<?>[] {VirtualMachine.class}, vm);
        assertThat(disposed).isTrue();
        call("cleanupVm", new Class<?>[] {VirtualMachine.class}, mirror(VirtualMachine.class, Map.of()));
    }

    private static ExecutionSnapshot snapshot(String method, int line) {
        return new ExecutionSnapshot(method == null ? List.of() : List.of(
                new ExecutionSnapshot.StackSnapshot(method, line, List.of(), java.util.Optional.empty())),
                List.of(), Map.of(), new byte[0], new byte[0]);
    }

    @Test
    void comparesOnlyPopulatedTopFrames() throws Exception {
        var signature = new Class<?>[] {ExecutionSnapshot.class, ExecutionSnapshot.class};
        assertThat(call("isSameTopFrame", signature, snapshot(null, 0), snapshot("main", 1))).isEqualTo(false);
        assertThat(call("isSameTopFrame", signature, snapshot("main", 1), snapshot(null, 0))).isEqualTo(false);
        assertThat(call("isSameTopFrame", signature, snapshot("main", 1), snapshot("other", 1))).isEqualTo(false);
        assertThat(call("isSameTopFrame", signature, snapshot("main", 1), snapshot("main", 2))).isEqualTo(false);
        assertThat(call("isSameTopFrame", signature, snapshot("main", 1), snapshot("main", 1))).isEqualTo(true);
    }

    @Test
    void trailingOutputPreservesThreadMetadataInBothSnapshotShapes() throws Exception {
        var frame = new ExecutionSnapshot.StackSnapshot(
                "main", 1, List.of(), java.util.Optional.empty());
        var original = new ExecutionSnapshot(List.of(frame), List.of(), Map.of(),
                OutputSlice.empty(), OutputSlice.empty(), java.util.Optional.of("Main.java"),
                "input", 5, List.of(new ExecutionSnapshot.ThreadSnapshot(
                        7L, "worker", "RUNNING", List.of(frame))), 7L, "thread_death");
        var chronological = new java.util.ArrayList<>(List.of(original));
        var targeted = new java.util.HashMap<Integer, List<ExecutionSnapshot>>();
        targeted.put(1, List.of(original));
        try (var out = new StreamDrainer(new java.io.ByteArrayInputStream(new byte[] {65}));
                var err = new StreamDrainer(new java.io.ByteArrayInputStream(new byte[] {66}))) {
            out.waitForEof(1000);
            err.waitForEof(1000);
            call("syncTrailingStreamOutput",
                    new Class<?>[] {List.class, StreamDrainer.class, StreamDrainer.class},
                    chronological, out, err);
            call("syncTrailingStreamOutput",
                    new Class<?>[] {Map.class, StreamDrainer.class, StreamDrainer.class},
                    targeted, out, err);
            for (var updated : List.of(chronological.getLast(), targeted.get(1).getLast())) {
                assertThat(updated).usingRecursiveComparison()
                        .ignoringFields("stdoutSlice", "stderrSlice").isEqualTo(original);
                assertThat(updated.stdout()).containsExactly((byte) 65);
                assertThat(updated.stderr()).containsExactly((byte) 66);
                assertThat(updated.materializeOutput()).usingRecursiveComparison()
                        .ignoringFields("stdoutSlice", "stderrSlice").isEqualTo(original);
                assertThat(updated.withSharedOutput(updated.stdout(), updated.stderr()))
                        .usingRecursiveComparison().ignoringFields("stdoutSlice", "stderrSlice")
                        .isEqualTo(original);
            }
        }
    }

    @Test
    void trailingOutputCopiesImmutableSnapshotLists() throws Exception {
        var original = snapshot("main", 1);
        var snapshots = new java.util.HashMap<Integer, List<ExecutionSnapshot>>();
        snapshots.put(1, List.of(original));
        snapshots.put(2, List.of());
        snapshots.put(3, null);
        try (var out = new StreamDrainer(new java.io.ByteArrayInputStream(new byte[] {65}));
                var err = new StreamDrainer(java.io.InputStream.nullInputStream())) {
            out.waitForEof(1000);
            err.waitForEof(1000);
            call("syncTrailingStreamOutput", new Class<?>[] {Map.class, StreamDrainer.class, StreamDrainer.class},
                    snapshots, out, err);
            assertThat(snapshots.get(1).getFirst().stdout()).containsExactly((byte) 65);
            assertThat(original.stdout()).isEmpty();
            assertThat(snapshots.get(2)).isEmpty();
            assertThat(snapshots.get(3)).isNull();
        }
    }

    @Test
    void stdinDeliveryToleratesUnavailableGuestAndClosedPipe() throws Exception {
        var signature = new Class<?>[] {VirtualMachine.class, String.class};
        call("writeGuestStdin", signature, null, "input");
        call("writeGuestStdin", signature, mirror(VirtualMachine.class, Map.of()), "input");
        var closed = new AtomicBoolean();
        var process = new Process() {
            @Override public java.io.OutputStream getOutputStream() { return new java.io.OutputStream() {
                @Override public void write(int value) throws java.io.IOException { throw new java.io.IOException("closed pipe"); }
                @Override public void close() { closed.set(true); }
            }; }
            @Override public java.io.InputStream getInputStream() { return java.io.InputStream.nullInputStream(); }
            @Override public java.io.InputStream getErrorStream() { return java.io.InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() {}
        };
        var vm = mirror(VirtualMachine.class, Map.of("process", process));
        call("writeGuestStdin", signature, vm, "input");
        assertThat(closed).isTrue();
        closed.set(false);
        call("writeGuestStdin", signature, vm, null);
        assertThat(closed).isTrue();
    }

    @Test
    void readerMetadataFailureDoesNotAbortTheTrace() throws Exception {
        var signature = new Class<?>[] {com.sun.jdi.event.MethodExitEvent.class, InputTracker.class, ObjectReference.class};
        var event = mirror(com.sun.jdi.event.MethodExitEvent.class, Map.of("method", new VMDisconnectedException()));
        call("handleReaderMethodExit", signature, event, null, null);
        var tracker = new InputTracker("abc");
        call("handleReaderMethodExit", signature, event, tracker, mirror(ObjectReference.class, Map.of()));
        assertThat(tracker.offset()).isZero();
    }

    @Test
    void typePreferencePreservesRuntimeNamesAndConcreteArguments() {
        assertThat(DebugTraceHelper.isMoreSpecific("Other<String>", "Box", "Box")).isFalse();
        assertThat(DebugTraceHelper.isMoreSpecific("Box", "Box<String>", "Box")).isFalse();
        assertThat(DebugTraceHelper.isMoreSpecific("Box<String>", "Box<?>", "Box")).isTrue();
        assertThat(DebugTraceHelper.isMoreSpecific("Box<? extends Number>", "Box<?>", "Box")).isFalse();
        assertThat(DebugTraceHelper.isMoreSpecific("Box<Integer>", "Box<String>", "Box")).isFalse();
        assertThat(DebugTraceHelper.isMoreSpecific("One", "Other", "Box")).isFalse();
    }

    @Test
    void unsupportedMirrorValuesDoNotCreateFields() throws Exception {
        var unsupported = mirror(VoidValue.class, Map.of());
        var frame = mirror(StackFrame.class, Map.of("getValue", unsupported));
        var variable = mirror(LocalVariable.class, Map.of("name", "x"));
        var fields = new java.util.ArrayList<ExecutionSnapshot.Field>();
        call("appendStackField", new Class<?>[] {StackFrame.class, LocalVariable.class, boolean.class, String.class,
                java.util.Optional.class, AstTypeResolver.class, Map.class, List.class, Map.class, List.class},
                frame, variable, false, "void", java.util.Optional.empty(), new AstTypeResolver(List.of()),
                new java.util.HashMap<>(), new java.util.ArrayList<>(), new java.util.HashMap<>(), fields);
        assertThat(fields).isEmpty();
        var field = mirror(Field.class, Map.of("isStatic", true, "name", "x"));
        var type = mirror(ReferenceType.class, Map.of("name", "C", "allFields", List.of(field), "getValue", unsupported));
        assertThat(call("collectStatics", new Class<?>[] {Iterable.class, List.class, List.class, Map.class},
                List.of(type), java.util.Arrays.asList((com.github.javaparser.ast.CompilationUnit) null),
                new java.util.ArrayList<>(), new java.util.HashMap<>())).isEqualTo(List.of());
    }

    @Test
    void readerExitLooksUpSystemInputWhenCacheIsMissing() throws Exception {
        var vm = mirror(VirtualMachine.class, Map.of("classesByName", List.of()));
        var event = mirror(com.sun.jdi.event.MethodExitEvent.class, Map.of("virtualMachine", vm,
                "method", new VMDisconnectedException()));
        var tracker = new InputTracker("abc");
        call("handleReaderMethodExit", new Class<?>[] {com.sun.jdi.event.MethodExitEvent.class,
                InputTracker.class, ObjectReference.class}, event, tracker, null);
        assertThat(tracker.offset()).isZero();
    }
}
