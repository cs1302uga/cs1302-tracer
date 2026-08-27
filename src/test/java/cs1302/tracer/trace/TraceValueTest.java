package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TraceValue Unit Tests")
public class TraceValueTest {

  @Test
  @DisplayName("fromJdiValue with null value returns TraceValue.Null")
  void shouldReturnNullOnNullValue() {
    TraceValue tv = TraceValue.fromJdiValue(null, null, Optional.empty());
    assertThat(tv).isInstanceOf(TraceValue.Null.class);
  }

  @Nested
  @DisplayName("Primitive & Reference Records")
  class PrimitiveAndReferenceTests {

    @Test
    void testBoolean() {
      TraceValue.Primitive.Boolean valTrue = new TraceValue.Primitive.Boolean(true);
      TraceValue.Primitive.Boolean valFalse = new TraceValue.Primitive.Boolean(false);
      assertThat(valTrue.value()).isTrue();
      assertThat(valTrue.toWrapperObject()).isEqualTo(Boolean.TRUE);
      assertThat(valFalse.value()).isFalse();
      assertThat(valFalse.toWrapperObject()).isEqualTo(Boolean.FALSE);
    }

    @Test
    void testByte() {
      TraceValue.Primitive.Byte val = new TraceValue.Primitive.Byte((byte) 12);
      assertThat(val.value()).isEqualTo((byte) 12);
      assertThat(val.toWrapperObject()).isEqualTo((byte) 12);
    }

    @Test
    void testChar() {
      TraceValue.Primitive.Character val = new TraceValue.Primitive.Character('a');
      assertThat(val.value()).isEqualTo('a');
      assertThat(val.toWrapperObject()).isEqualTo('a');
    }

    @Test
    void testShort() {
      TraceValue.Primitive.Short val = new TraceValue.Primitive.Short((short) 42);
      assertThat(val.value()).isEqualTo((short) 42);
      assertThat(val.toWrapperObject()).isEqualTo((short) 42);
    }

    @Test
    void testInt() {
      TraceValue.Primitive.Integer val = new TraceValue.Primitive.Integer(12345);
      assertThat(val.value()).isEqualTo(12345);
      assertThat(val.toWrapperObject()).isEqualTo(12345);
    }

    @Test
    void testLong() {
      TraceValue.Primitive.Long val = new TraceValue.Primitive.Long(9876543210L);
      assertThat(val.value()).isEqualTo(9876543210L);
      assertThat(val.toWrapperObject()).isEqualTo(9876543210L);
    }

    @Test
    void testFloat() {
      TraceValue.Primitive.Float val = new TraceValue.Primitive.Float(3.14f);
      assertThat(val.value()).isEqualTo(3.14f);
      assertThat(val.toWrapperObject()).isEqualTo(3.14f);
    }

    @Test
    void testDouble() {
      TraceValue.Primitive.Double val = new TraceValue.Primitive.Double(2.71828);
      assertThat(val.value()).isEqualTo(2.71828);
      assertThat(val.toWrapperObject()).isEqualTo(2.71828);
    }

    @Test
    void testReference() {
      TraceValue.Reference ref = new TraceValue.Reference(101L);
      assertThat(ref.uniqueId()).isEqualTo(101L);
      assertThat(ref).isEqualTo(new TraceValue.Reference(101L));
    }

    @Test
    void testNull() {
      TraceValue.Null n = new TraceValue.Null();
      assertThat(n).isEqualTo(new TraceValue.Null());
    }

    @Test
    void testString() {
      TraceValue.String str = new TraceValue.String("hello world");
      assertThat(str.value()).isEqualTo("hello world");
    }

    @Test
    void testObject() {
      ExecutionSnapshot.Field field =
          new ExecutionSnapshot.Field(true, "int", "x", new TraceValue.Primitive.Integer(10));
      TraceValue.Object obj = new TraceValue.Object("com.example.Foo", List.of(field));
      assertThat(obj.classFqn()).isEqualTo("com.example.Foo");
      assertThat(obj.fields()).containsExactly(field);
    }

    @Test
    void testList() {
      TraceValue.List list =
          new TraceValue.List(
              "java.util.ArrayList",
              List.of(new TraceValue.Primitive.Integer(1), new TraceValue.Primitive.Integer(2)));
      assertThat(list.typeName()).isEqualTo("java.util.ArrayList");
      assertThat(list.value()).hasSize(2);
    }

    @Test
    void testCollection() {
      TraceValue.Collection col1 =
          new TraceValue.Collection(List.of(new TraceValue.String("a"), new TraceValue.String("b")));
      assertThat(col1.typeName()).isEqualTo("java.util.Collection");
      assertThat(col1.value()).hasSize(2);

      TraceValue.Collection col2 =
          new TraceValue.Collection("java.util.HashSet", List.of(new TraceValue.String("c")));
      assertThat(col2.typeName()).isEqualTo("java.util.HashSet");
      assertThat(col2.value()).hasSize(1);
    }

    @Test
    void testMap() {
      TraceValue.Map map1 =
          new TraceValue.Map(
              Map.of(
                  new TraceValue.Reference(1L), new TraceValue.Reference(2L)));
      assertThat(map1.typeName()).isEqualTo("java.util.Map");
      assertThat(map1.value()).hasSize(1);

      TraceValue.Map map2 =
          new TraceValue.Map(
              "java.util.HashMap",
              Map.of(
                  new TraceValue.Reference(3L), new TraceValue.Reference(4L)));
      assertThat(map2.typeName()).isEqualTo("java.util.HashMap");
      assertThat(map2.value()).hasSize(1);
    }

    @Test
    void testLambda() {
      TraceValue.Lambda lambda = new TraceValue.Lambda("() -> 42");
      assertThat(lambda.implementation()).isEqualTo("() -> 42");
    }
  }
}
