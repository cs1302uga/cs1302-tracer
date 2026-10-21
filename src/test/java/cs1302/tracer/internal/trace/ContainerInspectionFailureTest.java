package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.internal.trace.JdiValueContractTest.mirror;
import static cs1302.tracer.internal.trace.JdiValueContractTest.inspect;
import com.sun.jdi.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerInspectionFailureTest {
    static Stream<Arguments> failures() {
        return Stream.of(new InvalidTypeException(), new ClassNotLoadedException("Missing"),
                new IllegalArgumentException("bad invocation"), new IncompatibleThreadStateException(),
                new InvocationException(mirror(ObjectReference.class, Map.of()))).map(Arguments::of);
    }

    private static ObjectReference container(String kind, Exception failure) {
        return container(kind, failure, List.of());
    }

    private static ObjectReference container(String kind, Exception failure,
            List<com.sun.jdi.request.ExceptionRequest> requests) {
        var contract = mirror(InterfaceType.class, Map.of());
        var name = "java.util.Test" + kind;
        var type = mirror(ClassType.class, Map.of("name", name, "signature", "L" + name.replace('.', '/') + ";",
                "allInterfaces", List.of(contract), "concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of()),
                "isEnum", false));
        var vm = (VirtualMachine) Proxy.newProxyInstance(VirtualMachine.class.getClassLoader(),
                new Class<?>[] { VirtualMachine.class }, (self, method, args) ->
                        method.getName().equals("eventRequestManager")
                                ? mirror(com.sun.jdi.request.EventRequestManager.class,
                                        Map.of("exceptionRequests", requests, "breakpointRequests", List.of(),
                                                "methodExitRequests", List.of()))
                                : method.getName().equals("classesByName") && args[0].equals("java.util." + kind)
                                ? List.of(contract) : List.of());
        var attributes = new java.util.HashMap<String, Object>(Map.of("uniqueID", 1L, "referenceType", type,
                "type", type, "virtualMachine", vm));
        if (failure != null) attributes.put("invokeMethod", failure);
        return mirror(ObjectReference.class, attributes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "throws", "disconnect"})
    void collectionInvocationRestoresOnlyPreviouslyEnabledExceptionRequests(String mode) {
        var calls = new java.util.ArrayList<String>();
        var requestType = com.sun.jdi.request.ExceptionRequest.class;
        var enabled = (com.sun.jdi.request.ExceptionRequest) Proxy.newProxyInstance(requestType.getClassLoader(),
                new Class<?>[] {requestType}, (self, method, args) -> {
                    if (method.getName().equals("isEnabled")) return true;
                    calls.add(method.getName());
                    return null;
                });
        var disabled = (com.sun.jdi.request.ExceptionRequest) Proxy.newProxyInstance(requestType.getClassLoader(),
                new Class<?>[] {requestType}, (self, method, args) -> {
                    assertThat(method.getName()).isEqualTo("isEnabled");
                    return false;
                });
        Exception failure = switch (mode) {
            case "throws" -> new InvocationException(mirror(ObjectReference.class, Map.of()));
            case "disconnect" -> new VMDisconnectedException();
            default -> null;
        };
        var object = container("Collection", failure, List.of(enabled, disabled));
        if (mode.equals("disconnect")) assertThatThrownBy(() -> inspect(object)).isSameAs(failure);
        else assertThat(inspect(object)).isEqualTo(new TraceValue.Object("java.util.TestCollection", List.of()));
        assertThat(calls).containsExactly("disable", "enable");
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapAccessorFailuresAreClassifiedThroughHeapInspection(Exception failure) {
        assertFailureOrFallback("Map", failure);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void collectionAccessorFailuresAreClassifiedThroughHeapInspection(Exception failure) {
        assertFailureOrFallback("Collection", failure);
    }

    private static void assertFailureOrFallback(String kind, Exception failure) {
        var object = container(kind, failure);
        if (failure instanceof InvocationException) {
            assertThat(inspect(object)).isEqualTo(new TraceValue.Object("java.util.Test" + kind, List.of()));
        } else {
            assertThatThrownBy(() -> inspect(object))
                    .isInstanceOf(failure instanceof IncompatibleThreadStateException ? IllegalArgumentException.class
                            : IllegalStateException.class)
                    .hasCause(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "field", "getter", "missing", "wrong-result", "throws", "fields-only" })
    void colorInspectionUsesFieldsOrFallsBackPredictably(String mode) {
        var attributes = new java.util.HashMap<String, Object>(
                Map.of("name", "java.awt.Color", "signature", "Ljava/awt/Color;", "allInterfaces", List.of()));
        if (mode.equals("field"))
            attributes.put("fieldByName", mirror(Field.class, Map.of()));
        if (!mode.equals("missing"))
            attributes.put("concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of()));
        var type = mirror(ClassType.class, attributes);
        var primitive = mirror(IntegerValue.class, Map.of("intValue", 0xff123456));
        Object invocation = switch (mode) {
        case "throws" -> new IllegalArgumentException("getter failed");
        case "fields-only" -> new AssertionError("FIELDS must not invoke guest code");
        case "wrong-result" -> mirror(Value.class, Map.of());
        default -> primitive;
        };
        var object = mirror(ObjectReference.class,
                Map.of("uniqueID", 1L, "type", type, "referenceType", type, "getValue", primitive,
                        "invokeMethod", invocation, "virtualMachine", mirror(VirtualMachine.class,
                                Map.of("classesByName", List.of()))));
        try (var session = new cs1302.tracer.internal.execution.TraceSession(cs1302.tracer.api.TraceLimits.unlimited(),
                mode.equals("fields-only") ? cs1302.tracer.api.InspectionPolicy.FIELDS
                        : cs1302.tracer.api.InspectionPolicy.TRUSTED,
                true)) {
            assertThat(cs1302.tracer.internal.execution.TraceSession.current()).isSameAs(session);
            var result = (TraceValue.Color) inspect(object);
            assertThat(result.hex()).isEqualTo(mode.equals("field") || mode.equals("getter") ? "#123456" : "#00000000");
        }
    }
}
