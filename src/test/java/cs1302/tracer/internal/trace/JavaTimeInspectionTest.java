package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;

import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VMDisconnectedException;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaTimeInspectionTest {
    @ParameterizedTest
    @ValueSource(strings = { "missing", "static", "signature", "unknown" })
    void unsupportedLayoutsFallBackWithoutReadingValues(String mode) {
        var field = mirror(Field.class, Map.of("isStatic", mode.equals("static"),
                "signature", mode.equals("signature") ? "I" : "J"));
        var type = mirror(ReferenceType.class, mode.equals("missing")
                ? Map.of("name", "java.time.Duration")
                : Map.of("name", mode.equals("unknown") ? "java.time.Clock" : "java.time.Duration",
                        "fieldByName", field));
        assertThat(JavaTimeInspection.fields(type)).isEmpty();
    }

    @Test
    void aLaterMissingComponentRejectsTheWholeLayout() {
        var seconds = mirror(Field.class, Map.of("isStatic", false, "signature", "J"));
        var type = (ReferenceType) Proxy.newProxyInstance(ReferenceType.class.getClassLoader(),
                new Class<?>[] { ReferenceType.class }, (self, method, args) -> switch (method.getName()) {
                case "name" -> "java.time.Duration";
                case "fieldByName" -> args[0].equals("seconds") ? seconds : null;
                default -> throw new AssertionError(method);
                });
        assertThat(JavaTimeInspection.fields(type)).isEmpty();
    }

    @Test
    void disconnectionIsNotMistakenForAnUnsupportedLayout() {
        var failure = new VMDisconnectedException();
        var type = mirror(ReferenceType.class, Map.of("name", "java.time.Duration", "fieldByName", failure));
        assertThatThrownBy(() -> JavaTimeInspection.fields(type)).isSameAs(failure);
    }
    @Test
    void durationFieldsChargeBeforeReadsAndNeverInvokeGuestMethods() {
        var seconds = mirror(Field.class, Map.of("name", "seconds", "typeName", "long",
                "signature", "J", "isStatic", false, "isFinal", true));
        var nanos = mirror(Field.class, Map.of("name", "nanos", "typeName", "int",
                "signature", "I", "isStatic", false, "isFinal", true));
        var type = (ReferenceType) Proxy.newProxyInstance(ReferenceType.class.getClassLoader(),
                new Class<?>[] { ReferenceType.class }, (self, method, args) -> switch (method.getName()) {
                case "name" -> "java.time.Duration";
                case "fieldByName" -> args[0].equals("seconds") ? seconds : nanos;
                default -> throw new AssertionError(method);
                });
        var reads = new java.util.ArrayList<String>();
        var object = (com.sun.jdi.ObjectReference) Proxy.newProxyInstance(ReferenceType.class.getClassLoader(),
                new Class<?>[] { com.sun.jdi.ObjectReference.class }, (self, method, args) -> switch (method.getName()) {
                case "uniqueID" -> 1L;
                case "referenceType", "type" -> type;
                case "getValue" -> {
                    String name = ((Field) args[0]).name();
                    reads.add(name);
                    yield name.equals("seconds") ? mirror(com.sun.jdi.LongValue.class, Map.of("value", -1L))
                            : mirror(com.sun.jdi.IntegerValue.class, Map.of("value", 999999999));
                }
                default -> throw new AssertionError("Unexpected guest operation: " + method);
                });
        for (int cap : new int[] {1, 2}) {
            reads.clear();
            try (var session = new cs1302.tracer.internal.execution.TraceSession(
                    cs1302.tracer.api.TraceLimits.unlimited().toBuilder().elements(cap).build(),
                    cs1302.tracer.api.InspectionPolicy.TRUSTED, true)) {
                session.beginSnapshot();
                if (cap == 1) {
                    assertThatThrownBy(() -> JdiValueContractTest.inspect(object))
                            .isInstanceOf(cs1302.tracer.internal.execution.TraceSession.Stopped.class);
                    assertThat(session.stopReason()).isEqualTo("element_limit");
                    assertThat(reads).isEmpty();
                    assertThat(session.snapshots()).isEmpty();
                } else {
                    var value = (TraceValue.Object) JdiValueContractTest.inspect(object);
                    assertThat(value.fields()).extracting(ExecutionSnapshot.Field::value)
                            .containsExactly(new TraceValue.Primitive.Long(-1), new TraceValue.Primitive.Integer(999999999));
                    assertThat(reads).containsExactly("seconds", "nanos");
                }
            }
        }
    }

}
