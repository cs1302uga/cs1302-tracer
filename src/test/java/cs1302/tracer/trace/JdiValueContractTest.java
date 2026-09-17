package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import com.sun.jdi.*;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JdiValueContractTest {
    static <T> T mirror(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (self, method, arguments) -> {
                    if (method.getName().equals("toString")) return type.getSimpleName();
                    if (method.getName().equals("equals")) return self == arguments[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                    Object value = values.get(method.getName());
                    if (value instanceof Throwable failure) throw failure;
                    return value;
                }));
    }

    static Stream<Arguments> primitiveValues() {
        return Stream.of(Arguments.of(BooleanValue.class, true), Arguments.of(ByteValue.class, (byte) 4),
                Arguments.of(CharValue.class, 'é'), Arguments.of(ShortValue.class, (short) 7),
                Arguments.of(IntegerValue.class, 42), Arguments.of(LongValue.class, 99L),
                Arguments.of(FloatValue.class, 1.25f), Arguments.of(DoubleValue.class, 2.5));
    }

    @ParameterizedTest
    @MethodSource("primitiveValues")
    void primitiveMirrorsPreserveTheirBoxedValues(Class<? extends PrimitiveValue> type, Object value) {
        var primitive = mirror(type, Map.of("value", value));
        assertThat(((TraceValue.Primitive) TraceValue.fromJdiValue(null, primitive, Optional.empty()))
                .toWrapperObject()).isEqualTo(value);
        assertThat(TraceValue.Primitive.tryFromJdiValue(null, primitive).orElseThrow()
                .toWrapperObject()).isEqualTo(value);
    }

    @Test
    void unknownMirrorsFailExplicitlyRatherThanBecomingNull() {
        assertThatThrownBy(() -> TraceValue.fromJdiValue(null, mirror(Value.class, Map.of()),
                Optional.empty())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unrecognized value");
        assertThatThrownBy(() -> TraceValue.Primitive.fromJdiPrimitive(
                mirror(PrimitiveValue.class, Map.of()))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown primitive");
        assertThat(TraceValue.Primitive.tryFromJdiValue(null, null)).isEmpty();
        assertThat(TraceValue.Primitive.tryFromJdiValue(null,
                mirror(ObjectReference.class, Map.of("type", mirror(ArrayType.class, Map.of())))))
                .isEmpty();
    }

    @Test
    void missingWrapperGetterIsReported() {
        var type = mirror(ClassType.class, Map.of("signature", "Ljava/lang/Integer;"));
        var object = mirror(ObjectReference.class, Map.of("type", type));
        assertThatThrownBy(() -> TraceValue.Primitive.tryFromJdiValue(null, object))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("intValue");
    }

    static Stream<Arguments> getterFailures() {
        return Stream.of(new IllegalArgumentException("bad argument"), new InvalidTypeException(),
                new ClassNotLoadedException("Missing"), new InvocationException(
                        mirror(ObjectReference.class, Map.of())), new IncompatibleThreadStateException())
                .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("getterFailures")
    void wrapperGetterFailuresKeepTheirCause(Exception failure) {
        var type = mirror(ClassType.class, Map.of("signature", "Ljava/lang/Integer;",
                "concreteMethodByName", mirror(com.sun.jdi.Method.class, Map.of())));
        var object = mirror(ObjectReference.class, Map.of("type", type, "invokeMethod", failure));
        assertThatThrownBy(() -> TraceValue.Primitive.tryFromJdiValue(null, object))
                .isInstanceOf(failure instanceof IncompatibleThreadStateException
                        ? IllegalArgumentException.class : IllegalStateException.class)
                .hasCause(failure);
    }
}
