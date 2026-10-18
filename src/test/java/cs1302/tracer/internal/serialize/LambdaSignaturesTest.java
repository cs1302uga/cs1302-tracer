package cs1302.tracer.internal.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.trace.ExecutionSnapshot;
import cs1302.tracer.internal.trace.TraceValue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LambdaSignaturesTest {

    private static final String BODY = """
         {
            // java.lang. remains in comments and qualified expressions.
            java.lang.String local = "java.lang.Integer";
            return java.util.List.of(local + "<java.lang.String>");
        }
        """;

    @Test
    void formatsNestedGenericAndArrayTypesWithoutChangingTheBody() {
        String signature = "java.util.List<java.lang.String[]>[] apply("
                + "java.util.Map<java.lang.String, ? extends java.lang.Number[]> values, int count)";
        String expected = "List<String[]>[] apply(Map<String, ? extends Number[]> values, int count)";
        assertThat(LambdaSignatures.format(signature + BODY, TypeStyle.SIMPLE)).isEqualTo(expected + BODY);
        assertThat(LambdaSignatures.format(signature + BODY, TypeStyle.FQN)).isEqualTo(signature + BODY);
    }

    @Test
    void preservesWhitespaceUnicodeNamesAndLineEndings() {
        String code = "\tjava.lang.String\r\n apply(\tjava.lang.Integer число)\r\n{\r\n\treturn \"🙂\";\r\n}\r\n";
        String expected = "\tString\r\n apply(\tInteger число)\r\n{\r\n\treturn \"🙂\";\r\n}\r\n";
        assertThat(LambdaSignatures.format(code, TypeStyle.SIMPLE)).isEqualTo(expected);
    }

    @Test
    void handlesZeroParametersPrimitiveTypesAndLegacyExpressions() {
        assertThat(LambdaSignatures.format("java.lang.String get() { return \"java.lang.\"; }", TypeStyle.SIMPLE))
                .isEqualTo("String get() { return \"java.lang.\"; }");
        assertThat(LambdaSignatures.format("void accept(int value) {}", TypeStyle.SIMPLE))
                .isEqualTo("void accept(int value) {}");
        assertThat(LambdaSignatures.format("(x) -> x + 1", TypeStyle.SIMPLE)).isEqualTo("(x) -> x + 1");
        assertThat(LambdaSignatures.format("java.lang.String apply(", TypeStyle.SIMPLE))
                .isEqualTo("java.lang.String apply(");
    }

    @Test
    void bothSerializersFormatTheSameSnapshotWithoutMutatingIt() {
        String original = "java.lang.String apply(java.lang.Integer x)" + BODY;
        String simple = "String apply(Integer x)" + BODY;
        ExecutionSnapshot snapshot = new ExecutionSnapshot(List.of(), List.of(),
                Map.of(1L, new TraceValue.Lambda(original)), new byte[0], new byte[0]);
        for (TypeStyle style : TypeStyle.values()) {
            String expected = style == TypeStyle.SIMPLE ? simple : original;
            assertThat(new ModernTraceSerializer(false, false, false, style).createTrace("public class Main {}", snapshot)
                    .steps().getFirst().heap().get("1").sam()).isEqualTo(expected);
            assertThat(new PyTutorSerializer(false, false, false, style).createTrace("public class Main {}", snapshot)
                    .trace().getFirst().heap().get("1")).isEqualTo(List.of("JAVA_LAMBDA", expected));
        }
        assertThat(((TraceValue.Lambda) snapshot.heap().get(1L)).implementation()).isEqualTo(original);
    }

}
