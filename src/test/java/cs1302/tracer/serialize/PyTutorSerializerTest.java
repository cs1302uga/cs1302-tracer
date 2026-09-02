package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.model.pytutor.RenderStackFrame;
import cs1302.tracer.model.pytutor.TraceStep;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.Field;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import cs1302.tracer.trace.TraceValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PyTutorSerializer")
public class PyTutorSerializerTest {

  @Test
  @DisplayName("should provide access to Gson instance and support pretty printing")
  void shouldProvideGsonInstance() {
    assertThat(PyTutorSerializer.getGson()).isNotNull();
    assertThat(PyTutorSerializer.getGson(false)).isNotNull();
    assertThat(PyTutorSerializer.getGson(true)).isNotNull();

    Map<String, String> sample = Map.of("key", "value");
    String compact = PyTutorSerializer.getGson(false).toJson(sample);
    String pretty = PyTutorSerializer.getGson(true).toJson(sample);

    assertThat(compact).doesNotContain("\n");
    assertThat(pretty).contains("\n");

    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(), List.of(), Map.of(), new byte[0], new byte[0]);
    PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
    assertThat(serializer.serialize("class A {}", snapshot, false)).doesNotContain("\n");
    assertThat(serializer.serialize("class A {}", snapshot, true)).contains("\n");
  }

  @Test
  @DisplayName("should serialize null field value")
  void shouldSerializeNullTraceValue() {
    Field nullField = new Field(false, "Object", "x", null);
    StackSnapshot frame = new StackSnapshot("main", 1, List.of(nullField), Optional.empty());
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(frame), List.of(), Map.of(), new byte[0], new byte[0]);

    PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
    TraceStep step = serializer.createTraceStep(snapshot);
    assertThat(step.stackToRender().getFirst().encodedLocals().get("x")).isNull();
  }

  @Nested
  @DisplayName("Primitive & Literal Serialization")
  class PrimitiveSerializationTests {

    @Test
    @DisplayName("should serialize all primitive numbers and characters")
    void shouldSerializePrimitives() {
      Field fFloat = new Field(false, "float", "f", new TraceValue.Primitive.Float(3.14f));
      Field fDouble = new Field(false, "double", "d", new TraceValue.Primitive.Double(2.718));
      Field fDoubleInf =
          new Field(false, "double", "dInf", new TraceValue.Primitive.Double(Double.POSITIVE_INFINITY));
      Field fDoubleNegInf =
          new Field(false, "double", "dNegInf", new TraceValue.Primitive.Double(Double.NEGATIVE_INFINITY));
      Field fDoubleNaN =
          new Field(false, "double", "dNan", new TraceValue.Primitive.Double(Double.NaN));
      Field fChar = new Field(false, "char", "c", new TraceValue.Primitive.Character('z'));
      Field fByte = new Field(false, "byte", "b", new TraceValue.Primitive.Byte((byte) 8));
      Field fShort = new Field(false, "short", "s", new TraceValue.Primitive.Short((short) 16));
      Field fLong = new Field(false, "long", "l", new TraceValue.Primitive.Long(64L));
      Field fBool = new Field(false, "boolean", "bool", new TraceValue.Primitive.Boolean(true));
      Field fNull = new Field(false, "Object", "n", new TraceValue.Null());

      StackSnapshot frame =
          new StackSnapshot(
              "main",
              10,
              List.of(
                  fFloat, fDouble, fDoubleInf, fDoubleNegInf, fDoubleNaN, fChar, fByte, fShort,
                  fLong, fBool, fNull),
              Optional.empty());
      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame), List.of(), Map.of(), new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      String json = serializer.serialize("public class Test {}", snapshot);

      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject locals =
          root.getAsJsonArray("trace")
              .get(0)
              .getAsJsonObject()
              .getAsJsonArray("stack_to_render")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("encoded_locals");

      assertThat(locals.getAsJsonArray("f").get(0).getAsString()).isEqualTo("NUMBER-LITERAL");
      assertThat(locals.getAsJsonArray("d").get(0).getAsString()).isEqualTo("NUMBER-LITERAL");
      assertThat(locals.getAsJsonArray("dInf").get(1).getAsString()).isEqualTo("Infinity");
      assertThat(locals.getAsJsonArray("dNegInf").get(1).getAsString()).isEqualTo("-Infinity");
      assertThat(locals.getAsJsonArray("dNan").get(1).getAsString()).isEqualTo("NaN");
      assertThat(locals.getAsJsonArray("c").get(1).getAsString()).isEqualTo("z");
      assertThat(locals.get("b").getAsByte()).isEqualTo((byte) 8);
      assertThat(locals.get("s").getAsShort()).isEqualTo((short) 16);
      assertThat(locals.get("l").getAsLong()).isEqualTo(64L);
      assertThat(locals.get("bool").getAsBoolean()).isTrue();
      assertThat(locals.get("n").isJsonNull()).isTrue();
    }
  }

  @Nested
  @DisplayName("Strings, References & Heap Serialization")
  class HeapSerializationTests {

    @Test
    @DisplayName("should serialize strings as heap instances when inlineStrings is false")
    void shouldSerializeBoxedStrings() {
      long strRefId = 101L;
      Field strVar = new Field(false, "String", "msg", new TraceValue.Reference(strRefId));
      StackSnapshot frame = new StackSnapshot("main", 5, List.of(strVar), Optional.empty());
      Map<Long, TraceValue> heap = Map.of(strRefId, new TraceValue.String("hello"));

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame), List.of(), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      String json = serializer.serialize("public class Test {}", snapshot);

      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject step = root.getAsJsonArray("trace").get(0).getAsJsonObject();
      JsonObject serializedHeap = step.getAsJsonObject("heap");

      assertThat(serializedHeap.has("101")).isTrue();
      assertThat(serializedHeap.getAsJsonArray("101").get(0).getAsString()).isEqualTo("INSTANCE");
      assertThat(serializedHeap.getAsJsonArray("101").get(1).getAsString()).isEqualTo("String");
    }

    @Test
    @DisplayName("should inline strings when inlineStrings is true")
    void shouldInlineStrings() {
      long strRefId = 101L;
      Field strVar = new Field(false, "String", "msg", new TraceValue.Reference(strRefId));
      StackSnapshot frame = new StackSnapshot("main", 5, List.of(strVar), Optional.empty());
      Map<Long, TraceValue> heap = Map.of(strRefId, new TraceValue.String("inlined"));

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame), List.of(), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, true, false);
      String json = serializer.serialize("public class Test {}", snapshot);

      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      JsonObject step = root.getAsJsonArray("trace").get(0).getAsJsonObject();
      JsonObject locals =
          step.getAsJsonArray("stack_to_render")
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("encoded_locals");

      assertThat(locals.get("msg").getAsString()).isEqualTo("inlined");
      assertThat(step.getAsJsonObject("heap").has("101")).isFalse();
    }

    @Test
    @DisplayName("should serialize List, Collection, Map, Object, and Lambda heap values")
    void shouldSerializeComplexHeapStructures() {
      TraceValue.List listVal =
          new TraceValue.List("int[]", List.of(new TraceValue.Primitive.Integer(1)));
      TraceValue.Collection setVal =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Integer(2)));
      Map<TraceValue, TraceValue> mapEntries = new LinkedHashMap<>();
      mapEntries.put(
          new TraceValue.String("k"), new TraceValue.Primitive.Integer(99));
      TraceValue.Map mapVal = new TraceValue.Map(mapEntries);
      TraceValue.Object objVal =
          new TraceValue.Object(
              "Person",
              List.of(new Field(false, "int", "id", new TraceValue.Primitive.Integer(123))));
      TraceValue.Lambda lambdaVal = new TraceValue.Lambda("() -> 42");

      Map<Long, TraceValue> heap =
          Map.of(
              1L, listVal,
              2L, setVal,
              3L, mapVal,
              4L, objVal,
              5L, lambdaVal);

      Field statField = new Field(true, "int[]", "MY_LIST", new TraceValue.Reference(1L));
      StackSnapshot frame =
          new StackSnapshot(
              "foo",
              20,
              List.of(
                  new Field(false, "Set", "mySet", new TraceValue.Reference(2L)),
                  new Field(false, "Map", "myMap", new TraceValue.Reference(3L)),
                  new Field(false, "Person", "p", new TraceValue.Reference(4L)),
                  new Field(false, "Supplier", "lam", new TraceValue.Reference(5L))),
              Optional.of(new ThisObject("MyClass", new TraceValue.Reference(4L))));

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(
              List.of(frame),
              List.of(statField),
              heap,
              "out".getBytes(),
              "err".getBytes());

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      PyTutorTrace trace = serializer.createTrace("public class Test {}", snapshot);
      TraceStep step = trace.trace().getFirst();

      assertThat(step.stdout()).isEqualTo("out");
      assertThat(step.stderr()).isEqualTo("err");
      assertThat(step.globals()).containsKey("MY_LIST");
      assertThat(step.globalsAttrs()).containsKey("MY_LIST");
      assertThat(step.heapAttrs()).containsKey("1");
      assertThat(step.heapAttrs()).containsKey("4");

      RenderStackFrame renderedFrame = step.stackToRender().getFirst();
      assertThat(renderedFrame.orderedVarnames()).contains("this", "mySet", "myMap", "p", "lam");
      assertThat(renderedFrame.localsAttrs()).containsKey("this");

      // Verify removeMethodThis flag
      PyTutorSerializer noThisSerializer = new PyTutorSerializer(false, false, true);
      TraceStep noThisStep = noThisSerializer.createTraceStep(snapshot);
      RenderStackFrame noThisFrame = noThisStep.stackToRender().getFirst();
      assertThat(noThisFrame.orderedVarnames()).doesNotContain("this");
    }

    @Test
    @DisplayName("should filter main args on frame 0 but keep args on subsequent frames")
    void shouldFilterArgsOnlyOnMainFrame() {
      Field mainArgs = new Field(false, "String[]", "args", new TraceValue.Reference(10L));
      Field subArgs = new Field(false, "String[]", "args", new TraceValue.Reference(20L));

      StackSnapshot frame0 = new StackSnapshot("main", 1, List.of(mainArgs), Optional.empty());
      StackSnapshot frame1 = new StackSnapshot("helper", 5, List.of(subArgs), Optional.empty());

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame0, frame1), List.of(), Map.of(), new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(true, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      assertThat(step.stackToRender().get(0).encodedLocals()).doesNotContainKey("args");
      assertThat(step.stackToRender().get(1).encodedLocals()).containsKey("args");
    }

    @Test
    @DisplayName("should handle empty stack and invalid byte decodings gracefully")
    void shouldHandleEmptyStackAndCorruptedOutputs() {
      byte[] invalidUtf8 = new byte[] {(byte) 0xFF, (byte) 0xFE};
      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(Collections.emptyList(), List.of(), Map.of(), invalidUtf8, invalidUtf8);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      assertThat(step.funcName()).isEmpty();
      assertThat(step.line()).isEqualTo(0L);
      assertThat(step.stackToRender()).isEmpty();
    }

    @Test
    @DisplayName("should resolve reified types for generic collections, maps, primitives, and strings in heap_attrs")
    void shouldResolveReifiedTypesInHeapAttrs() {
      TraceValue.List listVal =
          new TraceValue.List("java.util.ArrayList", List.of(new TraceValue.Reference(20L)));
      TraceValue.String strVal = new TraceValue.String("hello");
      TraceValue.Map mapVal =
          new TraceValue.Map(
              "java.util.HashMap",
              Map.of(new TraceValue.Reference(20L), new TraceValue.Reference(40L)));
      TraceValue.Primitive.Integer intVal = new TraceValue.Primitive.Integer(42);
      TraceValue.Collection setVal =
          new TraceValue.Collection("java.util.HashSet", List.of(new TraceValue.Reference(20L)));
      TraceValue.Primitive.Double doubleVal = new TraceValue.Primitive.Double(3.14);
      TraceValue.Primitive.Boolean boolVal = new TraceValue.Primitive.Boolean(true);
      TraceValue.Primitive.Long longVal = new TraceValue.Primitive.Long(999L);
      TraceValue.Primitive.Float floatVal = new TraceValue.Primitive.Float(1.5f);
      TraceValue.Primitive.Character charVal = new TraceValue.Primitive.Character('x');
      TraceValue.Primitive.Byte byteVal = new TraceValue.Primitive.Byte((byte) 2);
      TraceValue.Primitive.Short shortVal = new TraceValue.Primitive.Short((short) 4);
      TraceValue.Lambda lambdaVal = new TraceValue.Lambda("() -> 1");
      TraceValue.List matrixVal =
          new TraceValue.List("int[][]", List.of(new TraceValue.Reference(150L)));
      TraceValue.List rowVal =
          new TraceValue.List("int[]", List.of(new TraceValue.Primitive.Integer(1)));

      Map<Long, TraceValue> heap =
          Map.ofEntries(
              Map.entry(10L, listVal),
              Map.entry(20L, strVal),
              Map.entry(30L, mapVal),
              Map.entry(40L, intVal),
              Map.entry(50L, setVal),
              Map.entry(60L, doubleVal),
              Map.entry(70L, boolVal),
              Map.entry(80L, longVal),
              Map.entry(90L, floatVal),
              Map.entry(100L, charVal),
              Map.entry(110L, byteVal),
              Map.entry(120L, shortVal),
              Map.entry(130L, lambdaVal),
              Map.entry(140L, matrixVal),
              Map.entry(150L, rowVal));

      StackSnapshot frame =
          new StackSnapshot(
              "main",
              1,
              List.of(
                  new Field(false, "List<String>", "names", new TraceValue.Reference(10L)),
                  new Field(false, "Map<String, Integer>", "scores", new TraceValue.Reference(30L)),
                  new Field(false, "Set<String>", "tags", new TraceValue.Reference(50L)),
                  new Field(false, "int[][]", "mat", new TraceValue.Reference(140L))),
              Optional.empty());

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame), List.of(), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      Map<String, Object> heapAttrs = step.heapAttrs();
      assertThat(heapAttrs.get("10")).isEqualTo(Map.of("type", "java.util.ArrayList<String>"));
      assertThat(heapAttrs.get("20")).isEqualTo(Map.of("type", "java.lang.String"));
      assertThat(heapAttrs.get("30"))
          .isEqualTo(Map.of("type", "java.util.HashMap<String, Integer>"));
      assertThat(heapAttrs.get("40")).isEqualTo(Map.of("type", "java.lang.Integer"));
      assertThat(heapAttrs.get("50")).isEqualTo(Map.of("type", "java.util.HashSet<String>"));
      assertThat(heapAttrs.get("60")).isEqualTo(Map.of("type", "java.lang.Double"));
      assertThat(heapAttrs.get("70")).isEqualTo(Map.of("type", "java.lang.Boolean"));
      assertThat(heapAttrs.get("80")).isEqualTo(Map.of("type", "java.lang.Long"));
      assertThat(heapAttrs.get("90")).isEqualTo(Map.of("type", "java.lang.Float"));
      assertThat(heapAttrs.get("100")).isEqualTo(Map.of("type", "java.lang.Character"));
      assertThat(heapAttrs.get("110")).isEqualTo(Map.of("type", "java.lang.Byte"));
      assertThat(heapAttrs.get("120")).isEqualTo(Map.of("type", "java.lang.Short"));
      assertThat(heapAttrs.get("130")).isEqualTo(Map.of("type", "lambda"));
      assertThat(heapAttrs.get("140")).isEqualTo(Map.of("type", "int[][]"));
      assertThat(heapAttrs.get("150")).isEqualTo(Map.of("type", "int[]"));
    }

    @Test
    @DisplayName("should sample element types for collections and maps without explicit generic declaration")
    void shouldSampleElementTypesWhenGenericMissing() {
      TraceValue.List listVal =
          new TraceValue.List("java.util.ArrayList", List.of(new TraceValue.Reference(2L)));
      TraceValue.String strVal = new TraceValue.String("sample");
      TraceValue.Map mapVal =
          new TraceValue.Map(
              "java.util.HashMap",
              Map.of(new TraceValue.Reference(2L), new TraceValue.Reference(4L)));
      TraceValue.Primitive.Integer intVal = new TraceValue.Primitive.Integer(100);

      Map<Long, TraceValue> heap = Map.of(1L, listVal, 2L, strVal, 3L, mapVal, 4L, intVal);

      StackSnapshot frame =
          new StackSnapshot(
              "main",
              1,
              List.of(
                  new Field(false, "List", "rawList", new TraceValue.Reference(1L)),
                  new Field(false, "Map", "rawMap", new TraceValue.Reference(3L))),
              Optional.empty());

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(frame), List.of(), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      Map<String, Object> heapAttrs = step.heapAttrs();
      assertThat(heapAttrs.get("1")).isEqualTo(Map.of("type", "java.util.ArrayList<String>"));
      assertThat(heapAttrs.get("3")).isEqualTo(Map.of("type", "java.util.HashMap<String, Integer>"));
    }

    @Test
    @DisplayName("should handle empty and heterogeneous collections and maps in heap_attrs")
    void shouldHandleEmptyAndHeterogeneousCollections() {
      TraceValue.List emptyList = new TraceValue.List("java.util.ArrayList", List.of());
      TraceValue.Collection emptySet = new TraceValue.Collection("java.util.HashSet", List.of());
      TraceValue.Map emptyMap = new TraceValue.Map("java.util.HashMap", Map.of());

      TraceValue.List heterList =
          new TraceValue.List(
              "java.util.ArrayList",
              List.of(new TraceValue.String("a"), new TraceValue.Primitive.Integer(1)));
      TraceValue.Collection unknownCol =
          new TraceValue.Collection("java.util.HashSet", List.of(new TraceValue.Null()));

      // Sampling all supported element types directly
      TraceValue.Collection doubleCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Double(1.0)));
      TraceValue.Collection boolCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Boolean(false)));
      TraceValue.Collection longCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Long(10L)));
      TraceValue.Collection floatCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Float(1.0f)));
      TraceValue.Collection charCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Character('c')));
      TraceValue.Collection byteCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Byte((byte) 1)));
      TraceValue.Collection shortCol =
          new TraceValue.Collection(List.of(new TraceValue.Primitive.Short((short) 1)));
      TraceValue.Collection objFqnCol =
          new TraceValue.Collection(List.of(new TraceValue.Object("pkg.CustomObj", List.of())));
      TraceValue.Collection objSimpleCol =
          new TraceValue.Collection(List.of(new TraceValue.Object("SimpleObj", List.of())));
      TraceValue.Collection nestedListCol =
          new TraceValue.Collection(List.of(new TraceValue.List("int[]", List.of())));
      TraceValue.Collection nestedMapCol =
          new TraceValue.Collection(List.of(new TraceValue.Map("java.util.TreeMap", Map.of())));
      TraceValue.Collection nestedSetCol =
          new TraceValue.Collection(List.of(new TraceValue.Collection("java.util.TreeSet", List.of())));

      // Object on heap with field references
      TraceValue.Object parentObj =
          new TraceValue.Object(
              "Node",
              List.of(
                  new Field(false, "Node", "next", new TraceValue.Reference(100L))));

      Map<Long, TraceValue> heap =
          Map.ofEntries(
              Map.entry(1L, emptyList),
              Map.entry(2L, emptySet),
              Map.entry(3L, emptyMap),
              Map.entry(4L, heterList),
              Map.entry(5L, unknownCol),
              Map.entry(6L, doubleCol),
              Map.entry(7L, boolCol),
              Map.entry(8L, longCol),
              Map.entry(9L, floatCol),
              Map.entry(10L, charCol),
              Map.entry(11L, byteCol),
              Map.entry(12L, shortCol),
              Map.entry(13L, objFqnCol),
              Map.entry(14L, objSimpleCol),
              Map.entry(15L, nestedListCol),
              Map.entry(16L, nestedMapCol),
              Map.entry(17L, nestedSetCol),
              Map.entry(99L, parentObj),
              Map.entry(100L, new TraceValue.Object("Node", List.of())));

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(List.of(), List.of(), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      Map<String, Object> heapAttrs = step.heapAttrs();
      assertThat(heapAttrs.get("1")).isEqualTo(Map.of("type", "java.util.ArrayList"));
      assertThat(heapAttrs.get("2")).isEqualTo(Map.of("type", "java.util.HashSet"));
      assertThat(heapAttrs.get("3")).isEqualTo(Map.of("type", "java.util.HashMap"));
      assertThat(heapAttrs.get("4")).isEqualTo(Map.of("type", "java.util.ArrayList"));
      assertThat(heapAttrs.get("5")).isEqualTo(Map.of("type", "java.util.HashSet"));
      assertThat(heapAttrs.get("6")).isEqualTo(Map.of("type", "java.util.Collection<Double>"));
      assertThat(heapAttrs.get("7")).isEqualTo(Map.of("type", "java.util.Collection<Boolean>"));
      assertThat(heapAttrs.get("8")).isEqualTo(Map.of("type", "java.util.Collection<Long>"));
      assertThat(heapAttrs.get("9")).isEqualTo(Map.of("type", "java.util.Collection<Float>"));
      assertThat(heapAttrs.get("10")).isEqualTo(Map.of("type", "java.util.Collection<Character>"));
      assertThat(heapAttrs.get("11")).isEqualTo(Map.of("type", "java.util.Collection<Byte>"));
      assertThat(heapAttrs.get("12")).isEqualTo(Map.of("type", "java.util.Collection<Short>"));
      assertThat(heapAttrs.get("13")).isEqualTo(Map.of("type", "java.util.Collection<CustomObj>"));
      assertThat(heapAttrs.get("14")).isEqualTo(Map.of("type", "java.util.Collection<SimpleObj>"));
      assertThat(heapAttrs.get("15")).isEqualTo(Map.of("type", "java.util.Collection<int[]>"));
      assertThat(heapAttrs.get("16")).isEqualTo(Map.of("type", "java.util.Collection<java.util.TreeMap>"));
      assertThat(heapAttrs.get("17")).isEqualTo(Map.of("type", "java.util.Collection<java.util.TreeSet>"));
    }

    @Test
    @DisplayName("should cover all branch combinations in heap resolution")
    void shouldCoverAllBranchCombinationsInHeapResolution() {
      // 1. Static primitive and static null
      Field statPrimitive = new Field(false, "int", "STAT_INT", new TraceValue.Primitive.Integer(7));
      Field statNull = new Field(false, "Object", "STAT_NULL", new TraceValue.Null());

      // 2. Collection with multiple elements of same type
      TraceValue.Collection sameTypeCol =
          new TraceValue.Collection(
              List.of(new TraceValue.String("x"), new TraceValue.String("y")));

      // 3. Map with heterogeneous keys
      TraceValue.Map heterKeyMap =
          new TraceValue.Map(
              Map.of(
                  new TraceValue.String("k1"), new TraceValue.String("v1"),
                  new TraceValue.Primitive.Integer(2), new TraceValue.String("v2")));

      // 4. Map with heterogeneous values
      TraceValue.Map heterValMap =
          new TraceValue.Map(
              Map.of(
                  new TraceValue.String("k1"), new TraceValue.String("v1"),
                  new TraceValue.String("k2"), new TraceValue.Primitive.Integer(2)));

      // 5. Dangling reference in collection and in heap
      TraceValue.Collection danglingCol =
          new TraceValue.Collection(List.of(new TraceValue.Reference(99999L)));

      // 6. Null and unhandled value in heap
      TraceValue.Null nullInHeap = new TraceValue.Null();

      // 7. Malformed declared types
      Field malformed1 = new Field(false, ">bad<", "m1", new TraceValue.Reference(10L));
      Field malformed2 = new Field(false, "List<", "m2", new TraceValue.Reference(11L));
      Field malformed3 = new Field(false, "Object", "m3", new TraceValue.Reference(12L));

      TraceValue.List list1 = new TraceValue.List("java.util.ArrayList", List.of());
      TraceValue.List list2 = new TraceValue.List("java.util.ArrayList", List.of());
      TraceValue.List list3 = new TraceValue.List("java.util.ArrayList", List.of());

      Map<Long, TraceValue> heap =
          Map.of(
              1L, sameTypeCol,
              2L, heterKeyMap,
              3L, heterValMap,
              4L, danglingCol,
              5L, nullInHeap,
              10L, list1,
              11L, list2,
              12L, list3);

      StackSnapshot frame =
          new StackSnapshot(
              "main", 1, List.of(malformed1, malformed2, malformed3), Optional.empty());

      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(
              List.of(frame), List.of(statPrimitive, statNull), heap, new byte[0], new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      TraceStep step = serializer.createTraceStep(snapshot);

      Map<String, Object> heapAttrs = step.heapAttrs();
      assertThat(heapAttrs.get("1")).isEqualTo(Map.of("type", "java.util.Collection<String>"));
      assertThat(heapAttrs.get("2")).isEqualTo(Map.of("type", "java.util.Map"));
      assertThat(heapAttrs.get("3")).isEqualTo(Map.of("type", "java.util.Map"));
      assertThat(heapAttrs.get("4")).isEqualTo(Map.of("type", "java.util.Collection"));
      assertThat(heapAttrs.containsKey("5")).isFalse();
      assertThat(heapAttrs.get("10")).isEqualTo(Map.of("type", "java.util.ArrayList"));
      assertThat(heapAttrs.get("11")).isEqualTo(Map.of("type", "java.util.ArrayList"));
      assertThat(heapAttrs.get("12")).isEqualTo(Map.of("type", "java.util.ArrayList"));
    }

    @Test
    @DisplayName("should create PyTutorTrace from chronological list of snapshots")
    void shouldCreateTraceFromSnapshotsList() {
      ExecutionSnapshot snapshot1 =
          new ExecutionSnapshot(
              List.of(new StackSnapshot("main", 1, List.of(), Optional.empty())),
              List.of(),
              Map.of(),
              new byte[0],
              new byte[0]);
      ExecutionSnapshot snapshot2 =
          new ExecutionSnapshot(
              List.of(new StackSnapshot("main", 2, List.of(), Optional.empty())),
              List.of(),
              Map.of(),
              new byte[0],
              new byte[0]);

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      PyTutorTrace trace = serializer.createTrace("int a = 1;\nint b = 2;\n", List.of(snapshot1, snapshot2));

      assertThat(trace.code()).isEqualTo("int a = 1;\nint b = 2;\n");
      assertThat(trace.trace()).hasSize(2);
      assertThat(trace.trace().get(0).line()).isEqualTo(1);
      assertThat(trace.trace().get(1).line()).isEqualTo(2);
      assertThat(trace.trace().get(0).file()).isNull();
    }

    @Test
    @DisplayName("should serialize file metadata when multi-file is detected")
    void shouldSerializeFileMetadataForMultiFile() {
      ExecutionSnapshot snapshot1 =
          new ExecutionSnapshot(
              List.of(
                  new StackSnapshot(
                      "main", 6, List.of(), Optional.empty(), Optional.of("cs1302/math/Driver.java")),
                  new StackSnapshot(
                      "multiply", 7, List.of(), Optional.empty(), Optional.of("cs1302/math/Calculator.java"))),
              List.of(),
              Map.of(),
              new byte[0],
              new byte[0],
              Optional.of("cs1302/math/Calculator.java"));

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      String multiSource =
          """
          // --- cs1302/math/Calculator.java ---
          package cs1302.math;
          public class Calculator {}
          // --- cs1302/math/Driver.java ---
          package cs1302.math;
          public class Driver {}
          """;

      PyTutorTrace trace = serializer.createTrace(multiSource, snapshot1);
      TraceStep step = trace.trace().get(0);
      assertThat(step.file()).isEqualTo("cs1302/math/Calculator.java");
      assertThat(step.stackToRender().get(0).file()).isEqualTo("cs1302/math/Driver.java");
      assertThat(step.stackToRender().get(1).file()).isEqualTo("cs1302/math/Calculator.java");

      String json = serializer.serialize(multiSource, snapshot1);
      assertThat(json).contains("\"file\":\"cs1302/math/Calculator.java\"");
      assertThat(json).contains("\"file\":\"cs1302/math/Driver.java\"");
    }

    @Test
    @DisplayName("should serialize file metadata when distinct files present without stream delimiters")
    void shouldSerializeFileMetadataWhenDistinctFilesPresent() {
      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(
              List.of(
                  new StackSnapshot(
                      "main", 6, List.of(), Optional.empty(), Optional.of("cs1302/math/Driver.java")),
                  new StackSnapshot(
                      "multiply", 7, List.of(), Optional.empty(), Optional.of("cs1302/math/Calculator.java"))),
              List.of(),
              Map.of(),
              new byte[0],
              new byte[0],
              Optional.of("cs1302/math/Calculator.java"));

      PyTutorSerializer serializer = new PyTutorSerializer(false, false, false);
      String plainSource = "public class Driver {}";

      PyTutorTrace trace = serializer.createTrace(plainSource, snapshot);
      assertThat(trace.trace().get(0).file()).isEqualTo("cs1302/math/Calculator.java");
    }

    @Test
    @DisplayName("should format types according to TypeStyle in PyTutorSerializer")
    void shouldFormatTypesAccordingToTypeStyle() {
      Field f1 = new Field(false, "java.lang.String", "name", new TraceValue.Reference(101L));
      TraceValue.Object obj =
          new TraceValue.Object(
              "cs1302.generics.Pair<java.lang.String, java.lang.Integer>",
              List.of(new Field(false, "java.lang.String", "key", new TraceValue.Null())));
      StackSnapshot frame =
          new StackSnapshot(
              "main",
              10,
              List.of(f1),
              Optional.of(
                  new ThisObject(
                      "cs1302.generics.Pair<java.lang.String, java.lang.Integer>",
                      new TraceValue.Reference(101L))));
      ExecutionSnapshot snapshot =
          new ExecutionSnapshot(
              List.of(frame),
              List.of(new Field(false, "java.lang.String", "GLOBAL", new TraceValue.Null())),
              Map.of(101L, obj),
              new byte[0],
              new byte[0]);

      PyTutorSerializer simpleSerializer =
          new PyTutorSerializer(false, false, false, cs1302.tracer.model.TypeStyle.SIMPLE);
      TraceStep simpleStep = simpleSerializer.createTraceStep(snapshot);

      assertThat(simpleStep.globalsAttrs().get("GLOBAL"))
          .isEqualTo(Map.of("type", "String", "final", false));
      assertThat(simpleStep.stackToRender().getFirst().localsAttrs().get("this"))
          .isEqualTo(Map.of("type", "Pair<String, Integer>", "final", true));
      assertThat(simpleStep.stackToRender().getFirst().localsAttrs().get("name"))
          .isEqualTo(Map.of("type", "String", "final", false));
      List<?> instanceList = (List<?>) simpleStep.heap().get("101");
      assertThat(instanceList.get(1)).isEqualTo("Pair<String, Integer>");
    }
  }
}
