package cs1302.tracer.serialize;

import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.Field;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.TraceValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Container class for methods that help serialize a trace into the OnlinePythonTutor format.
 *
 * @param removeMainArgs True if the `args` parameter to main should be excluded from serializations
 *     produced by this instance.
 * @param inlineStrings True if strings should be inlined as literals in serializations produced by
 *     this instance, otherwise they are serialized as references.
 * @param removeMethodThis True if the value of `this` for method invocations should be excluded
 *     from serializations produced by this instance.
 */
public record PyTutorSerializer(
    boolean removeMainArgs, boolean inlineStrings, boolean removeMethodThis) {

  /**
   * Serialize an execution snapshot into the OnlinePythonTutor trace format.
   *
   * @param javaSource The source code for the program corresponding to the execution snapshot.
   * @param snapshot The snapshot that should be serialized.
   * @return The serialized execution snapshot.
   */
  public JSONObject serialize(String javaSource, ExecutionSnapshot snapshot) {
    String currentMethod = snapshot.stack().getLast().methodName();
    long currentLine = snapshot.stack().getLast().methodLine();

    JSONObject serializedStatics =
        new JSONObject(
            snapshot.statics().stream()
                .collect(
                    Collectors.toMap(
                        ExecutionSnapshot.Field::identifier,
                        s -> serializeTraceValue(s.value(), snapshot.heap()))));

    JSONArray orderedStatics =
        new JSONArray(
            snapshot.statics().stream().map(ExecutionSnapshot.Field::identifier).toList());

    Map<String, JSONObject> globalsAttrs =
        snapshot.statics().stream()
            .collect(
                Collectors.toMap(
                    Field::identifier,
                    f -> new JSONObject().put("type", f.typeName()).put("final", f.isFinal())));

    JSONObject serializedHeap =
        new JSONObject(
            snapshot.heap().entrySet().stream()
                .filter(e -> !(inlineStrings && e.getValue() instanceof TraceValue.String))
                .collect(
                    Collectors.toMap(
                        Entry::getKey, e -> serializeTraceValue(e.getValue(), snapshot.heap()))));

    Map<String, JSONObject> heapAttrs = new HashMap<>();
    for (Entry<Long, TraceValue> e : snapshot.heap().entrySet()) {
      String key = e.getKey().toString();
      switch (e.getValue()) {
        case TraceValue.Object o -> {
          JSONArray objectTypes =
              new JSONArray(o.fields().stream().map(ExecutionSnapshot.Field::typeName).toList());
          JSONArray objectFinals =
              new JSONArray(o.fields().stream().map(ExecutionSnapshot.Field::isFinal).toList());
          heapAttrs.put(key, new JSONObject().put("type", objectTypes).put("final", objectFinals));
        }
        case TraceValue.List a -> {
          heapAttrs.put(key, new JSONObject().put("type", a.typeName()));
        }
        default -> {}
      }
    }

    if (removeMainArgs) {
      // don't include String[] args in main
      snapshot.stack().getFirst().visibleVariables().removeFirst();
    } // if

    JSONArray serializedStackToRender =
        new JSONArray()
            .putAll(
                IntStream.range(0, snapshot.stack().size())
                    .boxed()
                    .map(
                        uniqueFrameId ->
                            serializeStackSnapshot(
                                snapshot.stack().get(uniqueFrameId),
                                uniqueFrameId,
                                uniqueFrameId == snapshot.stack().size() - 1,
                                snapshot.heap())) // map
                    .toArray());

    String stdout;
    try {
      stdout =
          Charset.defaultCharset()
              .newDecoder()
              .decode(ByteBuffer.wrap(snapshot.stdout()))
              .toString();
    } catch (CharacterCodingException cce) {
      stdout = "";
    }

    String stderr;
    try {
      stderr =
          Charset.defaultCharset()
              .newDecoder()
              .decode(ByteBuffer.wrap(snapshot.stderr()))
              .toString();
    } catch (CharacterCodingException cce) {
      stderr = "";
    }

    return new JSONObject()
        .put("code", javaSource)
        .put("stdin", "")
        .put(
            "trace",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("stdout", new String(stdout))
                        .put("stderr", new String(stderr)) // NOTE this isn't currently used
                        .put("event", "step_line")
                        .put("func_name", currentMethod)
                        .put("line", currentLine)
                        .put("stack_to_render", serializedStackToRender)
                        .put("globals", serializedStatics)
                        .put("globals_attrs", new JSONObject(globalsAttrs))
                        .put("ordered_globals", orderedStatics)
                        .put("heap", serializedHeap)
                        .put("heap_attrs", new JSONObject(heapAttrs))))
        .put("userlog", "");
  }

  /**
   * Serialize a stack snapshot into the OnlinePythonTutor stack frame format.
   *
   * @param stackSnapshot The snapshot to serialize.
   * @param uniqueFrameId A unique ID for the frame.
   * @param isCurrentFrame True if this is the top-level/executing/current frame, false otherwise.
   * @param heap The program's heap.
   * @return The serialization of the snapshot.
   */
  private JSONObject serializeStackSnapshot(
      StackSnapshot stackSnapshot,
      int uniqueFrameId,
      boolean isCurrentFrame,
      Map<Long, TraceValue> heap) {

    Map<String, Object> encodedLocals =
        stackSnapshot.visibleVariables().stream()
            .collect(
                Collectors.toMap(
                    ExecutionSnapshot.Field::identifier,
                    field -> serializeTraceValue(field.value(), heap)) // toMap
                );

    JSONArray orderedVarnames =
        new JSONArray(
            stackSnapshot.visibleVariables().stream()
                .map(ExecutionSnapshot.Field::identifier)
                .toList());

    Map<String, JSONObject> localsAttrs =
        stackSnapshot.visibleVariables().stream()
            .collect(
                Collectors.toMap(
                    Field::identifier,
                    f -> new JSONObject().put("type", f.typeName()).put("final", f.isFinal())));

    stackSnapshot
        .thisObject()
        .ifPresent(
            t -> {
              if (removeMethodThis) {
                return;
              }

              JSONArray newOrderedVarnames = new JSONArray().put("this").putAll(orderedVarnames);
              orderedVarnames.clear();
              orderedVarnames.putAll(newOrderedVarnames);

              localsAttrs
                  .put("this", new JSONObject().put("type", t.typeName()))
                  .put("final", true);
              encodedLocals.put("this", serializeTraceValue(t.value(), heap));
            });

    String funcName =
        String.format("%s:%d", stackSnapshot.methodName(), stackSnapshot.methodLine());

    return new JSONObject()
        .put("func_name", funcName)
        .put("encoded_locals", new JSONObject(encodedLocals))
        .put("locals_attrs", new JSONObject(localsAttrs))
        .put("ordered_varnames", orderedVarnames)
        .put("parent_frame_id_list", new JSONArray())
        .put("is_highlighted", isCurrentFrame)
        .put("is_zombie", false)
        .put("is_parent", false)
        .put("unique_hash", String.valueOf(uniqueFrameId))
        .put("frame_id", uniqueFrameId);
  } // serializeStackSnapshot

  /**
   * Serialize a TraceValue into a Boolean, Double, Integer, JSONArray, JSONObject, Long, String, or
   * the JSONObject.NULL object
   *
   * @param value The value to serialize.
   * @param heap A mapping of heap IDs to other TraceValues so that compound data types can be
   *     properly serialized.
   * @return A Boolean, Double, Integer, JSONArray, JSONObject, Long, String, or JSONObject.NULL
   *     that corresponds to the TraceValue
   */
  private Object serializeTraceValue(TraceValue value, Map<Long, TraceValue> heap) {
    return switch (value) {
      case TraceValue.Primitive.Float floatValue ->
          new JSONArray().put("NUMBER-LITERAL").put(Float.toString(floatValue.value()));

      case TraceValue.Primitive.Double doubleValue -> {
        if (doubleValue.value() == Double.POSITIVE_INFINITY) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("Infinity");
        } else if (doubleValue.value() == Double.NEGATIVE_INFINITY) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("-Infinity");
        } else if (Double.isNaN(doubleValue.value())) {
          yield new JSONArray().put("SPECIAL_FLOAT").put("NaN");
        } else {
          yield new JSONArray().put("NUMBER-LITERAL").put(Double.toString(doubleValue.value()));
        } // if
      } // case

      case TraceValue.Primitive.Character charValue ->
          new JSONArray().put("CHAR-LITERAL").put(Character.toString(charValue.value()));

      case TraceValue.Primitive primitiveValue -> primitiveValue.toWrapperObject();

      case TraceValue.Reference referenceValue -> {
        TraceValue target = heap.get(referenceValue.uniqueId());
        if (target instanceof TraceValue.String && inlineStrings) {
          yield serializeTraceValue(target, heap);
        } else {
          yield new JSONArray().put("REF").put(referenceValue.uniqueId());
        } // if
      } // case

      case TraceValue.Null nullValue -> JSONObject.NULL;

      case TraceValue.String stringValue -> {
        if (inlineStrings) {
          yield stringValue.value();
        } else {
          yield new JSONArray()
              .put("INSTANCE")
              .put("String")
              .put(new JSONArray().put("___NO_LABEL!___").put(stringValue.value()));
        } // if
      } // case

      case TraceValue.List listValue -> {
        Object[] values =
            listValue.value().stream().map(e -> serializeTraceValue(e, heap)).toArray();
        yield new JSONArray().put("LIST").putAll(values);
      } // case

      case TraceValue.Collection collectionValue -> {
        Object[] values =
            collectionValue.value().stream().map(e -> serializeTraceValue(e, heap)).toArray();
        yield new JSONArray().put("SET").putAll(values);
      } // case

      case TraceValue.Map mapValue -> {
        Object[] values =
            mapValue.value().entrySet().stream()
                .map(
                    entry ->
                        new JSONArray()
                            .put(serializeTraceValue(entry.getKey(), heap))
                            .put(serializeTraceValue(entry.getValue(), heap)))
                .toArray();
        yield new JSONArray().put("DICT").putAll(values);
      } // case

      case TraceValue.Object objectValue -> {
        // System.err.println(objectValue.
        yield new JSONArray()
            .put("INSTANCE")
            .put(objectValue.classFqn())
            .putAll(
                objectValue.fields().stream()
                    .map(
                        field ->
                            new JSONArray()
                                .put(field.identifier())
                                .put(serializeTraceValue(field.value(), heap)))
                    .toArray());
      } // case
      case TraceValue.Lambda lambdaValue -> {
        yield new JSONArray().put("JAVA_LAMBDA").put(lambdaValue.implementation());
      }
    };
  }
}
