package cs1302.tracer.serialize;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.model.TypeStyle;
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
 * Container class for methods that help serialize a trace into the OnlinePythonTutor format
 * using Gson.
 *
 * @param removeMainArgs True if the args parameter to main should be excluded.
 * @param inlineStrings True if strings should be inlined as literals.
 * @param removeMethodThis True if this references for method calls should be excluded.
 * @param typeStyle The type qualification style (FQN or SIMPLE).
 */
public record PyTutorSerializer(
        boolean removeMainArgs,
        boolean inlineStrings,
        boolean removeMethodThis,
        TypeStyle typeStyle) {

    /**
     * Constructs a PyTutorSerializer defaulting to fully qualified type names.
     *
     * @param removeMainArgs True if the args parameter to main should be excluded.
     * @param inlineStrings True if strings should be inlined as literals.
     * @param removeMethodThis True if this references for method calls should be excluded.
     */
    public PyTutorSerializer(
            boolean removeMainArgs, boolean inlineStrings, boolean removeMethodThis) {
        this(removeMainArgs, inlineStrings, removeMethodThis, TypeStyle.FQN);
    } // PyTutorSerializer

    private static final Gson GSON =
            new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    /**
     * Get the configured Gson instance.
     *
     * @return The Gson instance.
     */
    public static Gson getGson() {
        return GSON;
    } // getGson

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
        } // if
        Set<String> distinctFiles = new HashSet<>();
        for (ExecutionSnapshot snapshot : snapshots) {
            snapshot.sourcePath().ifPresent(distinctFiles::add);
            for (StackSnapshot frame : snapshot.stack()) {
                frame.sourcePath().ifPresent(distinctFiles::add);
            } // for
        } // for
        return distinctFiles.size() > 1;
    } // isMultiFileSource

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
    } // createTrace

    /**
     * Create a {@link PyTutorTrace} model representing a chronological sequence of snapshots.
     *
     * @param javaSource The source code for the program corresponding to the execution snapshots.
     * @param snapshots The list of snapshots in chronological order that should be serialized.
     * @return The structured PyTutorTrace model.
     */
    public PyTutorTrace createTrace(String javaSource, List<ExecutionSnapshot> snapshots) {
        boolean isMultiFile = isMultiFileSource(javaSource, snapshots);
        List<TraceStep> steps =
                snapshots.stream().map(s -> createTraceStep(s, isMultiFile)).toList();
        return new PyTutorTrace(javaSource, "", steps, "");
    } // createTrace

    /**
     * Create a {@link TraceStep} model representing the given snapshot.
     *
     * @param snapshot The snapshot that should be transformed.
     * @return The structured TraceStep model.
     */
    public TraceStep createTraceStep(ExecutionSnapshot snapshot) {
        return createTraceStep(snapshot, false);
    } // createTraceStep

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

        for (Field f : snapshot.statics()) {
            serializedStatics.put(f.identifier(), serializeTraceValue(f.value(), snapshot.heap()));
            orderedStatics.add(f.identifier());
            globalsAttrs.put(
                    f.identifier(),
                    Map.of("type", typeStyle.format(f.typeName()), "final", f.isFinal()));
        } // for

        Map<Long, String> declaredTypes = collectDeclaredTypes(snapshot);
        Map<String, Object> serializedHeap = serializeHeap(snapshot.heap());
        Map<String, Object> heapAttrs = buildHeapAttrs(snapshot.heap(), declaredTypes);

        List<RenderStackFrame> serializedStack = new ArrayList<>();
        int stackSize = snapshot.stack().size();
        for (int frameId = 0; frameId < stackSize; frameId++) {
            boolean isCurrent = (frameId == stackSize - 1);
            boolean isMain = (frameId == 0);
            serializedStack.add(serializeStackSnapshot(
                    snapshot.stack().get(frameId),
                    frameId,
                    isCurrent,
                    isMain,
                    snapshot.heap(),
                    isMultiFile));
        } // for

        String stdout = decodeOutput(snapshot.stdout());
        String stderr = decodeOutput(snapshot.stderr());
        String stepFile = isMultiFile ? snapshot.sourcePath().orElse(null) : null;

        return new TraceStep(
                stdout,
                stderr,
                "step_line",
                currentMethod,
                currentLine,
                serializedStack,
                serializedStatics,
                globalsAttrs,
                orderedStatics,
                serializedHeap,
                heapAttrs,
                stepFile);
    } // createTraceStep

    /**
     * Collect declared type names from statics, stack frames, and object fields.
     *
     * @param snapshot The execution snapshot.
     * @return Map of object IDs to declared type strings.
     */
    private Map<Long, String> collectDeclaredTypes(ExecutionSnapshot snapshot) {
        Map<Long, String> declaredTypes = new HashMap<>();
        for (Field f : snapshot.statics()) {
            if (f.value() instanceof TraceValue.Reference ref) {
                declaredTypes.put(ref.uniqueId(), f.typeName());
            } // if
        } // for
        for (StackSnapshot frame : snapshot.stack()) {
            for (Field f : frame.visibleVariables()) {
                if (f.value() instanceof TraceValue.Reference ref) {
                    declaredTypes.put(ref.uniqueId(), f.typeName());
                } // if
            } // for
            if (frame.thisObject().isPresent()) {
                ThisObject to = frame.thisObject().get();
                declaredTypes.put(to.value().uniqueId(), to.typeName());
            } // if
        } // for
        for (TraceValue tv : snapshot.heap().values()) {
            if (tv instanceof TraceValue.Object obj) {
                for (Field f : obj.fields()) {
                    if (f.value() instanceof TraceValue.Reference ref) {
                        declaredTypes.put(ref.uniqueId(), f.typeName());
                    } // if
                } // for
            } // if
        } // for
        return declaredTypes;
    } // collectDeclaredTypes

    /**
     * Serializes heap objects into JSON-compatible representations.
     *
     * @param heap The heap mapping.
     * @return Serialized heap map.
     */
    private Map<String, Object> serializeHeap(Map<Long, TraceValue> heap) {
        Map<String, Object> serializedHeap = new LinkedHashMap<>();
        for (Entry<Long, TraceValue> e : heap.entrySet()) {
            if (!(inlineStrings && e.getValue() instanceof TraceValue.String)) {
                serializedHeap.put(
                        e.getKey().toString(), serializeTraceValue(e.getValue(), heap));
            } // if
        } // for
        return serializedHeap;
    } // serializeHeap

    /**
     * Constructs the type attributes map for all heap objects.
     *
     * @param heap The heap mapping.
     * @param declaredTypes Declared type mappings for references.
     * @return Map of heap attributes.
     */
    private Map<String, Object> buildHeapAttrs(
            Map<Long, TraceValue> heap, Map<Long, String> declaredTypes) {
        Map<String, Object> heapAttrs = new LinkedHashMap<>();
        for (Entry<Long, TraceValue> e : heap.entrySet()) {
            String key = e.getKey().toString();
            Long id = e.getKey();
            TraceValue val = e.getValue();
            switch (val) {
            case TraceValue.Object o -> {
                List<String> objectTypes =
                        o.fields().stream()
                                .map(Field::typeName)
                                .map(typeStyle::format)
                                .collect(Collectors.toList());
                heapAttrs.put(key, Map.of("type", objectTypes));
            } // case
            case TraceValue.List a -> {
                String resolvedType = resolveListType(id, a, declaredTypes, heap);
                heapAttrs.put(key, Map.of("type", typeStyle.format(resolvedType)));
            } // case
            case TraceValue.Map m -> {
                String resolvedType = resolveMapType(id, m, declaredTypes, heap);
                heapAttrs.put(key, Map.of("type", typeStyle.format(resolvedType)));
            } // case
            case TraceValue.Collection c -> {
                String resolvedType = resolveCollectionType(id, c, declaredTypes, heap);
                heapAttrs.put(key, Map.of("type", typeStyle.format(resolvedType)));
            } // case
            case TraceValue.String s ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.String")));
            case TraceValue.Primitive.Integer i ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Integer")));
            case TraceValue.Primitive.Double d ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Double")));
            case TraceValue.Primitive.Boolean b ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Boolean")));
            case TraceValue.Primitive.Long l ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Long")));
            case TraceValue.Primitive.Float f ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Float")));
            case TraceValue.Primitive.Character c ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Character")));
            case TraceValue.Primitive.Byte b ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Byte")));
            case TraceValue.Primitive.Short s ->
                heapAttrs.put(key, Map.of("type", typeStyle.format("java.lang.Short")));
            case TraceValue.Lambda l -> heapAttrs.put(key, Map.of("type", "lambda"));
            default -> {
                // do nothing
            } // default
            } // switch
        } // for
        return heapAttrs;
    } // buildHeapAttrs

    /**
     * Decodes raw bytes to string.
     *
     * @param bytes Raw byte array.
     * @return Decoded string.
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
     * Serialize an execution snapshot into the OnlinePythonTutor JSON trace string.
     *
     * @param javaSource The source code for the program corresponding to the execution snapshot.
     * @param snapshot The snapshot that should be serialized.
     * @return The serialized execution snapshot as a JSON string.
     */
    public String serialize(String javaSource, ExecutionSnapshot snapshot) {
        PyTutorTrace trace = createTrace(javaSource, snapshot);
        return GSON.toJson(trace);
    } // serialize

    /**
     * Serialize a stack snapshot into the OnlinePythonTutor stack frame format.
     *
     * @param stackSnapshot The snapshot to serialize.
     * @param uniqueFrameId A unique ID for the frame.
     * @param isCurrentFrame True if this is the executing frame.
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

        stackSnapshot.thisObject().ifPresent(t -> {
            if (!removeMethodThis) {
                orderedVarnames.add("this");
                localsAttrs.put(
                        "this",
                        Map.of("type", typeStyle.format(t.typeName()), "final", true));
                encodedLocals.put("this", serializeTraceValue(t.value(), heap));
            } // if
        });

        for (Field field : stackSnapshot.visibleVariables()) {
            if (removeMainArgs && isMainFrame && "args".equals(field.identifier())) {
                continue;
            } // if
            orderedVarnames.add(field.identifier());
            localsAttrs.put(
                    field.identifier(),
                    Map.of("type", typeStyle.format(field.typeName()), "final", field.isFinal()));
            encodedLocals.put(field.identifier(), serializeTraceValue(field.value(), heap));
        } // for

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
    } // serializeStackSnapshot

    /**
     * Serialize a TraceValue into a standard Java object / List / Map representation.
     *
     * @param value The value to serialize.
     * @param heap A mapping of heap IDs to other TraceValues.
     * @return An object, List, Map, or null corresponding to the PyTutor JSON value.
     */
    private Object serializeTraceValue(TraceValue value, Map<Long, TraceValue> heap) {
        return switch (value) {
            case null -> null;
            case TraceValue.Null nullVal -> null;
            case TraceValue.Primitive prim -> serializePrimitive(prim);
            case TraceValue.Reference referenceValue -> {
                TraceValue target = heap.get(referenceValue.uniqueId());
                if (target instanceof TraceValue.String && inlineStrings) {
                    yield serializeTraceValue(target, heap);
                } // if
                yield List.of("REF", referenceValue.uniqueId());
            } // case
            case TraceValue.String stringValue -> {
                if (inlineStrings) {
                    yield stringValue.value();
                } // if
                yield List.of(
                        "INSTANCE", "String", List.of("___NO_LABEL!___", stringValue.value()));
            } // case
            case TraceValue.Lambda lambdaValue ->
                    List.of("JAVA_LAMBDA", lambdaValue.implementation());
            case TraceValue.List listValue -> {
                List<Object> list = new ArrayList<>();
                list.add("LIST");
                for (TraceValue elem : listValue.value()) {
                    list.add(serializeTraceValue(elem, heap));
                } // for
                yield list;
            } // case
            case TraceValue.Collection collectionValue -> {
                List<Object> list = new ArrayList<>();
                list.add("SET");
                for (TraceValue elem : collectionValue.value()) {
                    list.add(serializeTraceValue(elem, heap));
                } // for
                yield list;
            } // case
            case TraceValue.Map mapValue -> serializeMap(mapValue, heap);
            case TraceValue.Object objectValue -> serializeObject(objectValue, heap);
        }; // switch
    } // serializeTraceValue

    /**
     * Serializes a primitive trace value.
     *
     * @param prim The primitive trace value.
     * @return Serialized representation.
     */
    private Object serializePrimitive(TraceValue.Primitive prim) {
        return switch (prim) {
            case TraceValue.Primitive.Float f ->
                    List.of("NUMBER-LITERAL", Float.toString(f.value()));
            case TraceValue.Primitive.Double d -> serializeDouble(d.value());
            case TraceValue.Primitive.Character c ->
                    List.of("CHAR-LITERAL", Character.toString(c.value()));
            default -> prim.toWrapperObject();
        }; // switch
    } // serializePrimitive

    /**
     * Serializes a map trace value.
     *
     * @param mapValue Map trace value.
     * @param heap Heap map.
     * @return Serialized list representation.
     */
    private Object serializeMap(TraceValue.Map mapValue, Map<Long, TraceValue> heap) {
        List<Object> list = new ArrayList<>();
        list.add("DICT");
        for (Entry<? extends TraceValue, ? extends TraceValue> entry
                : mapValue.value().entrySet()) {
            list.add(Arrays.asList(
                    serializeTraceValue(entry.getKey(), heap),
                    serializeTraceValue(entry.getValue(), heap)));
        } // for
        return list;
    } // serializeMap

    /**
     * Serializes an object trace value.
     *
     * @param objectValue Object trace value.
     * @param heap Heap map.
     * @return Serialized list representation.
     */
    private Object serializeObject(TraceValue.Object objectValue, Map<Long, TraceValue> heap) {
        List<Object> list = new ArrayList<>();
        list.add("INSTANCE");
        list.add(typeStyle.format(objectValue.classFqn()));
        for (Field field : objectValue.fields()) {
            list.add(Arrays.asList(
                    field.identifier(), serializeTraceValue(field.value(), heap)));
        } // for
        return list;
    } // serializeObject

    /**
     * Serializes a double primitive value into a JSON representation.
     *
     * @param val The double value.
     * @return The serialized double representation.
     */
    private Object serializeDouble(double val) {
        if (val == Double.POSITIVE_INFINITY) {
            return List.of("SPECIAL_FLOAT", "Infinity");
        } // if
        if (val == Double.NEGATIVE_INFINITY) {
            return List.of("SPECIAL_FLOAT", "-Infinity");
        } // if
        if (Double.isNaN(val)) {
            return List.of("SPECIAL_FLOAT", "NaN");
        } // if
        return List.of("NUMBER-LITERAL", Double.toString(val));
    } // serializeDouble

    /**
     * Extracts generic type arguments enclosed in angle brackets.
     *
     * @param declaredType The declared type string.
     * @return Optional containing the generic type arguments.
     */
    private static Optional<String> extractGenericArguments(String declaredType) {
        if (declaredType == null) {
            return Optional.empty();
        } // if
        int start = declaredType.indexOf('<');
        int end = declaredType.lastIndexOf('>');
        if (start != -1 && end > start) {
            return Optional.of(declaredType.substring(start, end + 1));
        } // if
        return Optional.empty();
    } // extractGenericArguments

    /**
     * Resolves the descriptive type for a List or array.
     *
     * @param id The reference ID.
     * @param list The list trace value.
     * @param declaredTypes Map of declared types.
     * @param heap The heap map.
     * @return The resolved type name.
     */
    private static String resolveListType(
            Long id,
            TraceValue.List list,
            Map<Long, String> declaredTypes,
            Map<Long, TraceValue> heap) {
        String typeName = list.typeName();
        if (typeName.endsWith("[]")) {
            return typeName;
        } // if
        String declared = declaredTypes.get(id);
        Optional<String> genericArgs = extractGenericArguments(declared);
        if (genericArgs.isPresent()) {
            return typeName + genericArgs.get();
        } // if
        if (!list.value().isEmpty()) {
            String elemType = sampleElementType(list.value(), heap);
            if (elemType != null) {
                return typeName + "<" + elemType + ">";
            } // if
        } // if
        return typeName;
    } // resolveListType

    /**
     * Resolves the descriptive type for a Collection / Set.
     *
     * @param id The reference ID.
     * @param col The collection trace value.
     * @param declaredTypes Map of declared types.
     * @param heap The heap map.
     * @return The resolved type name.
     */
    private static String resolveCollectionType(
            Long id,
            TraceValue.Collection col,
            Map<Long, String> declaredTypes,
            Map<Long, TraceValue> heap) {
        String typeName = col.typeName();
        String declared = declaredTypes.get(id);
        Optional<String> genericArgs = extractGenericArguments(declared);
        if (genericArgs.isPresent()) {
            return typeName + genericArgs.get();
        } // if
        if (!col.value().isEmpty()) {
            String elemType = sampleElementType(col.value(), heap);
            if (elemType != null) {
                return typeName + "<" + elemType + ">";
            } // if
        } // if
        return typeName;
    } // resolveCollectionType

    /**
     * Resolves the descriptive type for a Map.
     *
     * @param id The reference ID.
     * @param map The map trace value.
     * @param declaredTypes Map of declared types.
     * @param heap The heap map.
     * @return The resolved type name.
     */
    private static String resolveMapType(
            Long id,
            TraceValue.Map map,
            Map<Long, String> declaredTypes,
            Map<Long, TraceValue> heap) {
        String typeName = map.typeName();
        String declared = declaredTypes.get(id);
        Optional<String> genericArgs = extractGenericArguments(declared);
        if (genericArgs.isPresent()) {
            return typeName + genericArgs.get();
        } // if
        if (!map.value().isEmpty()) {
            String keyType = sampleElementType(map.value().keySet(), heap);
            String valType = sampleElementType(map.value().values(), heap);
            if (keyType != null && valType != null) {
                return typeName + "<" + keyType + ", " + valType + ">";
            } // if
        } // if
        return typeName;
    } // resolveMapType

    /**
     * Samples and infers a consistent element type across a collection of trace values.
     *
     * @param elements The collection of trace values.
     * @param heap The heap map.
     * @return Inferred type name, or null if ambiguous.
     */
    private static String sampleElementType(
            java.util.Collection<? extends TraceValue> elements, Map<Long, TraceValue> heap) {
        String inferred = null;
        for (TraceValue elem : elements) {
            String type = getSimpleTypeName(elem, heap);
            if (type == null) {
                return null;
            } // if
            if (inferred == null) {
                inferred = type;
            } else {
                if (!inferred.equals(type)) {
                    return null;
                } // if
            } // if
        } // for
        return inferred;
    } // sampleElementType

    /**
     * Gets a simple, unqualified type name for a TraceValue.
     *
     * @param value The trace value.
     * @param heap The heap map.
     * @return Simple type name.
     */
    private static String getSimpleTypeName(TraceValue value, Map<Long, TraceValue> heap) {
        if (value instanceof TraceValue.Reference ref) {
            TraceValue target = heap.get(ref.uniqueId());
            if (target != null) {
                return getSimpleTypeName(target, heap);
            } // if
        } // if
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
            } // case
            case TraceValue.List l -> l.typeName();
            case TraceValue.Map m -> m.typeName();
            case TraceValue.Collection c -> c.typeName();
            default -> null;
        }; // switch
    } // getSimpleTypeName
} // PyTutorSerializer
