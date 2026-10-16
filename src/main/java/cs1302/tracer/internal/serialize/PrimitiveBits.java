package cs1302.tracer.internal.serialize;

import cs1302.tracer.internal.model.PrimitiveBinary;
import cs1302.tracer.internal.trace.TraceValue;
import java.util.List;
import java.util.Collection;

/** Converts owned primitive values without parsing literals or invoking guest methods. */
final class PrimitiveBits {

    /** Utility class. */
    private PrimitiveBits() {} // PrimitiveBits

    /**
     * Encodes a primitive when requested; references and null have no primitive encoding.
     * @param value Captured value.
     * @param enabled Whether metadata was requested.
     * @return Descriptor, or null when absent.
     */
    static PrimitiveBinary of(TraceValue value, boolean enabled) {
        if (!enabled || !(value instanceof TraceValue.Primitive primitive)) {
            return null;
        } // if
        return switch (primitive) {
        case TraceValue.Primitive.Boolean b -> encode("boolean", "logical", b.value() ? 1 : 0, 1);
        case TraceValue.Primitive.Byte b -> encode("byte", "twos-complement", b.value(), 8);
        case TraceValue.Primitive.Short s -> encode("short", "twos-complement", s.value(), 16);
        case TraceValue.Primitive.Integer i -> encode("int", "twos-complement", i.value(), 32);
        case TraceValue.Primitive.Long l -> encode("long", "twos-complement", l.value(), 64);
        case TraceValue.Primitive.Character c -> encode("char", "utf16-code-unit", c.value(), 16);
        case TraceValue.Primitive.Float f ->
            encode("float", "ieee754-binary32", Float.floatToRawIntBits(f.value()), 32);
        case TraceValue.Primitive.Double d ->
            encode("double", "ieee754-binary64", Double.doubleToRawLongBits(d.value()), 64);
        }; // switch
    } // of

    /**
     * Encodes array positions, retaining null positions for nonprimitive values.
     * @param values Captured elements.
     * @param enabled Whether metadata was requested.
     * @return Aligned descriptors, or null when disabled.
     */
    static List<PrimitiveBinary> elements(Collection<? extends TraceValue> values, boolean enabled) {
        return enabled ? values.stream().map(value -> of(value, true)).toList() : null;
    } // elements

    /**
     * Formats the low width bits, avoiding sign extension and preserving leading zeros.
     * @param type Primitive type.
     * @param encoding Encoding name.
     * @param value Bit pattern.
     * @param width Number of bits.
     * @return Binary descriptor.
     */
    private static PrimitiveBinary encode(String type, String encoding, long value, int width) {
        StringBuilder bits = new StringBuilder(width);
        for (int bit = width - 1; bit >= 0; bit--) {
            bits.append((value >>> bit) & 1);
        } // for
        return new PrimitiveBinary(type, encoding, bits.toString());
    } // encode
} // PrimitiveBits
