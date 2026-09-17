package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.sun.jdi.*;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerInspectionFailureTest {
    static Stream<Arguments> failures() {
        return Stream.of(new InvalidTypeException(), new ClassNotLoadedException("Missing"),
                new IllegalArgumentException("bad invocation"), new IncompatibleThreadStateException(),
                new InvocationException(mirror(ObjectReference.class, Map.of())))
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("failures")
    void mapAccessorFailuresAreClassified(Exception failure) {
        var contract = mirror(InterfaceType.class, Map.of());
        var type = mirror(ClassType.class, Map.of("allInterfaces", List.of(contract),
                "concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of())));
        var vm = mirror(VirtualMachine.class, Map.of("classesByName", List.of(contract)));
        var object = mirror(ObjectReference.class, Map.of("referenceType", type,
                "virtualMachine", vm, "invokeMethod", failure));
        if (failure instanceof InvocationException) {
            assertThat(TraceValue.Map.tryFromJdiObjectReference(null, object, Optional.empty())).isEmpty();
        } else {
            assertThatThrownBy(() -> TraceValue.Map.tryFromJdiObjectReference(null, object, Optional.empty()))
                    .isInstanceOf(failure instanceof IncompatibleThreadStateException
                            ? IllegalArgumentException.class : IllegalStateException.class).hasCause(failure);
        }
    }

    @ParameterizedTest
    @MethodSource("failures")
    void collectionAccessorFailuresAreClassified(Exception failure) throws Exception {
        var method = TraceValue.class.getDeclaredMethod("handleCollection", ThreadReference.class,
                ClassType.class, ObjectReference.class, Optional.class, AstTypeResolver.class, Map.class);
        method.setAccessible(true);
        var type = mirror(ClassType.class, Map.of("concreteMethodByName",
                mirror(com.sun.jdi.Method.class, Map.of())));
        var object = mirror(ObjectReference.class, Map.of("invokeMethod", failure));
        if (failure instanceof InvocationException) {
            assertThat(method.invoke(null, null, type, object, Optional.empty(), null, null))
                    .isEqualTo(Optional.empty());
        } else {
            assertThatThrownBy(() -> method.invoke(null, null, type, object, Optional.empty(), null, null))
                    .isInstanceOf(InvocationTargetException.class)
                    .cause().isInstanceOf(failure instanceof IncompatibleThreadStateException
                            ? IllegalArgumentException.class : IllegalStateException.class).hasCause(failure);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "getter", "missing", "wrong-result", "throws", "fields-only"})
    void colorInspectionUsesFieldsOrFallsBackPredictably(String mode) {
        var attributes = new java.util.HashMap<String, Object>(Map.of("name", "java.awt.Color",
                "signature", "Ljava/awt/Color;", "allInterfaces", List.of()));
        if (mode.equals("field")) attributes.put("fieldByName", mirror(Field.class, Map.of()));
        if (!mode.equals("missing")) attributes.put("concreteMethodByName",
                mirror(com.sun.jdi.Method.class, Map.of()));
        var type = mirror(ClassType.class, attributes);
        var primitive = mirror(IntegerValue.class, Map.of("intValue", 0xff123456));
        Object invocation = switch (mode) {
            case "throws" -> new IllegalArgumentException("getter failed");
            case "fields-only" -> new AssertionError("FIELDS must not invoke guest code");
            case "wrong-result" -> mirror(Value.class, Map.of());
            default -> primitive;
        };
        var object = mirror(ObjectReference.class, Map.of("type", type, "referenceType", type,
                "getValue", primitive, "invokeMethod", invocation,
                "virtualMachine", mirror(VirtualMachine.class, Map.of("classesByName", List.of()))));
        try (var session = new cs1302.tracer.execution.TraceSession(
                cs1302.tracer.execution.TraceLimits.unlimited(), mode.equals("fields-only")
                        ? cs1302.tracer.execution.InspectionPolicy.FIELDS
                        : cs1302.tracer.execution.InspectionPolicy.TRUSTED, true)) {
            assertThat(cs1302.tracer.execution.TraceSession.current()).isSameAs(session);
            var result = (TraceValue.Color) TraceValue.fromJdiValue(null, object, Optional.empty());
            assertThat(result.hex()).isEqualTo(mode.equals("field") || mode.equals("getter")
                    ? "#123456" : "#00000000");
        }
    }
}
