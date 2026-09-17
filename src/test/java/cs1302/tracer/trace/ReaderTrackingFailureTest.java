package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.*;
import com.sun.jdi.event.MethodExitEvent;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReaderTrackingFailureTest {
    private static final AtomicLong IDS = new AtomicLong();

    private static ObjectReference object(Map<String, Value> fields) {
        var type = (ReferenceType) Proxy.newProxyInstance(ReferenceType.class.getClassLoader(),
                new Class<?>[] {ReferenceType.class}, (self, method, args) -> {
                    if (method.getName().equals("fieldByName")) return fields.containsKey(args[0])
                            ? mirror(Field.class, Map.of("name", args[0])) : null;
                    return null;
                });
        long id = IDS.incrementAndGet();
        return (ObjectReference) Proxy.newProxyInstance(ObjectReference.class.getClassLoader(),
                new Class<?>[] {ObjectReference.class}, (self, method, args) -> switch (method.getName()) {
                    case "referenceType" -> type;
                    case "uniqueID" -> id;
                    case "equals" -> self == args[0];
                    case "getValue" -> fields.get(((Field) args[0]).name());
                    default -> null;
                });
    }

    private static IntegerValue number(int n) { return mirror(IntegerValue.class, Map.of("value", n)); }

    private static MethodExitEvent event(String owner, String name, ObjectReference reader,
            Value result, List<String> arguments) {
        var method = mirror(com.sun.jdi.Method.class, Map.of("name", name,
                "declaringType", mirror(ReferenceType.class, Map.of("name", owner)),
                "argumentTypeNames", arguments));
        var frame = mirror(StackFrame.class, reader == null ? Map.of() : Map.of("thisObject", reader));
        var thread = mirror(ThreadReference.class, Map.of("frames", List.of(frame)));
        var values = new HashMap<String, Object>(Map.of("method", method, "thread", thread));
        if (result != null) values.put("returnValue", result);
        return mirror(MethodExitEvent.class, values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"matcher", "buf", "hb", "offset", "first", "last"})
    void unsupportedScannerLayoutsDoNotInventConsumption(String missing) throws Exception {
        var input = object(Map.of());
        var match = new HashMap<String, Value>(Map.of("first", number(0), "last", number(1)));
        var buffer = new HashMap<String, Value>(Map.of("offset", number(0), "hb",
                mirror(ArrayReference.class, Map.of("length", 1))));
        match.remove(missing);
        buffer.remove(missing);
        var fields = new HashMap<String, Value>(Map.of("source", input,
                "matcher", object(match), "buf", object(buffer)));
        fields.remove(missing);
        var tracker = new InputTracker("1");
        ReaderTracking.record(event("java.util.Scanner", "nextInt", object(fields), number(1), List.of()),
                tracker, input);
        assertThat(tracker.offset()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2, 3})
    void invalidScannerMatchRangesDoNotInventConsumption(int first) throws Exception {
        var input = object(Map.of());
        var buffer = object(Map.of("offset", number(0), "hb", mirror(ArrayReference.class,
                Map.of("length", 1))));
        var scanner = object(Map.of("source", input, "buf", buffer, "matcher",
                object(Map.of("first", number(first), "last", number(first == 2 ? 1 : first + 1)))));
        var tracker = new InputTracker("1");
        ReaderTracking.record(event("java.util.Scanner", "nextInt", scanner, number(1), List.of()), tracker, input);
        assertThat(tracker.offset()).isZero();
    }

    @Test
    void cyclicAndExcessivelyDeepDelegateChainsStopInspection() throws Exception {
        var input = object(Map.of());
        var fields = new HashMap<String, Value>();
        var cycle = object(fields);
        fields.put("source", cycle);
        ObjectReference deep = input;
        for (int i = 0; i < 40; i++) deep = object(Map.of("source", deep));
        for (var reader : List.of(cycle, deep)) {
            var tracker = new InputTracker("1");
            ReaderTracking.record(event("java.util.Scanner", "next", reader,
                    mirror(StringReference.class, Map.of("value", "1")), List.of()), tracker, input);
            assertThat(tracker.offset()).isZero();
        }
    }

    @Test
    void eofAndUnavailableInputDoNotAdvanceCursor() throws Exception {
        var input = object(Map.of());
        var tracker = new InputTracker("abc");
        ReaderTracking.record(event("java.io.InputStream", "read", input, number(-1), List.of()), tracker, input);
        ReaderTracking.record(event("java.io.BufferedReader", "readLine", input, null, List.of()), tracker, input);
        ReaderTracking.record(event("java.util.Scanner", "nextInt", input, null, List.of()), tracker, input);
        ReaderTracking.record(event("java.util.Scanner", "next", input, number(1), List.of()), tracker, null);
        ReaderTracking.record(event("java.util.Scanner", "next", null, number(1), List.of()), tracker, input);
        assertThat(tracker.offset()).isZero();
        ReaderTracking.record(event("java.io.InputStream", "read", input, number(2), List.of("byte[]")), tracker, input);
        assertThat(tracker.consumed()).isEqualTo("ab");
    }

    @Test
    void onlyRecognizedSuccessfulReaderOperationsAdvanceInput() throws Exception {
        var input = object(Map.of());
        var wrapper = object(Map.of("in", input));
        var text = mirror(StringReference.class, Map.of("value", "abc"));
        var tracker = new InputTracker("abc");
        ReaderTracking.record(event("java.lang.IO", "println", null, text, List.of()), tracker, input);
        ReaderTracking.record(event("java.util.Scanner", "hasNext", wrapper, number(1), List.of()), tracker, input);
        ReaderTracking.record(event("java.io.InputStream", "read", wrapper, number(1), List.of()), tracker, input);
        ReaderTracking.record(event("java.lang.IO", "read", null, text, List.of()), tracker, input);
        ReaderTracking.record(event("java.io.InputStream", "read", input, text, List.of()), tracker, input);
        assertThat(tracker.offset()).isZero();
        ReaderTracking.record(event("java.util.Scanner", "findInLine", wrapper, text, List.of()), tracker, input);
        assertThat(tracker.consumed()).isEqualTo("abc");
    }

    @Test
    void scannerLineReadConsumesTheLineTerminator() throws Exception {
        var input = object(Map.of());
        var tracker = new InputTracker("abc\nrest");
        ReaderTracking.record(event("java.util.Scanner", "nextLine", object(Map.of("source", input)),
                mirror(StringReference.class, Map.of("value", "abc")), List.of()), tracker, input);
        assertThat(tracker.consumed()).isEqualTo("abc\n");
    }
}
