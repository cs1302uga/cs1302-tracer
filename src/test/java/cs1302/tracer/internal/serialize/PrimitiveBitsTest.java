package cs1302.tracer.internal.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.internal.model.PrimitiveBinary;
import cs1302.tracer.internal.trace.TraceValue;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PrimitiveBitsTest {
    static Stream<Arguments> values() {
        return Stream.of(
            Arguments.of(new TraceValue.Primitive.Byte((byte) 0), "byte", "twos-complement", 8, "00"),
            Arguments.of(new TraceValue.Primitive.Byte((byte) -1), "byte", "twos-complement", 8, "ff"),
            Arguments.of(new TraceValue.Primitive.Byte(Byte.MIN_VALUE), "byte", "twos-complement", 8, "80"),
            Arguments.of(new TraceValue.Primitive.Byte(Byte.MAX_VALUE), "byte", "twos-complement", 8, "7f"),
            Arguments.of(new TraceValue.Primitive.Short((short) 0), "short", "twos-complement", 16, "0000"),
            Arguments.of(new TraceValue.Primitive.Short((short) -1), "short", "twos-complement", 16, "ffff"),
            Arguments.of(new TraceValue.Primitive.Short(Short.MIN_VALUE), "short", "twos-complement", 16, "8000"),
            Arguments.of(new TraceValue.Primitive.Short(Short.MAX_VALUE), "short", "twos-complement", 16, "7fff"),
            Arguments.of(new TraceValue.Primitive.Integer(0), "int", "twos-complement", 32, "00000000"),
            Arguments.of(new TraceValue.Primitive.Integer(-1), "int", "twos-complement", 32, "ffffffff"),
            Arguments.of(new TraceValue.Primitive.Integer(Integer.MIN_VALUE), "int", "twos-complement", 32, "80000000"),
            Arguments.of(new TraceValue.Primitive.Integer(Integer.MAX_VALUE), "int", "twos-complement", 32, "7fffffff"),
            Arguments.of(new TraceValue.Primitive.Long(0L), "long", "twos-complement", 64, "0000000000000000"),
            Arguments.of(new TraceValue.Primitive.Long(-1L), "long", "twos-complement", 64, "ffffffffffffffff"),
            Arguments.of(new TraceValue.Primitive.Long(Long.MIN_VALUE), "long", "twos-complement", 64, "8000000000000000"),
            Arguments.of(new TraceValue.Primitive.Long(Long.MAX_VALUE), "long", "twos-complement", 64, "7fffffffffffffff"),
            Arguments.of(new TraceValue.Primitive.Long(9007199254740993L), "long", "twos-complement", 64,
                "0020000000000001"),
            Arguments.of(new TraceValue.Primitive.Character('\0'), "char", "utf16-code-unit", 16, "0000"),
            Arguments.of(new TraceValue.Primitive.Character('A'), "char", "utf16-code-unit", 16, "0041"),
            Arguments.of(new TraceValue.Primitive.Character('\uffff'), "char", "utf16-code-unit", 16, "ffff"),
            Arguments.of(new TraceValue.Primitive.Character('\ud800'), "char", "utf16-code-unit", 16, "d800"),
            Arguments.of(new TraceValue.Primitive.Boolean(false), "boolean", "logical", 1, "0"),
            Arguments.of(new TraceValue.Primitive.Boolean(true), "boolean", "logical", 1, "1"),
            Arguments.of(new TraceValue.Primitive.Float(0.0f), "float", "ieee754-binary32", 32, "00000000"),
            Arguments.of(new TraceValue.Primitive.Float(-0.0f), "float", "ieee754-binary32", 32, "80000000"),
            Arguments.of(new TraceValue.Primitive.Float(1.5f), "float", "ieee754-binary32", 32, "3fc00000"),
            Arguments.of(new TraceValue.Primitive.Float(Float.MIN_VALUE), "float", "ieee754-binary32", 32, "00000001"),
            Arguments.of(new TraceValue.Primitive.Float(Float.POSITIVE_INFINITY), "float", "ieee754-binary32", 32,
                "7f800000"),
            Arguments.of(new TraceValue.Primitive.Float(Float.NEGATIVE_INFINITY), "float", "ieee754-binary32", 32,
                "ff800000"),
            Arguments.of(new TraceValue.Primitive.Float(Float.NaN), "float", "ieee754-binary32", 32, "7fc00000"),
            Arguments.of(new TraceValue.Primitive.Float(Float.intBitsToFloat(0x7fc12345)), "float", "ieee754-binary32", 32,
                "7fc12345"),
            Arguments.of(new TraceValue.Primitive.Double(0.0), "double", "ieee754-binary64", 64, "0000000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(-0.0), "double", "ieee754-binary64", 64, "8000000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(1.5), "double", "ieee754-binary64", 64, "3ff8000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(Double.MIN_VALUE), "double", "ieee754-binary64", 64,
                "0000000000000001"),
            Arguments.of(new TraceValue.Primitive.Double(Double.POSITIVE_INFINITY), "double", "ieee754-binary64", 64,
                "7ff0000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(Double.NEGATIVE_INFINITY), "double", "ieee754-binary64", 64,
                "fff0000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(Double.NaN), "double", "ieee754-binary64", 64, "7ff8000000000000"),
            Arguments.of(new TraceValue.Primitive.Double(Double.longBitsToDouble(0x7ff8123456789abcL)), "double",
                "ieee754-binary64", 64, "7ff8123456789abc")
        );
    }

    @ParameterizedTest
    @MethodSource("values")
    void preservesTypeWidthAndBits(TraceValue value, String type, String encoding, int width, String hex) {
        PrimitiveBinary binary = PrimitiveBits.of(value, true);
        assertThat(binary.type()).isEqualTo(type);
        assertThat(binary.encoding()).isEqualTo(encoding);
        assertThat(binary.bits()).hasSize(width).matches("[01]+");
        assertThat(Long.parseUnsignedLong(binary.bits(), 2)).isEqualTo(Long.parseUnsignedLong(hex, 16));
    }

    @Test
    void omittedAndNonprimitiveValuesHaveNoDescriptor() {
        assertThat(PrimitiveBits.of(new TraceValue.Primitive.Integer(42), false)).isNull();
        assertThat(PrimitiveBits.of(null, true)).isNull();
        assertThat(PrimitiveBits.of(new TraceValue.Null(), true)).isNull();
        assertThat(PrimitiveBits.of(new TraceValue.Reference(1L), true)).isNull();
        assertThat(PrimitiveBits.elements(List.of(), false)).isNull();
        assertThat(PrimitiveBits.elements(List.of(), true)).isEmpty();
        assertThat(PrimitiveBits.elements(List.of(new TraceValue.Primitive.Byte((byte) -1), new TraceValue.Null()), true))
            .containsExactly(new PrimitiveBinary("byte", "twos-complement", "11111111"), null);
    }
}
