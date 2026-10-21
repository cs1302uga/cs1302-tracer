package cs1302.tracer.internal.trace;

import cs1302.tracer.internal.execution.TraceSession;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;

/**
 * Owns reachable-heap identity, pending references, and JDI inspection for one snapshot.
 * Snapshot capture supplies root observations and the shared snapshot type evidence before finishing inspection.
 */
final class HeapInspection {

    private final ThreadReference mainThread;
    private final ReifiedTypeMetadata metadata;
    private final AstTypeResolver astTypeResolver;
    private final LinkedList<ObjectReference> references = new LinkedList<>();
    private final Set<Long> admitted = new HashSet<>();
    private final java.util.Map<Long, TraceValue> heap = new HashMap<>();

    /**
     * Creates empty inspection state borrowing the suspended event thread and snapshot type evidence.
     *
     * @param eventThread Suspended event thread used for permitted guest calls.
     * @param metadata Shared type evidence owned by snapshot capture.
     */
    HeapInspection(ThreadReference eventThread, ReifiedTypeMetadata metadata) {
        this.mainThread = eventThread;
        this.metadata = Objects.requireNonNull(metadata);
        this.astTypeResolver = this.metadata.sourceTypes();
    } // HeapInspection

    /**
     * Admits one reference for inspection, charging a newly encountered identity before retaining it.
     *
     * @param reference Reachable reference, or null to ignore.
     */
    void encounter(ObjectReference reference) {
        if (reference == null || this.admitted.contains(reference.uniqueID())) {
            return;
        } // if
        TraceSession session = TraceSession.current();
        if (session != null) {
            session.encounter(reference.uniqueID());
        } // if
        this.admitted.add(reference.uniqueID());
        this.references.add(reference);
    } // encounter

    /**
     * Installs source-observed lambda text after reference admission and its existing element charge.
     * Repeated occurrences retain their text charge and replacement order even when identity is already admitted.
     *
     * @param reference Lambda identity.
     * @param implementation Captured source implementation.
     */
    void sourceLambda(ObjectReference reference, java.lang.String implementation) {
        this.encounter(reference);
        TraceSession.elements(implementation.length());
        this.heap.put(reference.uniqueID(), new TraceValue.Lambda(implementation));
    } // sourceLambda

    /**
     * Completes FIFO inspection and transfers the existing heap to the snapshot being assembled.
     * The capture caller stops using this owner after successful completion; a failed owner is discarded.
     *
     * @return Completed heap, without another copy or presentation transformation.
     */
    java.util.Map<Long, TraceValue> finish() {
        while (!this.references.isEmpty()) {
            ObjectReference workingObject = this.references.removeFirst();
            if (this.heap.containsKey(workingObject.uniqueID())) {
                continue;
            } // if
            TraceValue convertedObject = this.inspect(workingObject);
            this.heap.put(workingObject.uniqueID(), convertedObject);
        } // while
        this.heap.replaceAll((id, value) -> {
            java.lang.String view = this.metadata.functionalView(id);
            if (view != null && value instanceof TraceValue.Object object) {
                return new TraceValue.Object(view + "λ", object.fields(), object.enumConstant());
            } // if
            return value;
        });
        return heap;
    } // finish

    /**
     * Converts a reachable object, discovering descendants through this snapshot's inspection state.
     *
     * @param value Reachable guest object.
     * @return Owned captured value.
     */
    private TraceValue inspect(ObjectReference value) {
        return switch (value) {
        case ArrayReference ar -> {
            java.lang.String arrType = ar.referenceType().name();
            if (this.metadata.typeOf(ar.uniqueID()) != null) {
                arrType = this.metadata.typeOf(ar.uniqueID());
            } // if
            yield new TraceValue.List(arrType, this.arrayReferenceToList(ar));
        } // case
        case StringReference sr -> this.stringValue(sr);
        default -> this.handleObjectReference(value);
        }; // switch
    } // inspect

