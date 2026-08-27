package cs1302.tracer.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.model.pytutor.RenderStackFrame;
import cs1302.tracer.model.pytutor.TraceStep;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.Field;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot;
import cs1302.tracer.trace.ExecutionSnapshot.StackSnapshot.ThisObject;
import cs1302.tracer.trace.TraceValue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Container class for methods that help serialize a trace into the OnlinePythonTutor format using
 * Gson.
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

  private static final Gson GSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

  /**
   * Get the configured Gson instance.
   *
   * @return The Gson instance.
   */
  public static Gson getGson() {
    return GSON;
  }

  /**
   * Determine if the given source or snapshots represent a multi-file program.
   *
   * @param javaSource The raw source string.
   * @param snapshots The execution snapshots.
   * @return True if multi-file code is present.
   */
  private boolean isMultiFileSource(String javaSource, List<ExecutionSnapshot> snapshots) {
    if (CompilationHelper.DELIMITER_PATTERN.matcher(javaSource).find()) {
      return true;
    }
    Set<String> distinctFiles = new HashSet<>();
    for (ExecutionSnapshot snapshot : snapshots) {
      snapshot.sourcePath().ifPresent(distinctFiles::add);
      for (StackSnapshot frame : snapshot.stack()) {
        frame.sourcePath().ifPresent(distinctFiles::add);
      }
    }
    return distinctFiles.size() > 1;
  }

  /**
   * Create a {@link PyTutorTrace} model representing the given snapshot.
   *
   * @param javaSource The source code for the program corresponding to the execution snapshot.
   * @param snapshot The snapshot that should be serialized.
   * @return The structured PyTutorTrace model.
   */
  public PyTutorTrace createTrace(String javaSource, ExecutionSnapshot snapshot) {
    boolean isMultiFile = isMultiFileSource(javaSource, List.of(snapshot));
    TraceStep step = createTraceStep(snapshot, isMultiFile);
    return new PyTutorTrace(javaSource, "", List.of(step), "");
  }

  /**
   * Create a {@link PyTutorTrace} model representing a chronological sequence of snapshots.
   *
   * @param javaSource The source code for the program corresponding to the execution snapshots.
   * @param snapshots The list of snapshots in chronological order that should be serialized.
   * @return The structured PyTutorTrace model.
   */
  public PyTutorTrace createTrace(String javaSource, List<ExecutionSnapshot> snapshots) {
    boolean isMultiFile = isMultiFileSource(javaSource, snapshots);
    List<TraceStep> steps = snapshots.stream().map(s -> createTraceStep(s, isMultiFile)).toList();
    return new PyTutorTrace(javaSource, "", steps, "");
  }

  /**
   * Create a {@link TraceStep} model representing the given snapshot.
   *
   * @param snapshot The snapshot that should be transformed.
   * @return The structured TraceStep model.
   */
  public TraceStep createTraceStep(ExecutionSnapshot snapshot) {
    return createTraceStep(snapshot, false);
  }

  /**
   * Create a {@link TraceStep} model representing the given snapshot with multi-file setting.
   *
   * @param snapshot The snapshot that should be transformed.
   * @param isMultiFile Whether to include source file path metadata.
   * @return The structured TraceStep model.
   */
  public TraceStep createTraceStep(ExecutionSnapshot snapshot, boolean isMultiFile) {
    String currentMethod =
        snapshot.stack().isEmpty() ? "" : snapshot.stack().getLast().methodName();
    long currentLine = snapshot.stack().isEmpty() ? 0 : snapshot.stack().getLast().methodLine();

    Map<String, Object> serializedStatics = new LinkedHashMap<>();
    List<String> orderedStatics = new ArrayList<>();
    Map<String, Object> globalsAttrs = new LinkedHashMap<>();

    // Map heap references to declared type names from statics, stack, and object fields
    Map<Long, String> declaredTypes = new HashMap<>();

    for (Field f : snapshot.statics()) {
      serializedStatics.put(f.identifier(), serializeTraceValue(f.value(), snapshot.heap()));
      orderedStatics.add(f.identifier());
      globalsAttrs.put(f.identifier(), Map.of("type", f.typeName(), "final", f.isFinal()));
      if (f.value() instanceof TraceValue.Reference ref) {
        declaredTypes.put(ref.uniqueId(), f.typeName());
      }
    }

    for (StackSnapshot frame : snapshot.stack()) {
      for (Field f : frame.visibleVariables()) {
        if (f.value() instanceof TraceValue.Reference ref) {
          declaredTypes.put(ref.uniqueId(), f.typeName());
        }
      }
      if (frame.thisObject().isPresent()) {
        ThisObject to = frame.thisObject().get();
        declaredTypes.put(to.value().uniqueId(), to.typeName());
      }
    }

    for (TraceValue tv : snapshot.heap().values()) {
      if (tv instanceof TraceValue.Object obj) {
        for (Field f : obj.fields()) {
          if (f.value() instanceof TraceValue.Reference ref) {
            declaredTypes.put(ref.uniqueId(), f.typeName());
          }
        }
      }
    }

    Map<String, Object> serializedHeap = new LinkedHashMap<>();
    for (Entry<Long, TraceValue> e : snapshot.heap().entrySet()) {
      if (!(inlineStrings && e.getValue() instanceof TraceValue.String)) {
        serializedHeap.put(
            e.getKey().toString(), serializeTraceValue(e.getValue(), snapshot.heap()));
      }
    }

    Map<String, Object> heapAttrs = new LinkedHashMap<>();
    for (Entry<Long, TraceValue> e : snapshot.heap().entrySet()) {
      String key = e.getKey().toString();
      Long id = e.getKey();
      TraceValue val = e.getValue();
      switch (val) {
        case TraceValue.Object o -> {
          List<String> objectTypes =
              o.fields().stream().map(Field::typeName).collect(Collectors.toList());
          heapAttrs.put(key, Map.of("type", objectTypes));
        }
        case TraceValue.List a -> {
          String resolvedType = resolveListType(id, a, declaredTypes, snapshot.heap());
          heapAttrs.put(key, Map.of("type", resolvedType));
        }
        case TraceValue.Map m -> {
          String resolvedType = resolveMapType(id, m, declaredTypes, snapshot.heap());
          heapAttrs.put(key, Map.of("type", resolvedType));
        }
        case TraceValue.Collection c -> {
          String resolvedType = resolveCollectionType(id, c, declaredTypes, snapshot.heap());
          heapAttrs.put(key, Map.of("type", resolvedType));
        }
        case TraceValue.String s -> {
          heapAttrs.put(key, Map.of("type", "java.lang.String"));
        }
        case TraceValue.Primitive.Integer i -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Integer"));
        }
        case TraceValue.Primitive.Double d -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Double"));
        }
        case TraceValue.Primitive.Boolean b -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Boolean"));
        }
        case TraceValue.Primitive.Long l -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Long"));
        }
        case TraceValue.Primitive.Float f -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Float"));
        }
        case TraceValue.Primitive.Character c -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Character"));
        }
        case TraceValue.Primitive.Byte b -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Byte"));
        }
        case TraceValue.Primitive.Short s -> {
          heapAttrs.put(key, Map.of("type", "java.lang.Short"));
        }
        case TraceValue.Lambda l -> {
          heapAttrs.put(key, Map.of("type", "lambda"));
        }
        default -> {}
      }
    }

    List<RenderStackFrame> serializedStackToRender = new ArrayList<>();
    int stackSize = snapshot.stack().size();
    for (int uniqueFrameId = 0; uniqueFrameId < stackSize; uniqueFrameId++) {
      boolean isCurrent = (uniqueFrameId == stackSize - 1);
      boolean isMain = (uniqueFrameId == 0);
      serializedStackToRender.add(
          serializeStackSnapshot(
              snapshot.stack().get(uniqueFrameId),
              uniqueFrameId,
              isCurrent,
              isMain,
              snapshot.heap(),
              isMultiFile));
    }

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

    String stepFile = isMultiFile ? snapshot.sourcePath().orElse(null) : null;

    return new TraceStep(
        stdout,
        stderr,
        "step_line",
        currentMethod,
        currentLine,
        serializedStackToRender,
        serializedStatics,
        globalsAttrs,
        orderedStatics,
        serializedHeap,
        heapAttrs,
        stepFile);
  }

  /**
   * Serialize an execution snapshot into the OnlinePythonTutor JSON trace string.
   *
   * @param javaSource The source code for the program corresponding to the execution snapshot.
   * @param snapshot The snapshot that should be serialized.
   * @return The serialized execution snapshot as a JSON string.
   */
  public String serialize(String javaSource, ExecutionSnapshot snapshot) {
    PyTutorTrace trace = createTrace(javaSource, snapshot);
    return GSON.toJson(trace);
  }

  /**
   * Serialize a stack snapshot into the OnlinePythonTutor stack frame format.
   *
   * @param stackSnapshot The snapshot to serialize.
   * @param uniqueFrameId A unique ID for the frame.
   * @param isCurrentFrame True if this is the top-level/executing/current frame, false otherwise.
   * @param isMainFrame True if this is the bottommost frame (frame 0).
   * @param heap The program's heap.
   * @param isMultiFile Whether to serialize source file metadata.
   * @return The RenderStackFrame model.
   */
  private RenderStackFrame serializeStackSnapshot(
      StackSnapshot stackSnapshot,
      int uniqueFrameId,
      boolean isCurrentFrame,
      boolean isMainFrame,
      Map<Long, TraceValue> heap,
      boolean isMultiFile) {

    Map<String, Object> encodedLocals = new LinkedHashMap<>();
    List<String> orderedVarnames = new ArrayList<>();
    Map<String, Object> localsAttrs = new LinkedHashMap<>();

    stackSnapshot
        .thisObject()
        .ifPresent(
            t -> {
              if (!removeMethodThis) {
                orderedVarnames.add("this");
                localsAttrs.put("this", Map.of("type", t.typeName(), "final", true));
                encodedLocals.put("this", serializeTraceValue(t.value(), heap));
              }
            });

    for (Field field : stackSnapshot.visibleVariables()) {
      if (removeMainArgs && isMainFrame && "args".equals(field.identifier())) {
        continue;
      }
      orderedVarnames.add(field.identifier());
      localsAttrs.put(
          field.identifier(), Map.of("type", field.typeName(), "final", field.isFinal()));
      encodedLocals.put(field.identifier(), serializeTraceValue(field.value(), heap));
    }

    String funcName =
        String.format("%s:%d", stackSnapshot.methodName(), stackSnapshot.methodLine());
    String frameFile = isMultiFile ? stackSnapshot.sourcePath().orElse(null) : null;

    return new RenderStackFrame(
        funcName,
        encodedLocals,
        localsAttrs,
        orderedVarnames,
        Collections.emptyList(),
        isCurrentFrame,
        false,
        false,
        String.valueOf(uniqueFrameId),
        uniqueFrameId,
        frameFile);
  }

  /**
   * Serialize a TraceValue into a standard Java object / List / Map representation suitable for
   * Gson serialization into the PyTutor specification.
   *
   * @param value The value to serialize.
   * @param heap A mapping of heap IDs to other TraceValues.
   * @return An object, List, Map, or null corresponding to the PyTutor JSON value.
   */
  private Object serializeTraceValue(TraceValue value, Map<Long, TraceValue> heap) {
    return switch (value) {
      case null -> null;

      case TraceValue.Primitive.Float floatValue -> List.of(
          "NUMBER-LITERAL", Float.toString(floatValue.value()));

      case TraceValue.Primitive.Double doubleValue -> {
        if (doubleValue.value() == Double.POSITIVE_INFINITY) {
          yield List.of("SPECIAL_FLOAT", "Infinity");
        } else if (doubleValue.value() == Double.NEGATIVE_INFINITY) {
          yield List.of("SPECIAL_FLOAT", "-Infinity");
        } else if (Double.isNaN(doubleValue.value())) {
          yield List.of("SPECIAL_FLOAT", "NaN");
        } else {
          yield List.of("NUMBER-LITERAL", Double.toString(doubleValue.value()));
        }
      }

      case TraceValue.Primitive.Character charValue -> List.of(
          "CHAR-LITERAL", Character.toString(charValue.value()));

      case TraceValue.Primitive primitiveValue -> primitiveValue.toWrapperObject();

      case TraceValue.Reference referenceValue -> {
        TraceValue target = heap.get(referenceValue.uniqueId());
        if (target instanceof TraceValue.String && inlineStrings) {
          yield serializeTraceValue(target, heap);
        } else {
          yield List.of("REF", referenceValue.uniqueId());
        }
      }

      case TraceValue.Null nullValue -> null;

      case TraceValue.String stringValue -> {
        if (inlineStrings) {
          yield stringValue.value();
        } else {
          yield List.of("INSTANCE", "String", List.of("___NO_LABEL!___", stringValue.value()));
        }
      }

      case TraceValue.List listValue -> {
        List<Object> list = new ArrayList<>();
        list.add("LIST");
        for (TraceValue elem : listValue.value()) {
          list.add(serializeTraceValue(elem, heap));
        }
        yield list;
      }

      case TraceValue.Collection collectionValue -> {
        List<Object> list = new ArrayList<>();
        list.add("SET");
        for (TraceValue elem : collectionValue.value()) {
          list.add(serializeTraceValue(elem, heap));
        }
        yield list;
      }

      case TraceValue.Map mapValue -> {
        List<Object> list = new ArrayList<>();
        list.add("DICT");
        for (Entry<? extends TraceValue, ? extends TraceValue> entry :
            mapValue.value().entrySet()) {
          list.add(
              Arrays.asList(
                  serializeTraceValue(entry.getKey(), heap),
                  serializeTraceValue(entry.getValue(), heap)));
        }
        yield list;
      }

      case TraceValue.Object objectValue -> {
        List<Object> list = new ArrayList<>();
        list.add("INSTANCE");
        list.add(objectValue.classFqn());
        for (Field field : objectValue.fields()) {
          list.add(Arrays.asList(field.identifier(), serializeTraceValue(field.value(), heap)));
        }
        yield list;
      }

      case TraceValue.Lambda lambdaValue -> List.of("JAVA_LAMBDA", lambdaValue.implementation());
    };
  }

  private static Optional<String> extractGenericArguments(String declaredType) {
    if (declaredType == null) {
      return Optional.empty();
    }
    int start = declaredType.indexOf('<');
    int end = declaredType.lastIndexOf('>');
    if (start != -1 && end > start) {
      return Optional.of(declaredType.substring(start, end + 1));
    }
    return Optional.empty();
  }

  private static String resolveListType(
      Long id, TraceValue.List list, Map<Long, String> declaredTypes, Map<Long, TraceValue> heap) {
    String typeName = list.typeName();
    if (typeName.endsWith("[]")) {
      return typeName;
    }
    String declared = declaredTypes.get(id);
    Optional<String> genericArgs = extractGenericArguments(declared);
    if (genericArgs.isPresent()) {
      return typeName + genericArgs.get();
    }
    if (!list.value().isEmpty()) {
      String elemType = sampleElementType(list.value(), heap);
      if (elemType != null) {
        return typeName + "<" + elemType + ">";
      }
    }
    return typeName;
  }

  private static String resolveCollectionType(
      Long id, TraceValue.Collection col, Map<Long, String> declaredTypes, Map<Long, TraceValue> heap) {
    String typeName = col.typeName();
    String declared = declaredTypes.get(id);
    Optional<String> genericArgs = extractGenericArguments(declared);
    if (genericArgs.isPresent()) {
      return typeName + genericArgs.get();
    }
    if (!col.value().isEmpty()) {
      String elemType = sampleElementType(col.value(), heap);
      if (elemType != null) {
        return typeName + "<" + elemType + ">";
      }
    }
    return typeName;
  }

  private static String resolveMapType(
      Long id, TraceValue.Map map, Map<Long, String> declaredTypes, Map<Long, TraceValue> heap) {
    String typeName = map.typeName();
    String declared = declaredTypes.get(id);
    Optional<String> genericArgs = extractGenericArguments(declared);
    if (genericArgs.isPresent()) {
      return typeName + genericArgs.get();
    }
    if (!map.value().isEmpty()) {
      String keyType = sampleElementType(map.value().keySet(), heap);
      String valType = sampleElementType(map.value().values(), heap);
      if (keyType != null && valType != null) {
        return typeName + "<" + keyType + ", " + valType + ">";
      }
    }
    return typeName;
  }

  private static String sampleElementType(
      java.util.Collection<? extends TraceValue> elements, Map<Long, TraceValue> heap) {
    String inferred = null;
    for (TraceValue elem : elements) {
      String type = getSimpleTypeName(elem, heap);
      if (type == null) {
        return null;
      }
      if (inferred == null) {
        inferred = type;
      } else if (!inferred.equals(type)) {
        return null;
      }
    }
    return inferred;
  }

  private static String getSimpleTypeName(TraceValue value, Map<Long, TraceValue> heap) {
    if (value instanceof TraceValue.Reference ref) {
      TraceValue target = heap.get(ref.uniqueId());
      if (target != null) {
        return getSimpleTypeName(target, heap);
      }
    }
    return switch (value) {
      case TraceValue.String s -> "String";
      case TraceValue.Primitive.Integer i -> "Integer";
      case TraceValue.Primitive.Double d -> "Double";
      case TraceValue.Primitive.Boolean b -> "Boolean";
      case TraceValue.Primitive.Long l -> "Long";
      case TraceValue.Primitive.Float f -> "Float";
      case TraceValue.Primitive.Character c -> "Character";
      case TraceValue.Primitive.Byte b -> "Byte";
      case TraceValue.Primitive.Short s -> "Short";
      case TraceValue.Object o -> {
        String fqn = o.classFqn();
        int lastDot = fqn.lastIndexOf('.');
        yield (lastDot != -1) ? fqn.substring(lastDot + 1) : fqn;
      }
      case TraceValue.List l -> l.typeName();
      case TraceValue.Map m -> m.typeName();
      case TraceValue.Collection c -> c.typeName();
      default -> null;
    };
  }
}
