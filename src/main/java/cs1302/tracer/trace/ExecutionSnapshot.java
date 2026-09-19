package cs1302.tracer.trace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A snapshot of a program's memory state.
 *
 * @param stack The program's stack. Index 0 is the bottommost frame, the last index is the topmost.
 * @param statics Loaded static variables.
 * @param heap The program's heap, a mapping of reference IDs to values.
 * @param stdoutSlice Captured standard output slice.
 * @param stderrSlice Captured standard error slice.
 * @param sourcePath Optional relative source file path for the currently executing line.
 * @param stdinConsumed Cumulative standard input consumed up to this snapshot point.
 * @param stdinOffset Character index reached in standard input up to this snapshot point.
 */
public record ExecutionSnapshot(
        List<StackSnapshot> stack,
        List<Field> statics,
        Map<Long, TraceValue> heap,
        OutputSlice stdoutSlice,
        OutputSlice stderrSlice,
        Optional<String> sourcePath,
        String stdinConsumed,
        int stdinOffset) {

    /**
     * Compact constructor normalizing null slices, optionals, and strings.
     */
    public ExecutionSnapshot {
        stdoutSlice = stdoutSlice != null ? stdoutSlice : OutputSlice.empty();
        stderrSlice = stderrSlice != null ? stderrSlice : OutputSlice.empty();
        sourcePath = sourcePath != null ? sourcePath : Optional.empty();
        stdinConsumed = stdinConsumed != null ? stdinConsumed : "";
    } // ExecutionSnapshot

    /**
     * Constructs a snapshot with raw byte arrays for standard output and error.
     *
     * @param stack The program's stack.
     * @param statics Loaded static variables.
     * @param heap The program's heap.
     * @param stdout Bytes that have been output by the program to stdout.
     * @param stderr Bytes that have been output by the program to stderr.
     * @param sourcePath Optional relative source file path.
     * @param stdinConsumed Cumulative standard input consumed up to this snapshot point.
     * @param stdinOffset Character index reached in standard input up to this snapshot point.
     */
    public ExecutionSnapshot(
            List<StackSnapshot> stack,
            List<Field> statics,
            Map<Long, TraceValue> heap,
            byte[] stdout,
            byte[] stderr,
            Optional<String> sourcePath,
            String stdinConsumed,
            int stdinOffset) {
        this(stack, statics, heap,
                OutputSlice.from(stdout),
                OutputSlice.from(stderr),
                sourcePath, stdinConsumed, stdinOffset);
    } // ExecutionSnapshot

    /**
     * Constructs a snapshot with an explicit source file path defaulting stdin tracking.
     *
     * @param stack The program's stack.
     * @param statics Loaded static variables.
     * @param heap The program's heap.
     * @param stdout Captured standard output bytes.
     * @param stderr Captured standard error bytes.
     * @param sourcePath Optional relative source file path.
     */
    public ExecutionSnapshot(
            List<StackSnapshot> stack,
            List<Field> statics,
            Map<Long, TraceValue> heap,
            byte[] stdout,
            byte[] stderr,
            Optional<String> sourcePath) {
        this(stack, statics, heap, stdout, stderr, sourcePath, "", 0);
    } // ExecutionSnapshot

    /**
     * Constructs a snapshot without an explicit source file path.
     *
     * @param stack The program's stack.
     * @param statics Loaded static variables.
     * @param heap The program's heap.
     * @param stdout Captured standard output bytes.
     * @param stderr Captured standard error bytes.
     */
    public ExecutionSnapshot(
            List<StackSnapshot> stack,
            List<Field> statics,
            Map<Long, TraceValue> heap,
            byte[] stdout,
            byte[] stderr) {
        this(stack, statics, heap, stdout, stderr, Optional.empty(), "", 0);
    } // ExecutionSnapshot

    /**
     * Materializes the output slices of this snapshot into self-contained buffers.
     *
     * @return Snapshot with materialized output slices.
     */
    public ExecutionSnapshot materializeOutput() {
        OutputSlice matOut = stdoutSlice.materialize();
        OutputSlice matErr = stderrSlice.materialize();
        if (matOut == stdoutSlice && matErr == stderrSlice) {
            return this;
        } // if
        return new ExecutionSnapshot(
                stack, statics, heap, matOut, matErr, sourcePath, stdinConsumed, stdinOffset);
    } // materializeOutput

    /**
     * Replaces the output slices of this snapshot with slices from shared backing buffers.
     *
     * @param sharedStdout Shared standard output buffer.
     * @param sharedStderr Shared standard error buffer.
     * @return New snapshot referencing shared output slices.
     */
    ExecutionSnapshot withSharedOutput(byte[] sharedStdout, byte[] sharedStderr) {
        OutputSlice matOut = OutputSlice.wrapShared(sharedStdout, 0, stdoutSlice.length());
        OutputSlice matErr = OutputSlice.wrapShared(sharedStderr, 0, stderrSlice.length());
        return new ExecutionSnapshot(
                stack, statics, heap, matOut, matErr, sourcePath, stdinConsumed, stdinOffset);
    } // withSharedOutput

    /**
     * Returns captured standard output bytes.
     *
     * @return Byte array of standard output.
     */
    public byte[] stdout() {
        return stdoutSlice.toByteArray();
    } // stdout

    /**
     * Returns captured standard error bytes.
     *
     * @return Byte array of standard error.
     */
    public byte[] stderr() {
        return stderrSlice.toByteArray();
    } // stderr

    /**
     * Returns the length in bytes of standard output without allocating.
     *
     * @return Standard output length in bytes.
     */
    public int stdoutLength() {
        return stdoutSlice.length();
    } // stdoutLength

    /**
     * Returns the length in bytes of standard error without allocating.
     *
     * @return Standard error length in bytes.
     */
    public int stderrLength() {
        return stderrSlice.length();
    } // stderrLength

    /**
     * A snapshot of the state of a method's stack.
     *
     * @param methodName The name of the method this frame is associated with.
     * @param methodLine The line number this snapshot was taken at.
     * @param visibleVariables The stack variables that are accessible in this method at line
     *     methodLine.
     * @param thisObject A reference to the value of {@code this} for the frame, or empty if the
     *     method is native or static.
     * @param sourcePath Optional relative source file path for this stack frame.
     */
    public record StackSnapshot(
            String methodName,
            long methodLine,
            List<Field> visibleVariables,
            Optional<ThisObject> thisObject,
            Optional<String> sourcePath) {

        /**
         * Constructs a stack frame snapshot without an explicit source file path.
         *
         * @param methodName The method name.
         * @param methodLine The line number.
         * @param visibleVariables Accessible stack variables.
         * @param thisObject Optional reference to {@code this}.
         */
        public StackSnapshot(
                String methodName,
                long methodLine,
                List<Field> visibleVariables,
                Optional<ThisObject> thisObject) {
            this(methodName, methodLine, visibleVariables, thisObject, Optional.empty());
        } // StackSnapshot

        /**
         * A pair that includes the type name of and a reference to the value of {@code this}.
         *
         * @param typeName The name of type of {@code this}. Note that the name may differ from
         *     the type of the object's underlying value due to polymorphism.
         * @param value A reference to the value of {@code this}.
         */
        public record ThisObject(
                String typeName,
                TraceValue.Reference value) {
        } // ThisObject
    } // StackSnapshot

    /**
     * A key-value pair of identifier to the value it refers to.
     *
     * @param isFinal True if this field is declared as final, false otherwise.
     * @param typeName The name of this field's type. Note that this may differ from the type of the
     *     field's underlying value due to polymorphism.
     * @param identifier The field's identifier.
     * @param value The field's value.
     */
    public record Field(
            boolean isFinal,
            String typeName,
            String identifier,
            TraceValue value) {
    } // Field
} // ExecutionSnapshot
