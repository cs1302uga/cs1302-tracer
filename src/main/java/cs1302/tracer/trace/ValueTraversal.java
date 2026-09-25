package cs1302.tracer.trace;

import cs1302.tracer.execution.NestingException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Iterative traversal of inline values; guest heap references remain leaves. */
public final class ValueTraversal {
    /** Maximum inline value path length, including its root and leaf. */
    public static final int MAX_DEPTH = 32;

    /** Prevents utility construction. */
    private ValueTraversal() {} // ValueTraversal

    /**
     * Checks every value root before snapshot accounting or conversion.
     * @param snapshot Snapshot owned by the caller; must not be mutated during serialization.
     */
    public static void validate(ExecutionSnapshot snapshot) {
        snapshot.heap().values().forEach(ValueTraversal::validateValue);
        snapshot.statics().forEach(f -> validateValue(f.value()));
        snapshot.stack().forEach(ValueTraversal::validateFrame);
        if (snapshot.threads() != null) {
            snapshot.threads().forEach(t -> t.stack().forEach(ValueTraversal::validateFrame));
        } // if
    } // validate

    /**
     * Checks frame locals. The this-object is always a leaf reference.
     * @param frame Stack frame.
     */
    private static void validateFrame(ExecutionSnapshot.StackSnapshot frame) {
        frame.visibleVariables().forEach(f -> validateValue(f.value()));
    } // validateFrame

    /**
     * Checks one inline value tree.
     * @param value Root value.
     */
    private static void validateValue(TraceValue value) {
        visit(value, ignored -> { });
    } // validateValue

    /**
     * Visits children before their parent, rejecting deep paths and identity cycles.
     * Shared values are visited once; cached heights check each incoming path.
     * Record hashCode is never invoked.
     * @param root Root value, possibly null.
     * @param visitor Postorder consumer.
     */
    public static void visit(TraceValue root, Consumer<TraceValue> visitor) {
        Set<TraceValue> active = Collections.newSetFromMap(new IdentityHashMap<>());
        var heights = new IdentityHashMap<TraceValue, Integer>();
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(root, null));
        while (!stack.isEmpty()) {
            Frame frame = stack.pop();
            if (frame.children() == null) {
                Integer height = heights.get(frame.value());
                if (height != null) {
                    if (stack.size() + height > MAX_DEPTH) {
                        throw new NestingException("value_nesting_limit");
                    } // if
                    continue;
                } // if
                if (!active.add(frame.value())) {
                    throw new NestingException("inline_value_cycle");
                } // if
                if (stack.size() >= MAX_DEPTH) {
                    throw new NestingException("value_nesting_limit");
                } // if
                frame = new Frame(frame.value(), children(frame.value()));
            } // if
            if (frame.children().hasNext()) {
                stack.push(frame);
                stack.push(new Frame(frame.children().next(), null));
            } else {
                int height = 1;
                var children = children(frame.value());
                while (children.hasNext()) {
                    height = Math.max(height, heights.get(children.next()) + 1);
                } // while
                heights.put(frame.value(), height);
                visitor.accept(frame.value());
                active.remove(frame.value());
            } // if
        } // while
    } // visit

    /**
     * Returns inline children without following heap references or copying wide containers.
     * @param value Value to inspect.
     * @return Child iterator.
     */
    private static Iterator<? extends TraceValue> children(TraceValue value) {
        return switch (value) {
        case TraceValue.List list -> list.value().iterator();
        case TraceValue.Collection collection -> collection.value().iterator();
        case TraceValue.Object object -> object.fields().stream()
                .map(ExecutionSnapshot.Field::value).iterator();
        case TraceValue.Map map -> map.value().entrySet().stream()
                .flatMap(e -> Stream.<TraceValue>of(e.getKey(), e.getValue())).iterator();
        case null, default -> Collections.emptyIterator();
        }; // switch
    } // children

    /** Pending traversal state. */
    private record Frame(TraceValue value, Iterator<? extends TraceValue> children) {}
} // ValueTraversal