    /**
     * Budgets JDK string backing storage before asking JDI for its contents.
     *
     * @param string Guest string.
     * @return Owned string value.
     */
    private TraceValue stringValue(StringReference string) {
        Field backing = string.referenceType().fieldByName("value");
        if (backing != null && string.getValue(backing) instanceof ArrayReference array) {
            TraceSession.elements(array.length());
        } // if
        return new TraceValue.String(string.value());
    } // stringValue

    /**
     * Handles converting an ObjectReference into an appropriate TraceValue.
     *
     * @param or The object reference.
     * @return Converted TraceValue.
     */
    private TraceValue handleObjectReference(ObjectReference or) {

        if (!TraceSession.mayInvoke()) {
            if (this.isColor(or)) {
                return this.handleColor(or);
            } // if
            return this.handleRegularObject(or);
        } // if
        Optional<TraceValue.Primitive> maybeWrappedPrimitive = this.tryWrappedPrimitive(or);
        if (maybeWrappedPrimitive.isPresent()) {
            this.encounter(or);
            return maybeWrappedPrimitive.get();
        } // if

        Optional<TraceValue> maybeContainer = this.handleCollectionOrMap(or);
        if (maybeContainer.isPresent()) {
            return maybeContainer.get();
        } // if

        if (this.isColor(or)) {
            return this.handleColor(or);
        } // if

        if (this.isBuiltInType(or.referenceType().name())) {
            Optional<java.util.List<Field>> timeFields = JavaTimeInspection.fields(or.referenceType());
            if (timeFields.isPresent()) {
                java.lang.String typeName = or.referenceType().name();
                return new TraceValue.Object(typeName, this.extractSnapshotFields(or, typeName, timeFields.get()));
            } // if
            ClassType enumDecl = this.resolveEnumDeclaringType(or);
            Optional<java.lang.String> enumConstant = Optional.ofNullable(this.extractEnumConstantName(or, enumDecl));
            return new TraceValue.Object(or.referenceType().name(), java.util.List.of(), enumConstant);
        } // if

        return this.handleRegularObject(or);
    } // handleObjectReference

    /**
     * Checks if an object reference is a java.awt.Color instance.
     *
     * @param or The object reference.
     * @return True if Color.
     */
    private boolean isColor(ObjectReference or) {
        ReferenceType rt = or.referenceType();
        if ("java.awt.Color".equals(rt.name())) {
            return true;
        } // if
        if (rt instanceof ClassType ct) {
            ClassType sup = ct.superclass();
            while (sup != null) {
                if ("java.awt.Color".equals(sup.name())) {
                    return true;
                } // if
                sup = sup.superclass();
            } // while
        } // if
        return false;
    } // isColor

    /**
     * Handles converting a Color object reference into a TraceValue.Color.
     *
     * @param or The color object reference.
     * @return The converted Color TraceValue.
     */
    private TraceValue handleColor(ObjectReference or) {
        java.lang.String classFqn = or.referenceType().name();
        Field valueField = or.referenceType().fieldByName("value");
        Integer argbVal = null;
        if (valueField != null) {
            Value val = or.getValue(valueField);
            if (val instanceof PrimitiveValue pv) {
                argbVal = pv.intValue();
            } // if
        } // if
        if (argbVal == null && TraceSession.mayInvoke() && or.referenceType() instanceof ClassType ct) {
            try {
                Method getRGB = ct.concreteMethodByName("getRGB", "()I");
                if (getRGB != null) {
                    Value val = or.invokeMethod(this.mainThread, getRGB, java.util.List.of(), 0);
                    if (val instanceof PrimitiveValue pv) {
                        argbVal = pv.intValue();
                    } // if
                } // if
            } catch (Exception ignored) {
                // fallback to default
            } // try
        } // if
        int argb = (argbVal != null) ? argbVal : 0;
        int alpha = (argb >> 24) & 0xFF;
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        java.lang.String hex = (alpha == 255) ? java.lang.String.format("#%02X%02X%02X", red, green, blue)
                : java.lang.String.format("#%02X%02X%02X%02X", red, green, blue, alpha);
        TraceSession.elements(hex.length());
        return new TraceValue.Color(classFqn, hex);
    } // handleColor

