package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReifiedTypeMetadataTest {
    static Stream<Arguments> refinements() {
        return Stream.of(Arguments.of("Object", null, "java.util.ArrayList", "Object"),
                Arguments.of(null, "java.util.ArrayList<Integer>", "java.util.ArrayList",
                        "java.util.ArrayList<Integer>"),
                Arguments.of("java.util.ArrayList", "java.util.ArrayList", "java.util.ArrayList",
                        "java.util.ArrayList"),
                Arguments.of("java.util.List<java.lang.Integer>", "java.util.ArrayList<java.lang.Integer>",
                        "java.util.ArrayList", "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("java.util.ArrayList<java.lang.Integer>", "java.util.List<java.lang.Integer>",
                        "java.util.ArrayList", "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("java.util.ArrayList", "java.util.ArrayList<java.lang.Integer>", "java.util.ArrayList",
                        "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("java.util.ArrayList<java.lang.Integer>", "java.util.ArrayList", "java.util.ArrayList",
                        "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("java.util.ArrayList<? extends java.lang.Number>",
                        "java.util.ArrayList<java.lang.Integer>", "java.util.ArrayList",
                        "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("java.util.ArrayList<java.lang.Integer>",
                        "java.util.ArrayList<? extends java.lang.Number>", "java.util.ArrayList",
                        "java.util.ArrayList<java.lang.Integer>"),
                Arguments.of("Box", "Other<String>", "Box", "Box"),
                Arguments.of("Box<String>", "Box", "Box", "Box<String>"),
                Arguments.of("Box<?>", "Box<String>", "Box", "Box<String>"),
                Arguments.of("Box<?>", "Box<? extends Number>", "Box", "Box<?>"),
                Arguments.of("Box<String>", "Box<Integer>", "Box", "Box<String>"),
                Arguments.of("Other", "One", "Box", "Other"));
    }

    @ParameterizedTest
    @MethodSource("refinements")
    void refinementPreservesExistingPrecedence(String existing, String candidate, String runtime, String expected) {
        var metadata = new ReifiedTypeMetadata(null);
        metadata.recordFallback(1, existing);
        metadata.refine(1, candidate, runtime);
        assertThat(metadata.typeOf(1)).isEqualTo(expected);
    }

    @Test
    void fallbackNeverOverwritesEvidenceAndSnapshotsStayIndependent() {
        var first = new ReifiedTypeMetadata(null);
        var second = new ReifiedTypeMetadata(null);
        first.recordFallback(1, null);
        assertThat(first.typeOf(1)).isNull();
        first.recordFallback(1, "Box");
        first.refine(1, "Box<String>", "Box");
        first.recordFallback(1, "Box<Integer>");
        assertThat(first.typeOf(1)).isEqualTo("Box<String>");
        assertThat(second.typeOf(1)).isNull();
        second.refineDeclared(1, "Box<Integer>", "Box");
        assertThat(second.typeOf(1)).isEqualTo("Box<Integer>");
        first.refineDeclared(1, null, "Box");
        assertThat(first.typeOf(1)).isEqualTo("Box<String>");
    }
}
