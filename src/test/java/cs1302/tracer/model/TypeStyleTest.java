package cs1302.tracer.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TypeStyle}.
 */
public class TypeStyleTest {

    @Test
    @DisplayName("TypeStyle enum values and toString should return expected identifiers")
    void testEnumValues() {
        assertThat(TypeStyle.FQN.toString()).isEqualTo("fqn");
        assertThat(TypeStyle.SIMPLE.toString()).isEqualTo("simple");
        assertThat(TypeStyle.valueOf("FQN")).isEqualTo(TypeStyle.FQN);
        assertThat(TypeStyle.valueOf("SIMPLE")).isEqualTo(TypeStyle.SIMPLE);
    } // testEnumValues

    @Test
    @DisplayName("format with FQN should preserve full package names")
    void testFormatFqn() {
        assertThat(TypeStyle.FQN.format(null)).isNull();
        assertThat(TypeStyle.FQN.format("java.lang.String")).isEqualTo("java.lang.String");
        assertThat(TypeStyle.FQN.format("cs1302.generics.Pair<java.lang.String, java.lang.Integer>"))
                .isEqualTo("cs1302.generics.Pair<java.lang.String, java.lang.Integer>");
    } // testFormatFqn

    @Test
    @DisplayName("format and simplify with SIMPLE should strip package prefixes")
    void testFormatSimple() {
        assertThat(TypeStyle.SIMPLE.format(null)).isNull();
        assertThat(TypeStyle.simplify(null)).isNull();
        assertThat(TypeStyle.SIMPLE.format("int")).isEqualTo("int");
        assertThat(TypeStyle.SIMPLE.format("java.lang.String")).isEqualTo("String");
        assertThat(TypeStyle.SIMPLE.format("java.lang.String[]")).isEqualTo("String[]");
        assertThat(TypeStyle.SIMPLE.format("cs1302.generics.Pair<java.lang.String, java.lang.Integer>"))
                .isEqualTo("Pair<String, Integer>");
        assertThat(TypeStyle.SIMPLE.format(
                "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>"))
                .isEqualTo("Map<String, List<Integer>>");
        assertThat(TypeStyle.SIMPLE.format("? extends java.lang.Number"))
                .isEqualTo("? extends Number");
    } // testFormatSimple
} // TypeStyleTest