    /**
     * Checks if a class name belongs to standard Java runtime packages.
     *
     * @param name The class FQN.
     * @return True if built-in package.
     */
    private boolean isBuiltInType(java.lang.String name) {
        java.lang.String[] builtInPackages = { "com.sun.", "java.", "javax.", "jdk.", "netscape.javascript.",
            "org.ietf.jgss.", "org.w3c.dom.", "org.xml.sax." };
        for (java.lang.String prefix : builtInPackages) {
            if (name.startsWith(prefix)) {
                return true;
            } // if
        } // for
        return false;
    } // isBuiltInType

    /**
     * Propagates reified element types from a parameterized container to element objects.
     *
     * @param ar The array of elements.
     * @param colTypeName The container's reified type name.
     */
    private void propagateContainerElements(ArrayReference ar, java.lang.String colTypeName) {
        if (!colTypeName.contains("<") || this.astTypeResolver == null) {
            return;
        } // if
        java.util.List<java.lang.String> typeArgs = AstTypeResolver.extractTypeArguments(colTypeName);
        if (typeArgs.isEmpty()) {
            return;
        } // if
        java.lang.String elemType = typeArgs.get(0);
        TraceSession.elements(ar.length());
        for (Value elemVal : ar.getValues()) {
            if (elemVal instanceof ObjectReference elemOr) {
                this.metadata.refineDeclared(elemOr, elemType);
            } // if
        } // for
    } // propagateContainerElements

