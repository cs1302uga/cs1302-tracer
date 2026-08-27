package cs1302.tracer.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cs1302.tracer.CompilationHelper;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Serializes {@link ExecutionSnapshot} objects into the modern clean JSON trace format.
 */
public class ModernTraceSerializer {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private final boolean removeMainArgs;
  private final boolean inlineStrings;
  private final boolean removeMethodThis;

  public ModernTraceSerializer(
      boolean removeMainArgs, boolean inlineStrings, boolean removeMethodThis) {
    this.removeMainArgs = removeMainArgs;
    this.inlineStrings = inlineStrings;
    this.removeMethodThis = removeMethodThis;
  }

  public static Gson getGson() {
    return GSON;
  }

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
   * Create a modern trace for a single execution snapshot.
   */
  public Trace createTrace(String javaSource, ExecutionSnapshot snapshot) {
    boolean isMultiFile = isMultiFileSource(javaSource, List.of(snapshot));
    Step step = createStep(snapshot, 1, isMultiFile);
    return new Trace(javaSource, List.of(step));
  }

  /**
   * Create a modern trace for a chronological list of snapshots.
   */
  public Trace createTrace(String javaSource, List<ExecutionSnapshot> snapshots) {
    boolean isMultiFile = isMultiFileSource(javaSource, snapshots);
    List<Step> steps = new ArrayList<>();
    for (int i = 0; i < snapshots.size(); i++) {
      steps.add(createStep(snapshots.get(i), i + 1, isMultiFile));
    }
    return new Trace(javaSource, steps);
  }

  /**
   * Create a modern trace for a breakpoints map.
   */
  public Trace createBreakpointsTrace(
      String javaSource, Map<Integer, ?> breakpointSnapshots) {
    Map<Integer, Object> converted = new LinkedHashMap<>();
    for (Entry<Integer, ?> entry : breakpointSnapshots.entrySet()) {
      if (entry.getValue() instanceof ExecutionSnapshot single) {
        converted.put(entry.getKey(), createStep(single, 1, false));
      } else if (entry.getValue() instanceof List<?> list) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
          if (list.get(i) instanceof ExecutionSnapshot s) {
            steps.add(createStep(s, i + 1, false));
          }
        }
        converted.put(entry.getKey(), steps);
      }
    }
    return new Trace(javaSource, converted);
  }

  /**
   * Convert an individual {@link ExecutionSnapshot} into a {@link Step}.
   */
  public Step createStep(ExecutionSnapshot snapshot, int stepNumber, boolean isMultiFile) {
    String currentMethod =
        snapshot.stack().isEmpty() ? "" : snapshot.stack().getLast().methodName();
    long currentLine = snapshot.stack().isEmpty() ? 0 : snapshot.stack().getLast().methodLine();
    String stepFile = isMultiFile ? snapshot.sourcePath().orElse(null) : null;

    List<Variable> statics = new ArrayList<>();
    for (Field f : snapshot.statics()) {
      statics.add(
          new Variable(
              f.identifier(), f.typeName(), serializeValue(f.value()), f.isFinal()));
    }

    List<StackFrame> callStack = new ArrayList<>();
    int stackSize = snapshot.stack().size();
    for (int i = 0; i < stackSize; i++) {
      boolean isCurrent = (i == stackSize - 1);
      boolean isMain = (i == 0);
      callStack.add(serializeStackFrame(snapshot.stack().get(i), isCurrent, isMain, isMultiFile));
    }

    Map<String, HeapObject> heap = new LinkedHashMap<>();
    for (Entry<Long, TraceValue> e : snapshot.heap().entrySet()) {
      long id = e.getKey();
      TraceValue tv = e.getValue();
      heap.put(String.valueOf(id), serializeHeapObject(id, tv));
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

    return new Step(
        stepNumber,
        currentLine,
        stepFile,
        "step_line",
        currentMethod,
        callStack,
        statics,
        heap,
        stdout,
        stderr);
  }

  private StackFrame serializeStackFrame(
      StackSnapshot frame, boolean isCurrent, boolean isMain, boolean isMultiFile) {
    Reference thisRef = null;
    if (!removeMethodThis && frame.thisObject().isPresent()) {
      ThisObject to = frame.thisObject().get();
      thisRef = new Reference(to.value().uniqueId());
    }

    List<Variable> locals = new ArrayList<>();
    for (Field field : frame.visibleVariables()) {
      if (removeMainArgs && isMain && "args".equals(field.identifier())) {
        continue;
      }
      locals.add(
          new Variable(
              field.identifier(),
              field.typeName(),
              serializeValue(field.value()),
              field.isFinal()));
    }

    String frameFile = isMultiFile ? frame.sourcePath().orElse(null) : null;

    return new StackFrame(
        frame.methodName(),
        frame.methodLine(),
        frameFile,
        isCurrent,
        thisRef,
        locals);
  }

  private Object serializeValue(TraceValue value) {
    return switch (value) {
      case null -> null;
      case TraceValue.Primitive.Float f -> f.value();
      case TraceValue.Primitive.Double d -> {
        if (Double.isInfinite(d.value())) {
          yield d.value() > 0 ? "Infinity" : "-Infinity";
        } else if (Double.isNaN(d.value())) {
          yield "NaN";
        }
        yield d.value();
      }
      case TraceValue.Primitive.Character c -> Character.toString(c.value());
      case TraceValue.Primitive.Byte b -> b.value();
      case TraceValue.Primitive.Short s -> s.value();
      case TraceValue.Primitive.Integer i -> i.value();
      case TraceValue.Primitive.Long l -> l.value();
      case TraceValue.Primitive.Boolean b -> b.value();
      case TraceValue.Reference ref -> new Reference(ref.uniqueId());
      default -> null;
    };
  }

  private HeapObject serializeHeapObject(long id, TraceValue value) {
    return switch (value) {
      case TraceValue.Object obj -> {
        List<Variable> fields = new ArrayList<>();
        for (Field f : obj.fields()) {
          fields.add(
              new Variable(
                  f.identifier(), f.typeName(), serializeValue(f.value()), f.isFinal()));
        }
        yield HeapObject.ofObject(id, obj.classFqn(), fields);
      }
      case TraceValue.String str -> HeapObject.ofString(id, str.value());
      case TraceValue.Lambda lambda ->
          HeapObject.ofLambda(id, "lambda", lambda.implementation());
      case TraceValue.List list -> {
        List<Object> elements = list.value().stream().map(this::serializeValue).toList();
        yield HeapObject.ofArray(id, list.typeName(), elements);
      }
      case TraceValue.Collection col -> {
        List<Object> elements = col.value().stream().map(this::serializeValue).toList();
        yield HeapObject.ofArray(id, col.typeName(), elements);
      }
      case TraceValue.Map map -> {
        List<Variable> entries = new ArrayList<>();
        for (Entry<? extends TraceValue, ? extends TraceValue> entry : map.value().entrySet()) {
          Object k = serializeValue(entry.getKey());
          Object v = serializeValue(entry.getValue());
          entries.add(new Variable(String.valueOf(k), "entry", v, false));
        }
        yield HeapObject.ofObject(id, map.typeName(), entries);
      }
      case TraceValue.Primitive prim ->
          HeapObject.ofBox(id, prim.toWrapperObject().getClass().getName(), serializeValue(prim));
      default -> HeapObject.ofObject(id, "Object", List.of());
    };
  }
}
