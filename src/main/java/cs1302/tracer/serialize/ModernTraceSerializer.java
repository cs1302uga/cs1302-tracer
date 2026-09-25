package cs1302.tracer.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cs1302.tracer.model.TypeStyle;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Serializes {@link ExecutionSnapshot} objects into the modern clean JSON trace format.
 */
public class ModernTraceSerializer {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new GsonBuilder().create();

    private final boolean removeMainArgs;
    private final boolean inlineStrings;
    private final boolean removeMethodThis;
    private final TypeStyle typeStyle;

    /**
     * Constructs a ModernTraceSerializer with the specified formatting options and TypeStyle.
     *
     * @param removeMainArgs Whether to remove args from the main stack frame.
     * @param inlineStrings Whether strings should be inlined.
     * @param removeMethodThis Whether this references should be omitted from method frames.
     * @param typeStyle The type qualification style (FQN or SIMPLE).
     */
    public ModernTraceSerializer(
            boolean removeMainArgs,
            boolean inlineStrings,
            boolean removeMethodThis,
            TypeStyle typeStyle) {
        this.removeMainArgs = removeMainArgs;
        this.inlineStrings = inlineStrings;
        this.removeMethodThis = removeMethodThis;
        this.typeStyle = typeStyle;
    } // ModernTraceSerializer

    /**
     * Constructs a ModernTraceSerializer defaulting to fully qualified type names.
     *
     * @param removeMainArgs Whether to remove args from the main stack frame.
     * @param inlineStrings Whether strings should be inlined.
     * @param removeMethodThis Whether this references should be omitted from method frames.
     */
    public ModernTraceSerializer(
            boolean removeMainArgs, boolean inlineStrings, boolean removeMethodThis) {
        this(removeMainArgs, inlineStrings, removeMethodThis, TypeStyle.FQN);
    } // ModernTraceSerializer

    /**
     * Gets the shared Gson serializer instance.
     *
     * @return The Gson instance.
     */
    public static Gson getGson() {
        return PRETTY_GSON;
    } // getGson

    /**
     * Gets the shared Gson serializer instance with optional pretty-printing.
     *
     * @param pretty True to enable pretty-printing.
     * @return The Gson instance.
     */
    public static Gson getGson(boolean pretty) {
        return pretty ? PRETTY_GSON : COMPACT_GSON;
    } // getGson

    /**
     * Creates a modern trace for a single execution snapshot.
     *
     * @param javaSource The original Java source code.
     * @param snapshot The single execution snapshot.
     * @return The generated modern Trace object.
     */
    public Trace createTrace(String javaSource, ExecutionSnapshot snapshot) {
        return createTrace(javaSource, "", snapshot);
    } // createTrace

    /**
     * Creates a modern trace for a single snapshot with standard input.
     *
     * @param javaSource The original Java source code.
     * @param stdin The standard input string.
     * @param snapshot The single execution snapshot.
     * @return The generated modern Trace object.
     */
    public Trace createTrace(String javaSource, String stdin, ExecutionSnapshot snapshot) {
        Step step = createStep(snapshot, 1, true);
        return new Trace(javaSource, stdin, List.of(step));
    } // createTrace

    /**
     * Creates a modern trace for a chronological list of snapshots.
     *
     * @param javaSource The original Java source code.
     * @param snapshots The list of execution snapshots.
     * @return The generated modern Trace object.
     */
    public Trace createTrace(String javaSource, List<ExecutionSnapshot> snapshots) {
        return createTrace(javaSource, "", snapshots);
    } // createTrace

