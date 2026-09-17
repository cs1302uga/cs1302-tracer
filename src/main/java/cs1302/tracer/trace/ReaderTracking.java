package cs1302.tracer.trace;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import com.sun.jdi.event.MethodExitEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Observes logical reads without invoking methods in the guest. */
final class ReaderTracking {

    private static final Set<String> READERS = Set.of("java.util.Scanner",
            "java.io.BufferedReader", "java.lang.IO");

    /** Prevents construction. */
    private ReaderTracking() {} // ReaderTracking

    /**
     * Records one outermost read from the supplied standard input stream.
     * @param event Completed guest method.
     * @param tracker Logical input cursor.
     * @param systemIn Guest standard input identity.
     * @throws com.sun.jdi.IncompatibleThreadStateException If the guest is not suspended.
     */
    static void record(MethodExitEvent event, InputTracker tracker, ObjectReference systemIn)
            throws com.sun.jdi.IncompatibleThreadStateException {
        var frames = event.thread().frames();
        for (int i = 1; i < frames.size(); i++) {
            String caller = frames.get(i).location().declaringType().name();
            if (READERS.contains(caller)) {
                return;
            } // if
            if (caller.equals("java.io.BufferedInputStream")
                    && frames.get(i).location().method().name().equals("read")) {
                return;
            } // if
        } // for
        String owner = event.method().declaringType().name();
        String method = event.method().name();
        ObjectReference reader = frames.getFirst().thisObject();
        if (!owner.equals("java.lang.IO") && !backsOnto(reader, systemIn)) {
            return;
        } // if
        Value result = event.returnValue();
        if ((owner.equals("java.lang.IO") && method.equals("readln"))
                || (owner.equals("java.io.BufferedReader") && method.equals("readLine"))
                || (owner.equals("java.util.Scanner") && method.equals("nextLine"))) {
            if (result instanceof StringReference line) {
                tracker.consumeLine(line.value());
            } // if
            return;
        } // if
        if (owner.equals("java.util.Scanner")
                && (method.startsWith("next") || method.startsWith("find"))) {
            if (result instanceof StringReference token) {
                tracker.consumeToken(token.value());
                return;
            } // if
            if (result != null) {
                tracker.consumeToken(matchedToken(reader));
            } // if
            return;
        } // if
        if (method.equals("read") && reader != null && reader.equals(systemIn)
                && result instanceof IntegerValue count) {
            tracker.consumeBytes(event.method().argumentTypeNames().isEmpty()
                    ? (count.value() < 0 ? 0 : 1) : count.value());
        } // if
    } // record

    /**
     * Follows known reader delegate fields, with a bounded traversal and no guest calls.
     * @param reader Reader to inspect.
     * @param input Required input identity.
     * @return Whether the delegate chain reaches standard input.
     */
    private static boolean backsOnto(ObjectReference reader, ObjectReference input) {
        if (reader == null || input == null) {
            return false;
        } // if
        var pending = new ArrayDeque<ObjectReference>();
        var seen = new HashSet<Long>();
        pending.add(reader);
        while (!pending.isEmpty() && seen.size() < 32) {
            ObjectReference next = pending.removeFirst();
            if (next.equals(input)) {
                return true;
            } // if
            if (seen.add(next.uniqueID())) {
                for (String name : new String[] {"source", "in", "sd", "ch"}) {
                    if (field(next, name) instanceof ObjectReference delegate) {
                        pending.add(delegate);
                    } // if
                } // for
            } // if
        } // while
        return false;
    } // backsOnto

    /**
     * Reads the original numeric token, preserving radix, signs, and formatting.
     * @param scanner Guest Scanner.
     * @return Matched input, or null for an unsupported representation.
     */
    private static String matchedToken(ObjectReference scanner) {
        if (!(field(scanner, "matcher") instanceof ObjectReference matcher)
                || !(field(scanner, "buf") instanceof ObjectReference buffer)
                || !(field(buffer, "hb") instanceof ArrayReference chars)
                || !(field(buffer, "offset") instanceof IntegerValue offset)
                || !(field(matcher, "first") instanceof IntegerValue first)
                || !(field(matcher, "last") instanceof IntegerValue last)) {
            return null;
        } // if
        int start = offset.value() + first.value();
        int length = last.value() - first.value();
        if (start < 0 || length < 0 || start > chars.length() - length) {
            return null;
        } // if
        StringBuilder token = new StringBuilder();
        for (Value value : chars.getValues(start, length)) {
            token.append(((CharValue) value).value());
        } // for
        return token.toString();
    } // matchedToken

    /**
     * Reads an optional field without executing guest code.
     * @param object Guest object.
     * @param name Field name.
     * @return Field value, or null when absent.
     */
    private static Value field(ObjectReference object, String name) {
        Field field = object.referenceType().fieldByName(name);
        return field == null ? null : object.getValue(field);
    } // field
} // ReaderTracking
