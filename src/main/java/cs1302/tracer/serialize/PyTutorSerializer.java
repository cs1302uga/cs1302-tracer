package cs1302.tracer.serialize;

import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.json.JSONArray;
import org.json.JSONObject;

import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.TraceValue;

public class PyTutorSerializer {
  public static JSONObject serialize(String javaSource, ExecutionSnapshot snapshot, boolean inlineStrings) {
    String currentMethod = snapshot.stack().getLast().methodName();
    long currentLine = snapshot.stack().getLast().methodLine();

    JSONObject serializedStatics = new JSONObject(snapshot.statics().stream()
        .collect(Collectors.toMap(ExecutionSnapshot.Field::identifier,
            s -> serializeTraceValue(s.value(), snapshot.heap(), inlineStrings))));

    JSONArray orderedStatics = new JSONArray(snapshot.statics().stream()
        .map(ExecutionSnapshot.Field::identifier)
        .toArray());

    JSONObject serializedHeap = new JSONObject(snapshot.heap().entrySet().stream()
        .filter(e -> !(inlineStrings && e.getValue() instanceof TraceValue.String))
        .collect(Collectors.toMap(Entry::getKey,
            e -> serializeTraceValue(e.getValue(), snapshot.heap(), inlineStrings))));

    JSONArray serializedStackToRender = new JSONArray(
        IntStream.range(0, snapshot.stack().size()).map(i -> snapshot.stack().size() - i - 1).boxed()
            .map(i -> serializeStackSnapshot(snapshot.stack().get(i), i, snapshot.heap(), inlineStrings))
            .toArray());

    return new JSONObject()
        .put("code", javaSource)
        .put("stdin", "")
        .put("trace", new JSONArray()
            .put(new JSONObject()
                .put("stdout", "")
                .put("event", "step_line")
                .put("func_name", currentMethod)
                .put("line", currentLine)
                .put("stack_to_render", serializedStackToRender)
                .put("globals", serializedStatics)
                .put("ordered_globals", orderedStatics)
                .put("heap", serializedHeap)))
        .put("userlog", "");
  }

  private static JSONObject serializeStackSnapshot(StackSnapshot stackSnapshot, int uniqueFrameId,
      Map<Long, TraceValue> heap, boolean inlineStrings) {
    Map<String, Object> encodedLocals = stackSnapshot.visibleVariables().stream()
        .collect(Collectors.toMap(ExecutionSnapshot.Field::identifier,
            e -> serializeTraceValue(e.value(), heap, inlineStrings)));

    return new JSONObject()
        .put("func_name", String.format("%s:%d", stackSnapshot.methodName(), stackSnapshot.methodLine()))
        .put("encoded_locals", new JSONObject(encodedLocals))
        .put("ordered_varnames", new JSONArray(encodedLocals.keySet()))
        .put("parent_frame_id_list", new JSONArray())
        .put("is_highlighted", uniqueFrameId == 0)
        .put("is_zombie", false)
        .put("is_parent", false)
        .put("unique_hash", String.valueOf(uniqueFrameId))
        .put("frame_id", uniqueFrameId);
  }

  /**
   * Serialize a TraceValue into a Boolean, Double, Integer, JSONArray,
   * JSONObject, Long, String, or the JSONObject.NULL object
   */
  private static Object serializeTraceValue(TraceValue value, Map<Long, TraceValue> heap, boolean inlineStrings) {
    return switch (value) {
      // TODO does this produce identical results? floating point is weird.
      case TraceValue.Primitive.Float f ->
        serializeTraceValue(new TraceValue.Primitive.Double(f.value()), heap, inlineStrings);
      case TraceValue.Primitive.Double d -> {
        if (d.value() == Double.POSITIVE_INFINITY) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("Infinity");
        } else if (d.value() == Double.NEGATIVE_INFINITY) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("-Infinity");
        } else if (Double.isNaN(d.value())) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("NaN");
        } else {
          yield d.toWrapperObject();
        }
      }
      case TraceValue.Primitive tp -> tp.toWrapperObject();
      case TraceValue.Reference tr -> {
        TraceValue referencedValue = heap.get(tr.uniqueId());
        if (referencedValue instanceof TraceValue.String && inlineStrings) {
          yield serializeTraceValue(referencedValue, heap, inlineStrings);
        } else {
          yield new JSONArray().put("REF").put(tr.uniqueId());
        }
      }
      case TraceValue.Null n -> JSONObject.NULL;
      case TraceValue.String s -> {
        if (inlineStrings) {
          yield s.value();
        } else {
          yield new JSONArray().put("INSTANCE").put("String").put(new JSONArray().put("").put(s.value()));
        }
      }
      case TraceValue.List l ->
        new JSONArray()
            .put("LIST")
            .putAll(l.value().stream().map(e -> serializeTraceValue(e, heap, inlineStrings)).toArray());
      case TraceValue.Collection c ->
        new JSONArray()
            .put("SET")
            .putAll(c.value().stream().map(e -> serializeTraceValue(e, heap, inlineStrings)).toArray());
      case TraceValue.Map m ->
        new JSONArray().put("DICT")
            .putAll(m.value().entrySet().stream()
                .map(e -> new JSONArray().put(serializeTraceValue(e.getKey(), heap, inlineStrings))
                    .put(serializeTraceValue(e.getValue(), heap, inlineStrings)))
                .toArray());
      case TraceValue.Object o ->
        new JSONArray().put("INSTANCE").put(o.classFqn())
            .putAll(o.fields().stream()
                .map(f -> new JSONArray().put(f.identifier()).put(serializeTraceValue(f.value(), heap, inlineStrings)))
                .toArray());
    };

  }
}
