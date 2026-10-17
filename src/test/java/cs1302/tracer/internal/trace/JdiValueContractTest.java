package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;
import com.sun.jdi.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JdiValueContractTest {
    static <T> T mirror(Class<T> type, Map<String, Object> values) {
        return type.cast(
                Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (self, method, arguments) -> {
                    if (method.getName().equals("toString"))
                        return type.getSimpleName();
                    if (method.getName().equals("equals"))
                        return self == arguments[0];
                    if (method.getName().equals("hashCode"))
                        return System.identityHashCode(self);
                    Object value = values.get(method.getName());
                    if (value instanceof Throwable failure)
                        throw failure;
                    return value;
                }));
    }

    static TraceValue inspect(ObjectReference object, ReifiedTypeMetadata metadata) {
        var inspection = new HeapInspection(null, metadata);
        inspection.encounter(object);
        return inspection.finish().get(object.uniqueID());
    }

    static TraceValue inspect(ObjectReference object) {
        return inspect(object, new ReifiedTypeMetadata(null));
    }

    static Stream<Arguments> primitiveValues() {
        return Stream.of(Arguments.of(BooleanValue.class, true, "Boolean"), Arguments.of(ByteValue.class, (byte) 4, "Byte"),
                Arguments.of(CharValue.class, 'é', "Character"), Arguments.of(ShortValue.class, (short) 7, "Short"),
                Arguments.of(IntegerValue.class, 42, "Integer"), Arguments.of(LongValue.class, 99L, "Long"),
                Arguments.of(FloatValue.class, 1.25f, "Float"), Arguments.of(DoubleValue.class, 2.5, "Double"));
    }

    private static ObjectReference wrapper(String name, Object accessor) {
        var attributes = new java.util.HashMap<String, Object>(Map.of("name", "java.lang." + name,
                "signature", "Ljava/lang/" + name + ";", "allInterfaces", List.of(), "isEnum", false));
        if (accessor != null)
            attributes.put("concreteMethodByName", accessor);
        var type = mirror(ClassType.class, attributes);
        return mirror(ObjectReference.class, Map.of("uniqueID", 1L, "referenceType", type, "type", type));
    }

    @ParameterizedTest
    @MethodSource("primitiveValues")
    void primitiveAndWrapperMirrorsPreserveTheirBoxedValues(Class<? extends PrimitiveValue> type, Object value,
            String wrapperName) {
        var primitive = mirror(type, Map.of("value", value));
        assertThat(HeapInspection.primitive(primitive).toWrapperObject()).isEqualTo(value);
        var wrapper = wrapper(wrapperName, mirror(com.sun.jdi.Method.class, Map.of()));
        var boxed = mirror(ObjectReference.class, Map.of("uniqueID", 1L, "referenceType", wrapper.referenceType(),
                "type", wrapper.type(), "invokeMethod", primitive));
        assertThat(((TraceValue.Primitive) inspect(boxed)).toWrapperObject()).isEqualTo(value);
    }

    static Stream<Arguments> booleanValues() {
        return Stream.of(Arguments.of(true, TraceValue.Primitive.Boolean.TRUE),
                Arguments.of(false, TraceValue.Primitive.Boolean.FALSE));
    }

    @ParameterizedTest
    @MethodSource("booleanValues")
    void booleanMirrorsReuseSharedValues(boolean value, TraceValue.Primitive.Boolean expected) {
        var primitive = mirror(BooleanValue.class, Map.of("value", value));
        assertThat(HeapInspection.primitive(primitive)).isSameAs(expected);
        var wrapper = wrapper("Boolean", mirror(com.sun.jdi.Method.class, Map.of()));
        var boxed = mirror(ObjectReference.class, Map.of("uniqueID", 1L, "referenceType", wrapper.referenceType(),
                "type", wrapper.type(), "invokeMethod", primitive));
        assertThat(inspect(boxed)).isSameAs(expected);
    }

    @Test
    void unknownPrimitiveMirrorsFailExplicitlyRatherThanBecomingNull() {
        assertThatThrownBy(() -> HeapInspection.primitive(mirror(PrimitiveValue.class, Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown primitive");
    }

    @Test
    void missingWrapperGetterIsReported() {
        assertThatThrownBy(() -> inspect(wrapper("Integer", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("intValue");
    }

    static Stream<Arguments> getterFailures() {
        return Stream.of(new IllegalArgumentException("bad argument"), new InvalidTypeException(),
                new ClassNotLoadedException("Missing"),
                new InvocationException(mirror(ObjectReference.class, Map.of())),
                new IncompatibleThreadStateException()).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("getterFailures")
    void wrapperGetterFailuresKeepTheirCause(Exception failure) {
        var wrapper = wrapper("Integer", mirror(com.sun.jdi.Method.class, Map.of()));
        var object = mirror(ObjectReference.class, Map.of("uniqueID", 1L, "referenceType", wrapper.referenceType(),
                "type", wrapper.type(), "invokeMethod", failure));
        assertThatThrownBy(() -> inspect(object))
                .isInstanceOf(failure instanceof IncompatibleThreadStateException ? IllegalArgumentException.class
                        : IllegalStateException.class)
                .hasCause(failure);
    }
}
