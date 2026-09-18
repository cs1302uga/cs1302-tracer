package cs1302.tracer.trace;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A captured state whose output may remain in shared internal storage. */
public interface Snapshot {

    /**
     * Returns state metadata; internal captures omit output from this record.
     * @return State metadata; internal captures omit output from this record.
     */
    ExecutionSnapshot metadata();

    /**
     * Returns cumulative standard output bytes.
     * @return Cumulative standard output bytes.
     */
    byte[] stdout();

    /**
     * Returns cumulative sanitized standard error bytes.
     * @return Cumulative sanitized standard error bytes.
     */
    byte[] stderr();

    /**
     * Returns captured call stack.
     * @return Captured call stack.
     */
    default List<ExecutionSnapshot.StackSnapshot> stack() {
        return metadata().stack();
    } // stack

    /**
     * Returns captured static fields.
     * @return Captured static fields.
     */
    default List<ExecutionSnapshot.Field> statics() {
        return metadata().statics();
    } // statics

    /**
     * Returns captured heap.
     * @return Captured heap.
     */
    default Map<Long, TraceValue> heap() {
        return metadata().heap();
    } // heap

    /**
     * Returns current source path.
     * @return Current source path.
     */
    default Optional<String> sourcePath() {
        return metadata().sourcePath();
    } // sourcePath

    /**
     * Returns consumed input text.
     * @return Consumed input text.
     */
    default String stdinConsumed() {
        return metadata().stdinConsumed();
    } // stdinConsumed

    /**
     * Returns consumed input offset.
     * @return Consumed input offset.
     */
    default int stdinOffset() {
        return metadata().stdinOffset();
    } // stdinOffset

    /**
     * Returns public snapshot with independent output arrays for an internal capture.
     * @return Public snapshot with independent output arrays for an internal capture.
     */
    default ExecutionSnapshot materialize() {
        return new ExecutionSnapshot(stack(), statics(), heap(), stdout(), stderr(),
                sourcePath(), stdinConsumed(), stdinOffset());
    } // materialize
} // Snapshot
