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
 * @param stdout Bytes that have been output by the program to stdout up to the snapshot point.
 * @param stderr Bytes that have been output by the program to stderr up to the snapshot point.
 * @param sourcePath Optional relative source file path for the currently executing line.
 */
public record ExecutionSnapshot(
        List<StackSnapshot> stack,
        List<Field> statics,
        Map<Long, TraceValue> heap,
        byte[] stdout,
        byte[] stderr,
        Optional<String> sourcePath) {

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
        this(stack, statics, heap, stdout, stderr, Optional.empty());
    } // ExecutionSnapshot

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