    /**
     * Creates a modern trace for a chronological list of snapshots with standard input.
     *
     * @param javaSource The original Java source code.
     * @param stdin The standard input string.
     * @param snapshots The list of execution snapshots.
     * @return The generated modern Trace object.
     */
    public Trace createTrace(String javaSource, String stdin, List<ExecutionSnapshot> snapshots) {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            steps.add(createStep(snapshots.get(i), i + 1, true));
        } // for
        return new Trace(javaSource, stdin, steps);
    } // createTrace

    /**
     * Creates a modern trace for a breakpoints map.
     *
     * @param javaSource The original Java source code.
     * @param breakpointSnapshots The mapping of line numbers to snapshots.
     * @return The generated modern Trace object.
     */
    public Trace createBreakpointsTrace(
            String javaSource, Map<Integer, ?> breakpointSnapshots) {
        return createBreakpointsTrace(javaSource, "", breakpointSnapshots);
    } // createBreakpointsTrace

    /**
     * Creates a modern trace for a breakpoints map with standard input.
     *
     * @param javaSource The original Java source code.
     * @param stdin The standard input string.
     * @param breakpointSnapshots The mapping of line numbers to snapshots.
     * @return The generated modern Trace object.
     */
    public Trace createBreakpointsTrace(
            String javaSource, String stdin, Map<Integer, ?> breakpointSnapshots) {
        Map<Integer, Object> converted = new LinkedHashMap<>();
        for (Entry<Integer, ?> entry : breakpointSnapshots.entrySet()) {
            if (entry.getValue() instanceof ExecutionSnapshot single) {
                converted.put(entry.getKey(), createStep(single, 1, true));
            } else if (entry.getValue() instanceof List<?> list) {
                List<Step> steps = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof ExecutionSnapshot s) {
                        steps.add(createStep(s, i + 1, true));
                    } // if
                } // for
                converted.put(entry.getKey(), steps);
            } // if
        } // for
        return new Trace(javaSource, stdin, converted);
    } // createBreakpointsTrace

    /**
     * Converts an individual {@link ExecutionSnapshot} into a {@link Step}.
     *
     * @param snapshot The execution snapshot.
     * @param stepNumber The 1-based step sequence number.
     * @param isMultiFile True if file path attributes should be serialized.
     * @return The converted Step.
     */
    public Step createStep(ExecutionSnapshot snapshot, int stepNumber, boolean isMultiFile) {
        String currentMethod =
                snapshot.stack().isEmpty() ? "" : snapshot.stack().getLast().methodName();
        long currentLine = snapshot.stack().isEmpty() ? 0 : snapshot.stack().getLast().methodLine();
        String stepFile = isMultiFile ? snapshot.sourcePath().orElse(null) : null;

        List<Variable> statics = new ArrayList<>();
        for (Field f : snapshot.statics()) {
            statics.add(new Variable(
                    f.identifier(),
                    typeStyle.format(f.typeName()),
                    serializeValue(f.value()),
                    f.isFinal()));
        } // for

        List<StackFrame> callStack = new ArrayList<>();
        int stackSize = snapshot.stack().size();
        for (int i = 0; i < stackSize; i++) {
            boolean isCurrent = (i == stackSize - 1);
            boolean isMain = (i == 0);
            callStack.add(serializeStackFrame(
                    snapshot.stack().get(i), isCurrent, isMain, isMultiFile));
        } // for

        Map<String, HeapObject> heap = new LinkedHashMap<>();
        for (Entry<Long, TraceValue> e : snapshot.heap().entrySet()) {
            long id = e.getKey();
            TraceValue tv = e.getValue();
            heap.put(String.valueOf(id), serializeHeapObject(id, tv));
        } // for

        String stdout = decodeOutput(snapshot.stdout());
        String stderr = decodeOutput(snapshot.stderr());

        List<Step.ThreadState> threads = serializeThreads(snapshot, isMultiFile);
        return new Step(
                stepNumber,
                currentLine,
                stepFile,
                snapshot.event() == null ? "step_line" : snapshot.event(),
                currentMethod,
                callStack,
                statics,
                heap,
                stdout,
                stderr,
                snapshot.stdinConsumed(),
                snapshot.stdinOffset(), threads, snapshot.triggeringThreadId());
    } // createStep

    /**
     * Converts optional application thread states using the same frame options as the event stack.
     * @param snapshot Captured state.
     * @param isMultiFile Include source file names.
     * @return Thread models, or null for legacy output.
     */
    private List<Step.ThreadState> serializeThreads(
            ExecutionSnapshot snapshot, boolean isMultiFile) {
        List<Step.ThreadState> threads = null;
        if (snapshot.threads() != null) {
            threads = new ArrayList<>();
            for (var thread : snapshot.threads()) {
                List<StackFrame> frames = new ArrayList<>();
                for (int i = 0; i < thread.stack().size(); i++) {
                    frames.add(serializeStackFrame(thread.stack().get(i),
                            i == thread.stack().size() - 1, i == 0, isMultiFile));
                } // for
                threads.add(new Step.ThreadState(
                        thread.id(), thread.name(), thread.state(), frames));
            } // for
        } // if
        return threads;
    } // serializeThreads

    /**
     * Decodes raw stdout or stderr byte arrays into a UTF-8 string.
     *
     * @param bytes The raw output bytes.
     * @return The decoded string, or empty string on decoding failure.
     */
    private String decodeOutput(byte[] bytes) {
        try {
            return Charset.defaultCharset()
                    .newDecoder()
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException cce) {
            return "";
        } // try
    } // decodeOutput

    /**
     * Serializes a single stack frame snapshot into a modern {@link StackFrame}.
     *
     * @param frame The stack frame snapshot.
     * @param isCurrent True if this is the active topmost frame.
     * @param isMain True if this is the entry-point main frame.
     * @param isMultiFile True if relative file paths should be included.
     * @return The serialized modern StackFrame.
     */
    private StackFrame serializeStackFrame(
            StackSnapshot frame, boolean isCurrent, boolean isMain, boolean isMultiFile) {
        Reference thisRef = null;
        if (!removeMethodThis && frame.thisObject().isPresent()) {
            ThisObject to = frame.thisObject().get();
            thisRef = new Reference(to.value().uniqueId());
        } // if

        List<Variable> locals = new ArrayList<>();
        for (Field field : frame.visibleVariables()) {
            if (removeMainArgs && isMain && "main".equals(frame.methodName())
                    && "args".equals(field.identifier())) {
                continue;
            } // if
            locals.add(new Variable(
                    field.identifier(),
                    typeStyle.format(field.typeName()),
                    serializeValue(field.value()),
                    field.isFinal()));
        } // for

        String frameFile = isMultiFile ? frame.sourcePath().orElse(null) : null;

        return new StackFrame(
                frame.methodName(),
                frame.methodLine(),
                frameFile,
                isCurrent,
                thisRef,
                locals);
    } // serializeStackFrame

    /**
     * Serializes a {@link TraceValue} into its JSON-compatible representation.
     *
     * @param value The trace value to serialize.
     * @return The serialized value (primitive, Reference, or null).
     */
    private Object serializeValue(TraceValue value) {
        return switch (value) {
            case null -> null;
            case TraceValue.Primitive.Float f -> f.value();
            case TraceValue.Primitive.Double d -> {
                if (Double.isInfinite(d.value())) {
                    yield d.value() > 0 ? "Infinity" : "-Infinity";
                } // if
                if (Double.isNaN(d.value())) {
                    yield "NaN";
                } // if
                yield d.value();
            } // case
            case TraceValue.Primitive.Character c -> Character.toString(c.value());
            case TraceValue.Primitive.Byte b -> b.value();
            case TraceValue.Primitive.Short s -> s.value();
            case TraceValue.Primitive.Integer i -> i.value();
            case TraceValue.Primitive.Long l -> l.value();
            case TraceValue.Primitive.Boolean b -> b.value();
            case TraceValue.Reference ref -> new Reference(ref.uniqueId());
            default -> null;
        }; // switch
    } // serializeValue

    /**
     * Serializes a heap object {@link TraceValue} into a {@link HeapObject}.
     *
     * @param id The unique reference ID.
     * @param value The heap object trace value.
     * @return The serialized modern HeapObject.
     */
    private HeapObject serializeHeapObject(long id, TraceValue value) {
        return switch (value) {
            case TraceValue.Object obj -> {
                List<Variable> fields = new ArrayList<>();
                for (Field f : obj.fields()) {
                    fields.add(new Variable(
                            f.identifier(),
                            typeStyle.format(f.typeName()),
                            serializeValue(f.value()),
                            f.isFinal()));
                } // for
                String typeLabel = typeStyle.format(obj.classFqn());
                if (obj.enumConstant().isPresent()) {
                    typeLabel += "." + obj.enumConstant().get();
                } // if
                yield HeapObject.ofObject(id, typeLabel, fields);
            } // case
            case TraceValue.String str ->
                    HeapObject.ofString(id, typeStyle.format("java.lang.String"), str.value());
            case TraceValue.Lambda lambda ->
                    HeapObject.ofLambda(id, "lambda", lambda.implementation());
            case TraceValue.List list -> {
                List<Object> elements = list.value().stream().map(this::serializeValue).toList();
                yield HeapObject.ofArray(id, typeStyle.format(list.typeName()), elements);
            } // case
            case TraceValue.Collection col -> {
                List<Object> elements = col.value().stream().map(this::serializeValue).toList();
                yield HeapObject.ofArray(id, typeStyle.format(col.typeName()), elements);
            } // case
            case TraceValue.Map map -> {
                List<Variable> entries = new ArrayList<>();
                for (Entry<? extends TraceValue, ? extends TraceValue> entry
                        : map.value().entrySet()) {
                    Object k = serializeValue(entry.getKey());
                    Object v = serializeValue(entry.getValue());
                    entries.add(new Variable(String.valueOf(k), "entry", v, false));
                } // for
                yield HeapObject.ofObject(id, typeStyle.format(map.typeName()), entries);
            } // case
            case TraceValue.Primitive prim ->
                    HeapObject.ofBox(
                            id,
                            typeStyle.format(prim.toWrapperObject().getClass().getName()),
                            serializeValue(prim));
            case TraceValue.Color col ->
                    HeapObject.ofColor(id, typeStyle.format(col.classFqn()), col.hex());
            default -> HeapObject.ofObject(id, "Object", List.of());
        }; // switch
    } // serializeHeapObject
} // ModernTraceSerializer