    /**
     * Handles collection inspection and element array conversion.
     *
     * @param ct ClassType of the object.
     * @param or ObjectReference of the collection.
     * @return Converted collection TraceValue.
     */
    private Optional<TraceValue> handleCollection(ClassType ct, ObjectReference or) {
        try {
            VirtualMachine vm = or.virtualMachine();
            Method toArray = ct.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
            ArrayReference ar = invokeCollectionArray(or, toArray);
            if (ar == null) {
                return Optional.empty();
            } // if
            boolean isList = !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.List"));
            java.lang.String colTypeName = or.referenceType().name();
            if (this.metadata.typeOf(or.uniqueID()) != null) {
                colTypeName = this.metadata.typeOf(or.uniqueID());
            } // if
            this.propagateContainerElements(ar, colTypeName);
            java.util.List<TraceValue> traceArray = this.arrayReferenceToList(ar);
            return Optional.of(isList ? new TraceValue.List(colTypeName, traceArray)
                    : new TraceValue.Collection(colTypeName, traceArray));
        } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException e) {
            throw new IllegalStateException("Failed to inspect collection", e);
        } catch (InvocationException ignored) {
            return Optional.empty();
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalArgumentException("Expected thread to be suspended", e);
        } // try
    } // handleCollection

    /**
     * Invokes a collection accessor without reentering source capture or exception observation.
     * JDI reports accessor exceptions through InvocationException; a separate suspending event would
     * deadlock the event consumer waiting for this synchronous invocation. Restore only requests
     * that were enabled before inspection, including when the accessor fails.
     *
     * @param object Collection being inspected.
     * @param method Zero-argument array accessor.
     * @return Returned array, possibly null for an invalid implementation.
     * @throws InvalidTypeException If invocation arguments are invalid.
     * @throws ClassNotLoadedException If an argument type is unavailable.
     * @throws IncompatibleThreadStateException If the event thread cannot invoke.
     * @throws InvocationException If the guest accessor throws.
     */
    private ArrayReference invokeCollectionArray(ObjectReference object, Method method)
            throws InvalidTypeException, ClassNotLoadedException, IncompatibleThreadStateException,
            InvocationException {
        EventRequestManager manager = object.virtualMachine().eventRequestManager();
        java.util.List<EventRequest> candidates = new ArrayList<>(manager.exceptionRequests());
        candidates.addAll(manager.breakpointRequests());
        candidates.addAll(manager.methodExitRequests());
        java.util.List<EventRequest> requests = candidates.stream().filter(EventRequest::isEnabled).toList();
        requests.forEach(EventRequest::disable);
        try {
            return (ArrayReference) object.invokeMethod(this.mainThread, method, java.util.List.of(), 0);
        } finally {
            requests.forEach(EventRequest::enable);
        } // try
    } // invokeCollectionArray

    /**
     * Handles collections and maps conversion.
     *
     * @param or The object reference.
     * @return Optional containing converted container TraceValue.
     */
    private Optional<TraceValue> handleCollectionOrMap(ObjectReference or) {
        if (!(or.referenceType() instanceof ClassType ct)) {
            return Optional.empty();
        } // if
        VirtualMachine vm = or.virtualMachine();

        boolean isCollection = !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.Collection"));
        if (isCollection) {
            return this.handleCollection(ct, or);
        } // if

        Optional<TraceValue.Map> maybeMap = this.inspectMap(or);
        if (maybeMap.isPresent()) {
            TraceValue.Map map = maybeMap.get();
            java.lang.String mapTypeName = or.referenceType().name();
            if (this.metadata.typeOf(or.uniqueID()) != null) {
                mapTypeName = this.metadata.typeOf(or.uniqueID());
            } // if
            return Optional.of(new TraceValue.Map(mapTypeName, map.value()));
        } // if
        return Optional.empty();
    } // handleCollectionOrMap

    /**
     * Resolves the field type name applying generic bindings if available.
     *
     * @param objectField The JDI field.
     * @param concreteType Reconstructed runtime type with available type arguments.
     * @return Resolved field type string.
     */
    private java.lang.String resolveFieldTypeName(Field objectField, java.lang.String concreteType) {
        return this.astTypeResolver == null ? objectField.typeName()
                : this.astTypeResolver.fieldType(concreteType, objectField.declaringType().name(), objectField.name())
                        .orElseGet(objectField::typeName);
    } // resolveFieldTypeName

    /**
     * Resolves the declaring enum type if the given reference is an enum instance.
     *
     * @param or The object reference.
     * @return The declaring enum ClassType, or null if not an enum.
     */
    private ClassType resolveEnumDeclaringType(ObjectReference or) {
        if (or.referenceType() instanceof ClassType ct) {
            if (ct.isEnum()) {
                return ct;
            } // if
            if (ct.superclass() != null && ct.superclass().isEnum()) {
                return ct.superclass();
            } // if
        } // if
        return null;
    } // resolveEnumDeclaringType

    /**
     * Extracts the enum constant name if the given reference is an enum instance.
     *
     * @param or The object reference.
     * @param enumDeclaringType The declaring enum ClassType.
     * @return The constant name, or null if not resolved.
     */
    private java.lang.String extractEnumConstantName(ObjectReference or, ClassType enumDeclaringType) {
        if (enumDeclaringType != null) {
            Field nameField = enumDeclaringType.fieldByName("name");
            if (nameField != null && or.getValue(nameField) instanceof StringReference sr) {
                return sr.value();
            } // if
        } // if
        return null;
    } // extractEnumConstantName

    /**
     * Converts JDI fields of an object reference into execution snapshot fields.
     *
     * @param or The object reference.
     * @param concreteType Reconstructed runtime type with available type arguments.
     * @return Collection of snapshot fields.
     */
    private java.util.Collection<ExecutionSnapshot.Field> extractSnapshotFields(ObjectReference or,
            java.lang.String concreteType) {
        java.util.List<Field> objectJdiFields = or.referenceType().allFields().stream().filter(f -> !f.isStatic())
                .filter(f -> this.includeThreadField(or, f)).toList();
        return this.extractSnapshotFields(or, concreteType, objectJdiFields);
    } // extractSnapshotFields

    /**
     * Captures selected stored fields using the ordinary budget, type-evidence, and reference admission rules.
     *
     * @param or Guest object.
     * @param concreteType Reconstructed runtime type.
     * @param objectJdiFields Validated fields in presentation order.
     * @return Detached captured fields.
     */
    private java.util.Collection<ExecutionSnapshot.Field> extractSnapshotFields(ObjectReference or,
            java.lang.String concreteType, java.util.List<Field> objectJdiFields) {
        java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields = new ArrayList<>();
        TraceSession.elements(objectJdiFields.size());
        for (Field objectField : objectJdiFields) {
            java.lang.String fieldTypeName = this.resolveFieldTypeName(objectField, concreteType);
            Value fieldValue = or.getValue(objectField);
            switch (fieldValue) {
            case null -> objectSnapshotFields.add(
                    new ExecutionSnapshot.Field(objectField.isFinal(), fieldTypeName, objectField.name(),
                            new TraceValue.Null()));
            case PrimitiveValue pf -> objectSnapshotFields.add(new ExecutionSnapshot.Field(objectField.isFinal(),
                    fieldTypeName, objectField.name(), primitive(pf)));
            case ObjectReference of -> {
                if (fieldTypeName != null) {
                    this.metadata.refineDeclared(of, fieldTypeName);
                } // if
                objectSnapshotFields.add(new ExecutionSnapshot.Field(objectField.isFinal(), fieldTypeName,
                        objectField.name(), new TraceValue.Reference(of.uniqueID())));
                this.encounter(of);
            } // case
            default -> {
                // do nothing
            } // default
            } // switch
        } // for
        return objectSnapshotFields;
    } // extractSnapshotFields

    /**
     * Keeps user thread-subclass fields without traversing JVM thread bookkeeping.
     *
     * @param object Object being inspected.
     * @param field Candidate instance field.
     * @return True when the field belongs in the heap view.
     */
    private boolean includeThreadField(ObjectReference object, Field field) {
        TraceSession session = TraceSession.current();
        return session == null || session.threadCapture() == null || !(object instanceof ThreadReference)
                || session.threadCapture().applicationClass(field.declaringType().name());
    } // includeThreadField

    /**
     * Evaluates lazy enum hash code if configured and currently uninitialized.
     *
     * @param or The enum object reference.
     * @param enumDeclaringType The declaring enum ClassType.
     */
    private void evaluateEnumHashIfNeeded(ObjectReference or,
            ClassType enumDeclaringType) {
        if (this.mainThread == null || enumDeclaringType == null || !TraceSession.shouldEvalEnumHash()) {
            return;
        } // if
        Field hashField = null;
        for (Field f : or.referenceType().allFields()) {
            if ("hash".equals(f.name()) && "int".equals(f.typeName())) {
                hashField = f;
                break;
            } // if
        } // for
        if (hashField != null && or.getValue(hashField) instanceof IntegerValue iv && iv.value() == 0) {
            ClassType ct = (ClassType) or.referenceType();
            try {
                Method hashCodeMethod = ct.concreteMethodByName("hashCode", "()I");
                if (hashCodeMethod != null) {
                    or.invokeMethod(this.mainThread, hashCodeMethod, java.util.List.of(), 0);
                } // if
            } catch (Exception ignored) {
                // fallback to uninitialized hash
            } // try
        } // if
    } // evaluateEnumHashIfNeeded

    /**
     * Converts a regular user object reference with fields.
     *
     * @param or The object reference.
     * @return Converted Object TraceValue.
     */
    private TraceValue handleRegularObject(ObjectReference or) {
        ClassType enumDeclaringType = this.resolveEnumDeclaringType(or);
        this.evaluateEnumHashIfNeeded(or, enumDeclaringType);
        java.lang.String rawClassFqn = (enumDeclaringType != null) ? enumDeclaringType.name()
                : or.referenceType().name();
        java.lang.String reifiedClassType = this.metadata.typeOf(or.uniqueID());
        java.lang.String classFqn = (reifiedClassType != null) ? reifiedClassType : rawClassFqn;
        java.lang.String constantName = this.extractEnumConstantName(or, enumDeclaringType);
        java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields = this.extractSnapshotFields(or, classFqn);
        return new TraceValue.Object(classFqn, objectSnapshotFields, Optional.ofNullable(constantName));
    } // handleRegularObject

    /**
     * Convert a mirrored ArrayReference into an owned List.
     *
     * @param arrayReference The array to convert.
     * @return A List with the same contents as the ArrayReference.
     */
    private java.util.List<TraceValue> arrayReferenceToList(ArrayReference arrayReference) {
        TraceSession.elements(arrayReference.length());
        java.util.List<TraceValue> tvs = new ArrayList<>(arrayReference.length());

        for (int i = 0; i < arrayReference.length(); i++) {
            switch (arrayReference.getValue(i)) {
            case null -> tvs.add(new TraceValue.Null());
            case PrimitiveValue pv -> tvs.add(primitive(pv));
            case ObjectReference or -> {
                this.encounter(or);
                tvs.add(new TraceValue.Reference(or.uniqueID()));
            } // case
            case Value v -> throw new IllegalArgumentException("Unrecognized value type: " + v);
            } // switch
        } // for

        return tvs;
    } // arrayReferenceToList

    /**
     * Convert a mirrored JDI primitive into a primitive TraceValue.
     *
     * @param primitiveValue Guest object to inspect.
     * @return The converted primitive TraceValue, reusing {@link TraceValue.Primitive.Boolean#TRUE}
     *         or {@link TraceValue.Primitive.Boolean#FALSE} for booleans.
     */
    static TraceValue.Primitive primitive(PrimitiveValue primitiveValue) {
        return switch (primitiveValue) {
        case BooleanValue bv -> bv.value() ? TraceValue.Primitive.Boolean.TRUE : TraceValue.Primitive.Boolean.FALSE;
        case ByteValue bv -> new TraceValue.Primitive.Byte(bv.value());
        case CharValue cv -> new TraceValue.Primitive.Character(cv.value());
        case DoubleValue dv -> new TraceValue.Primitive.Double(dv.value());
        case FloatValue fv -> new TraceValue.Primitive.Float(fv.value());
        case IntegerValue iv -> new TraceValue.Primitive.Integer(iv.value());
        case LongValue lv -> new TraceValue.Primitive.Long(lv.value());
        case ShortValue sv -> new TraceValue.Primitive.Short(sv.value());
        default -> throw new IllegalArgumentException("Unknown primitive: " + primitiveValue);
        }; // switch
    } // primitive

    /**
     * Tries to inspect a recognized primitive wrapper using its guest accessor.
     *
     * @param value Guest object to inspect.
     * @return The converted primitive TraceValue, or empty if the object is not a recognized wrapper.
     * @throws IllegalStateException If a recognized wrapper's getter is unavailable or its invocation fails.
     * @throws IllegalArgumentException If the thread is not suspended for wrapper invocation.
     */
    private Optional<TraceValue.Primitive> tryWrappedPrimitive(ObjectReference value) {
        if (!(value.type() instanceof ClassType)) {
            return Optional.empty();
        } // if

        ClassType classType = (ClassType) value.type();
        Getter getter = getPrimitiveGetter(classType.signature());
        if (getter == null) {
            return Optional.empty();
        } // if

        Method getterMethod = classType.concreteMethodByName(getter.name(), getter.signature());
        if (getterMethod == null) {
            throw new IllegalStateException(java.lang.String.format("Expected method %s with signature %s.",
                    getter.name(), getter.signature()));
        } // if

        try {
            PrimitiveValue primitiveValue = (PrimitiveValue) value.invokeMethod(this.mainThread, getterMethod,
                    java.util.List.of(), 0);
            return Optional.of(primitive(primitiveValue));
        } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException
                | InvocationException e) {
            throw new IllegalStateException("Failed to invoke getter", e);
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalArgumentException("Expected thread to be suspended", e);
        } // try
    } // tryWrappedPrimitive

    /**
     * A wrapper accessor and its matching JNI method signature.
     *
     * @param name Accessor method name.
     * @param signature JNI method signature.
     */
    private record Getter(java.lang.String name, java.lang.String signature) {} // Getter

    /**
     * Gets the accessor for a primitive wrapper in one lookup.
     *
     * @param signature Wrapper class signature.
     * @return Accessor, or null for other classes.
     */
    private static Getter getPrimitiveGetter(java.lang.String signature) {
        return switch (signature) {
        case "Ljava/lang/Boolean;" -> new Getter("booleanValue", "()Z");
        case "Ljava/lang/Byte;" -> new Getter("byteValue", "()B");
        case "Ljava/lang/Character;" -> new Getter("charValue", "()C");
        case "Ljava/lang/Short;" -> new Getter("shortValue", "()S");
        case "Ljava/lang/Integer;" -> new Getter("intValue", "()I");
        case "Ljava/lang/Long;" -> new Getter("longValue", "()J");
        case "Ljava/lang/Float;" -> new Getter("floatValue", "()F");
        case "Ljava/lang/Double;" -> new Getter("doubleValue", "()D");
        default -> null;
        }; // switch
    } // getPrimitiveGetter

    /**
     * Try to convert a mirrored JDI object into a Map TraceValue.
     *
     * @param or The mirrored JDI object to convert.
     * @return The converted Map TraceValue, or empty if conversion failed.
     */
    private Optional<TraceValue.Map> inspectMap(ObjectReference or) {
        ClassType ct = (ClassType) or.referenceType();
        VirtualMachine vm = or.virtualMachine();

        boolean isMap = !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.Map"));
        if (!isMap) {
            return Optional.empty();
        } // if

        try {
            Method entrySet = ct.concreteMethodByName("entrySet", "()Ljava/util/Set;");
            ObjectReference entries = (ObjectReference) or.invokeMethod(this.mainThread, entrySet, java.util.List.of(),
                    0);
            ClassType entriesCt = (ClassType) entries.referenceType();
            Method entriesToArray = entriesCt.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
            ArrayReference ar = (ArrayReference) entries.invokeMethod(this.mainThread, entriesToArray,
                    java.util.List.of(), 0);
            TraceSession.elements(ar.length() * 2L);
            java.util.Map<TraceValue, TraceValue> map = new HashMap<>();
            for (int i = 0; i < ar.length(); i++) {
                ObjectReference entry = (ObjectReference) ar.getValue(i);
                ClassType entryCt = (ClassType) entry.referenceType();
                Method entryGetKey = entryCt.concreteMethodByName("getKey", "()Ljava/lang/Object;");
                ObjectReference entryKey = (ObjectReference) entry.invokeMethod(this.mainThread, entryGetKey,
                        java.util.List.of(), 0);
                Method entryGetValue = entryCt.concreteMethodByName("getValue", "()Ljava/lang/Object;");
                ObjectReference entryValue = (ObjectReference) entry.invokeMethod(this.mainThread, entryGetValue,
                        java.util.List.of(), 0);
                map.put(this.mapReference(entryKey),
                        this.mapReference(entryValue));
            } // for
            return Optional.of(new TraceValue.Map(or.referenceType().name(), map));
        } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException e) {
            throw new IllegalStateException("Failed to inspect map entries", e);
        } catch (InvocationException ignored) {
            return Optional.empty();
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalArgumentException("Expected thread to be suspended", e);
        } // try
    } // inspectMap

    /**
     * Converts a nullable map entry reference and enqueues only live references.
     *
     * @param value Nullable entry value.
     * @return A null value or non-null reference.
     */
    private TraceValue mapReference(ObjectReference value) {
        if (value == null) {
            return new TraceValue.Null();
        } // if
        this.encounter(value);
        return new TraceValue.Reference(value.uniqueID());
    } // mapReference

} // HeapInspection
