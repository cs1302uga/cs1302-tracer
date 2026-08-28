package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.assertThat;

import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.modern.HeapObject;
import cs1302.tracer.model.modern.Reference;
import cs1302.tracer.model.modern.StackFrame;
import cs1302.tracer.model.modern.Step;
import cs1302.tracer.model.modern.Trace;
import cs1302.tracer.model.modern.Variable;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.Field;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import cs1302.tracer.trace.TraceValue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ModernTraceSerializer")
public class ModernTraceSerializerTest {

  @Test
  @DisplayName("should provide Gson instance and enum toString")
  void testGsonAndEnum() {
    assertThat(ModernTraceSerializer.getGson()).isNotNull();
    assertThat(TraceFormat.PYTUTOR.toString()).isEqualTo("pytutor");
    assertThat(TraceFormat.MODERN.toString()).isEqualTo("modern");
  }

  @Test
  @DisplayName("should serialize all primitive and special float values")
  void testPrimitives() {
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
    Field fInt = new Field(false, "int", "i", new TraceValue.Primitive.Integer(32));
    Field fLong = new Field(false, "long", "l", new TraceValue.Primitive.Long(64L));
    Field fBool = new Field(false, "boolean", "bool", new TraceValue.Primitive.Boolean(true));
    Field fNull = new Field(false, "Object", "n", new TraceValue.Null());

    StackSnapshot frame =
        new StackSnapshot(
            "main",
            10,
            List.of(
                fFloat, fDouble, fDoubleInf, fDoubleNegInf, fDoubleNaN, fChar, fByte, fShort,
                fInt, fLong, fBool, fNull),
            Optional.empty());
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(frame), List.of(), Map.of(), new byte[0], new byte[0]);

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, false);
    Trace trace = serializer.createTrace("public class Test {}", snapshot);

    assertThat(trace.format()).isEqualTo("modern");
    assertThat(trace.steps()).hasSize(1);
    Step step = trace.steps().get(0);
    List<Variable> locals = step.callStack().get(0).locals();

    assertThat(locals.get(0).value()).isEqualTo(3.14f);
    assertThat(locals.get(1).value()).isEqualTo(2.718);
    assertThat(locals.get(2).value()).isEqualTo("Infinity");
    assertThat(locals.get(3).value()).isEqualTo("-Infinity");
    assertThat(locals.get(4).value()).isEqualTo("NaN");
    assertThat(locals.get(5).value()).isEqualTo("z");
    assertThat(locals.get(6).value()).isEqualTo((byte) 8);
    assertThat(locals.get(7).value()).isEqualTo((short) 16);
    assertThat(locals.get(8).value()).isEqualTo(32);
    assertThat(locals.get(9).value()).isEqualTo(64L);
    assertThat(locals.get(10).value()).isEqualTo(true);
    assertThat(locals.get(11).value()).isNull();
  }

  @Test
  @DisplayName("should serialize heap objects including arrays, strings, boxes, lambdas, and maps")
  void testHeapSerialization() {
    TraceValue.Object accountObj =
        new TraceValue.Object(
            "cs1302.Account",
            List.of(
                new Field(false, "String", "name", new TraceValue.Reference(101L)),
                new Field(false, "double", "bal", new TraceValue.Primitive.Double(100.0))));
    TraceValue.List arrayObj =
        new TraceValue.List(
            "int[]",
            List.of(
                new TraceValue.Primitive.Integer(1),
                new TraceValue.Primitive.Integer(2)));
    TraceValue.String strObj = new TraceValue.String("Alice");
    TraceValue.Primitive.Integer boxObj = new TraceValue.Primitive.Integer(42);
    TraceValue.Lambda lambdaObj = new TraceValue.Lambda("(x) -> x + 1");
    TraceValue.List listObj =
        new TraceValue.List("java.util.List", List.of(new TraceValue.Primitive.Integer(99)));
    TraceValue.Collection colObj =
        new TraceValue.Collection(List.of(new TraceValue.Primitive.Integer(88)));

    Map<TraceValue, TraceValue> mapEntries = new LinkedHashMap<>();
    mapEntries.put(new TraceValue.String("k1"), new TraceValue.Primitive.Integer(10));
    TraceValue.Map mapObj = new TraceValue.Map(mapEntries);

    Map<Long, TraceValue> heap = new LinkedHashMap<>();
    heap.put(1L, accountObj);
    heap.put(2L, arrayObj);
    heap.put(101L, strObj);
    heap.put(3L, boxObj);
    heap.put(4L, lambdaObj);
    heap.put(5L, listObj);
    heap.put(6L, colObj);
    heap.put(7L, mapObj);

    Field stat = new Field(true, "int", "MAX", new TraceValue.Primitive.Integer(100));
    Field refVar = new Field(false, "cs1302.Account", "acc", new TraceValue.Reference(1L));
    Field argsVar = new Field(false, "String[]", "args", new TraceValue.Reference(2L));

    StackSnapshot frame =
        new StackSnapshot(
            "main",
            5,
            List.of(argsVar, refVar),
            Optional.of(new ThisObject("cs1302.Driver", new TraceValue.Reference(999L))));
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(
            List.of(frame),
            List.of(stat),
            heap,
            "hello\n".getBytes(),
            "".getBytes());

    ModernTraceSerializer serializer = new ModernTraceSerializer(true, false, false);
    Trace trace = serializer.createTrace("public class Driver {}", snapshot);

    Step step = trace.steps().get(0);
    assertThat(step.stdout()).isEqualTo("hello\n");
    assertThat(step.statics()).hasSize(1);
    assertThat(step.statics().get(0).name()).isEqualTo("MAX");

    StackFrame sf = step.callStack().get(0);
    assertThat(sf.thisObject()).isEqualTo(new Reference(999L));
    // args should be removed by removeMainArgs
    assertThat(sf.locals()).hasSize(1);
    assertThat(sf.locals().get(0).name()).isEqualTo("acc");
    assertThat(sf.locals().get(0).value()).isEqualTo(new Reference(1L));

    Map<String, HeapObject> h = step.heap();
    assertThat(h.get("1").kind()).isEqualTo("object");
    assertThat(h.get("1").fields()).hasSize(2);
    assertThat(h.get("2").kind()).isEqualTo("array");
    assertThat(h.get("2").elements()).containsExactly(1, 2);
    assertThat(h.get("101").kind()).isEqualTo("string");
    assertThat(h.get("101").value()).isEqualTo("Alice");
    assertThat(h.get("3").kind()).isEqualTo("box");
    assertThat(h.get("4").kind()).isEqualTo("lambda");
    assertThat(h.get("5").kind()).isEqualTo("array");
    assertThat(h.get("6").kind()).isEqualTo("array");
    assertThat(h.get("7").kind()).isEqualTo("object");
  }

  @Test
  @DisplayName("should serialize multi-file traces with file attributes and handle removeMethodThis")
  void testMultiFileAndOptions() {
    ExecutionSnapshot s1 =
        new ExecutionSnapshot(
            List.of(
                new StackSnapshot(
                    "main", 8, List.of(), Optional.of(new ThisObject("D", new TraceValue.Reference(1L))), Optional.of("cs1302/Driver.java")),
                new StackSnapshot(
                    "calc", 12, List.of(), Optional.empty(), Optional.of("cs1302/Calc.java"))),
            List.of(),
            Map.of(),
            new byte[0],
            new byte[0],
            Optional.of("cs1302/Calc.java"));

    ExecutionSnapshot s2 =
        new ExecutionSnapshot(
            List.of(
                new StackSnapshot(
                    "main", 9, List.of(), Optional.empty(), Optional.of("cs1302/Driver.java"))),
            List.of(),
            Map.of(),
            new byte[0],
            new byte[0],
            Optional.of("cs1302/Driver.java"));

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, true);
    String multiSource =
        """
        // --- cs1302/Calc.java ---
        package cs1302;
        public class Calc {}
        // --- cs1302/Driver.java ---
        package cs1302;
        public class Driver {}
        """;

    Trace trace = serializer.createTrace(multiSource, List.of(s1, s2));
    assertThat(trace.steps()).hasSize(2);

    Step step1 = trace.steps().get(0);
    assertThat(step1.file()).isEqualTo("cs1302/Calc.java");
    assertThat(step1.callStack().get(0).file()).isEqualTo("cs1302/Driver.java");
    // removeMethodThis was true, so thisObject must be null
    assertThat(step1.callStack().get(0).thisObject()).isNull();
    assertThat(step1.callStack().get(1).file()).isEqualTo("cs1302/Calc.java");
  }

  @Test
  @DisplayName("should serialize breakpoints map in modern format")
  void testBreakpointsSerialization() {
    ExecutionSnapshot s1 =
        new ExecutionSnapshot(
            List.of(new StackSnapshot("main", 4, List.of(), Optional.empty())),
            List.of(),
            Map.of(),
            new byte[0],
            new byte[0]);

    ExecutionSnapshot s2 =
        new ExecutionSnapshot(
            List.of(new StackSnapshot("main", 5, List.of(), Optional.empty())),
            List.of(),
            Map.of(),
            new byte[0],
            new byte[0]);

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, false);
    Map<Integer, ExecutionSnapshot> singleBp = Map.of(4, s1);
    Trace trace1 = serializer.createBreakpointsTrace("code", singleBp);
    assertThat(trace1.breakpoints()).containsKey(4);

    Map<Integer, List<ExecutionSnapshot>> accumBp = Map.of(5, List.of(s1, s2));
    Trace trace2 = serializer.createBreakpointsTrace("code", accumBp);
    assertThat(trace2.breakpoints()).containsKey(5);

    Map<Integer, Object> otherMap = new LinkedHashMap<>();
    otherMap.put(1, "invalid-entry");
    otherMap.put(2, List.of(s1, "non-snapshot-item"));
    Trace trace3 = serializer.createBreakpointsTrace("code", otherMap);
    assertThat(trace3.breakpoints()).doesNotContainKey(1);
    assertThat(trace3.breakpoints()).containsKey(2);
  }

  @Test
  @DisplayName("should handle null fields and default heap values gracefully")
  void shouldHandleNullAndDefaultHeapValues() {
    Field nullField = new Field(false, "Object", "x", null);
    StackSnapshot frame = new StackSnapshot("main", 5, List.of(nullField), Optional.empty());
    Map<Long, TraceValue> heap = new LinkedHashMap<>();
    heap.put(99L, new TraceValue.Null());

    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(frame), List.of(), heap, new byte[0], new byte[0]);

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, false);
    Step step = serializer.createStep(snapshot, 1, false);

    assertThat(step.callStack().get(0).locals().get(0).value()).isNull();
    assertThat(step.heap().get("99").kind()).isEqualTo("object");
  }

  @Test
  @DisplayName("should handle empty stack and invalid byte decodings gracefully")
  void shouldHandleEmptyStackAndCorruptedOutputs() {
    byte[] invalidUtf8 = new byte[] {(byte) 0xFF, (byte) 0xFE};
    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(), List.of(), Map.of(), invalidUtf8, invalidUtf8);

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, false);
    Step step = serializer.createStep(snapshot, 1, false);

    assertThat(step.method()).isEmpty();
    assertThat(step.line()).isEqualTo(0L);
    assertThat(step.callStack()).isEmpty();
    assertThat(step.stdout()).isEmpty();
    assertThat(step.stderr()).isEmpty();
  }

  @Test
  @DisplayName("should detect multi-file from snapshots without stream delimiter in source")
  void shouldDetectMultiFileWithoutDelimiter() {
    ExecutionSnapshot s1 =
        new ExecutionSnapshot(
            List.of(
                new StackSnapshot(
                    "main", 6, List.of(), Optional.empty(), Optional.of("A.java")),
                new StackSnapshot(
                    "helper", 10, List.of(), Optional.empty(), Optional.of("B.java"))),
            List.of(),
            Map.of(),
            new byte[0],
            new byte[0],
            Optional.of("B.java"));

    ModernTraceSerializer serializer = new ModernTraceSerializer(false, false, false);
    Trace trace = serializer.createTrace("public class A {}", s1);
    assertThat(trace.steps().get(0).file()).isEqualTo("B.java");
    assertThat(trace.steps().get(0).callStack().get(0).file()).isEqualTo("A.java");
  }

  @Test
  @DisplayName("should filter main args only in the bottom-most frame (frame 0)")
  void shouldFilterMainArgsOnlyInMainFrame() {
    Field argsField = new Field(false, "String[]", "args", new TraceValue.Reference(1L));
    StackSnapshot frame0 = new StackSnapshot("main", 5, List.of(argsField), Optional.empty());
    StackSnapshot frame1 = new StackSnapshot("helper", 12, List.of(argsField), Optional.empty());

    ExecutionSnapshot snapshot =
        new ExecutionSnapshot(List.of(frame0, frame1), List.of(), Map.of(), new byte[0], new byte[0]);

    ModernTraceSerializer serializer = new ModernTraceSerializer(true, false, false);
    Step step = serializer.createStep(snapshot, 1, false);

    // Frame 0 should have args filtered
    assertThat(step.callStack().get(0).locals()).isEmpty();
    // Frame 1 should keep args
    assertThat(step.callStack().get(1).locals()).hasSize(1);
  }

  @Test
  @DisplayName("should format types according to TypeStyle in ModernTraceSerializer")
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

    ModernTraceSerializer simpleSerializer =
        new ModernTraceSerializer(false, false, false, cs1302.tracer.model.TypeStyle.SIMPLE);
    Step simpleStep = simpleSerializer.createStep(snapshot, 1, false);

    assertThat(simpleStep.statics().get(0).type()).isEqualTo("String");
    assertThat(simpleStep.callStack().get(0).locals().get(0).type()).isEqualTo("String");
    assertThat(simpleStep.heap().get("101").type()).isEqualTo("Pair<String, Integer>");
    assertThat(simpleStep.heap().get("101").fields().get(0).type()).isEqualTo("String");
  }
}
