package cs1302.tracer.trace;

import java.util.List;
import java.util.Map;

/**
 * A snapshot of a program's memory state.
 *
 * @param stack The program's stack. Index 0 is the bottommost frame, the last index is the topmost.
 * @param statics Loaded static variables.
 * @param heap The program's heap, a mapping of reference IDs to values.
 */
public record ExecutionSnapshot(List<StackSnapshot> stack,
                                List<Field> statics, Map<Long, TraceValue> heap) {

    /**
     * A snapshot of the state of a method's stack.
     *
     * @param methodName The name of the method this frame is associated with.
     * @param methodLine The line number this snapshot was taken at.
     * @param visibleVariables The stack variables that are accessible in this method at line
     *                         methodLine.
     */
    public record StackSnapshot(String methodName, long methodLine,
                                List<Field> visibleVariables) {
    }

    /**
     * A key-value pair of identifier to the value it refers to.
     *
     * @param identifier The field's identifier.
     * @param value The field's value.
     * @param typeName The name of this field's type. Note that this may differ from the type
     *                 of the field's underlying value due to polymorphism.
     */
    public record Field(String typeName, String identifier, TraceValue value) {
    }
}
