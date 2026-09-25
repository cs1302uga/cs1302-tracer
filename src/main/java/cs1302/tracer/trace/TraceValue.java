package cs1302.tracer.trace;

import cs1302.tracer.execution.TraceSession;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;

/**
 * A Java value (primitive, null, reference, or object).
 *
 * <p>Normative References:
 * <ul>
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;4.1
 *       (The Kinds of Types and Values).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;4.2
 *       (Primitive Types and Values).
 *   <li>The Java Language Specification (Java SE 21 Edition), &sect;4.3
 *       (Reference Types and Values).
 *   <li>The Java Debug Interface (JDI) Specification (Java SE 21 Edition,
 *       {@code com.sun.jdi.Value}).
 * </ul>
 */
public sealed interface TraceValue {

    /**
     * Convert a JDI value mirror into a TraceValue that is owned by our JVM.
     *
     * @param mainThread The thread associated with the value you want to convert.
     * @param value The value you want to convert.
     * @param outEncounteredReferences An out parameter which accumulates encountered references.
     * @return A TraceValue that contains the same information as the given value.
     */
    static TraceValue fromJdiValue(
            ThreadReference mainThread,
            Value value,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences) {
        return fromJdiValue(
                mainThread, value, outEncounteredReferences, null, Collections.emptyMap());
    } // fromJdiValue

    /**
     * Convert a JDI value mirror into a TraceValue, utilizing AstTypeResolver and objectTypeMap.
     *
     * @param mainThread The thread associated with the value you want to convert.
     * @param value The value you want to convert.
     * @param outEncounteredReferences Accumulates encountered references.
     * @param astTypeResolver Optional AstTypeResolver for declared type lookup.
     * @param objectTypeMap Mapping of object reference IDs to their reified type names.
     * @return A TraceValue containing the converted value with reified type metadata.
     */
    static TraceValue fromJdiValue(
            ThreadReference mainThread,
            Value value,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        if (value == null) {
            return new Null();
        } // if

        return switch (value) {
            case PrimitiveValue pv -> Primitive.fromJdiPrimitive(pv);
            case ArrayReference ar -> {
                java.lang.String arrType = ar.referenceType().name();
                if (objectTypeMap != null && objectTypeMap.containsKey(ar.uniqueID())) {
                    arrType = objectTypeMap.get(ar.uniqueID());
                } // if
                yield new List(
                        arrType,
                        arrayReferenceToList(ar, outEncounteredReferences));
            } // case
            case StringReference sr -> stringValue(sr);
            case ObjectReference or -> handleObjectReference(
                    mainThread,
                    or,
                    outEncounteredReferences,
                    astTypeResolver,
                    objectTypeMap);
            default ->
                throw new IllegalArgumentException("Unrecognized value type: " + value);
        }; // switch
    } // fromJdiValue

    /**
     * Budgets JDK string backing storage before asking JDI for its contents.
     * @param string Guest string.
     * @return Owned string value.
     */
    private static TraceValue stringValue(StringReference string) {
        Field backing = string.referenceType().fieldByName("value");
        if (backing != null && string.getValue(backing) instanceof ArrayReference array) {
            TraceSession.elements(array.length());
        } // if
        return new String(string.value());
    } // stringValue

    /**
     * Handles converting an ObjectReference into an appropriate TraceValue.
     *
     * @param mainThread The thread reference.
     * @param or The object reference.
     * @param outEncounteredReferences Accumulates encountered references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @return Converted TraceValue.
     */
    private static TraceValue handleObjectReference(
            ThreadReference mainThread,
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {

        if (!TraceSession.mayInvoke()) {
            if (isColor(or)) {
                return handleColor(mainThread, or);
            } // if
            return handleRegularObject(
                    mainThread, or, outEncounteredReferences, astTypeResolver, objectTypeMap);
        } // if
        Optional<Primitive> maybeWrappedPrimitive = Primitive.tryFromJdiValue(mainThread, or);
        if (maybeWrappedPrimitive.isPresent()) {
            outEncounteredReferences.ifPresent(l -> l.add(or));
            return maybeWrappedPrimitive.get();
        } // if

        Optional<TraceValue> maybeContainer = handleCollectionOrMap(
                mainThread, or, outEncounteredReferences, astTypeResolver, objectTypeMap);
        if (maybeContainer.isPresent()) {
            return maybeContainer.get();
        } // if

        if (isColor(or)) {
            return handleColor(mainThread, or);
        } // if

        if (isBuiltInType(or.referenceType().name())) {
            ClassType enumDecl = resolveEnumDeclaringType(or);
            Optional<java.lang.String> enumConstant =
                    Optional.ofNullable(extractEnumConstantName(or, enumDecl));
            return new Object(or.referenceType().name(), java.util.List.of(), enumConstant);
        } // if

        return handleRegularObject(
                mainThread, or, outEncounteredReferences, astTypeResolver, objectTypeMap);
    } // handleObjectReference

    /**
     * Checks if an object reference is a java.awt.Color instance.
     *
     * @param or The object reference.
     * @return True if Color.
     */
    private static boolean isColor(ObjectReference or) {
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
     * @param mainThread The thread reference.
     * @param or The color object reference.
     * @return The converted Color TraceValue.
     */
    private static TraceValue handleColor(ThreadReference mainThread, ObjectReference or) {
        java.lang.String classFqn = or.referenceType().name();
        Field valueField = or.referenceType().fieldByName("value");
        Integer argbVal = null;
        if (valueField != null) {
            Value val = or.getValue(valueField);
            if (val instanceof PrimitiveValue pv) {
                argbVal = pv.intValue();
            } // if
        } // if
        if (argbVal == null && TraceSession.mayInvoke()
                && or.referenceType() instanceof ClassType ct) {
            try {
                Method getRGB = ct.concreteMethodByName("getRGB", "()I");
                if (getRGB != null) {
                    Value val = or.invokeMethod(mainThread, getRGB, java.util.List.of(), 0);
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
        java.lang.String hex = (alpha == 255)
                ? java.lang.String.format("#%02X%02X%02X", red, green, blue)
                : java.lang.String.format("#%02X%02X%02X%02X", red, green, blue, alpha);
        TraceSession.elements(hex.length());
        return new Color(classFqn, hex);
    } // handleColor

    /**
     * Checks if a class name belongs to standard Java runtime packages.
     *
     * @param name The class FQN.
     * @return True if built-in package.
     */
    private static boolean isBuiltInType(java.lang.String name) {
        java.lang.String[] builtInPackages = {
            "com.sun.",
            "java.",
            "javax.",
            "jdk.",
            "netscape.javascript.",
            "org.ietf.jgss.",
            "org.w3c.dom.",
            "org.xml.sax."
        };
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
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     */
    private static void propagateContainerElements(
            ArrayReference ar,
            java.lang.String colTypeName,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        if (objectTypeMap == null
                || !colTypeName.contains("<")
                || ar == null
                || astTypeResolver == null) {
            return;
        } // if
        java.util.List<java.lang.String> typeArgs =
                AstTypeResolver.extractTypeArguments(colTypeName);
        if (typeArgs.isEmpty()) {
            return;
        } // if
        java.lang.String elemType = typeArgs.get(0);
        TraceSession.elements(ar.length());
        for (Value elemVal : ar.getValues()) {
            if (elemVal instanceof ObjectReference elemOr) {
                java.lang.String elemRuntime = elemOr.referenceType().name();
                java.lang.String candidate =
                        astTypeResolver.reconcileRuntimeType(elemRuntime, elemType);
                if (!objectTypeMap.containsKey(elemOr.uniqueID())
                        || DebugTraceHelper.isMoreSpecific(
                                candidate,
                                objectTypeMap.get(elemOr.uniqueID()),
                                elemRuntime)) {
                    objectTypeMap.put(elemOr.uniqueID(), candidate);
                } // if
            } // if
        } // for
    } // propagateContainerElements

    /**
     * Handles collection inspection and element array conversion.
     *
     * @param mainThread The thread reference.
     * @param ct ClassType of the object.
     * @param or ObjectReference of the collection.
     * @param outEncounteredReferences Accumulates references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @return Converted collection TraceValue.
     */
    private static Optional<TraceValue> handleCollection(
            ThreadReference mainThread,
            ClassType ct,
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        try {
            VirtualMachine vm = or.virtualMachine();
            Method toArray = ct.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
            ArrayReference ar =
                    (ArrayReference) or.invokeMethod(
                            mainThread, toArray, java.util.List.of(), 0);
            boolean isList =
                    !Collections.disjoint(
                            ct.allInterfaces(), vm.classesByName("java.util.List"));
            java.lang.String colTypeName = or.referenceType().name();
            if (objectTypeMap != null && objectTypeMap.containsKey(or.uniqueID())) {
                colTypeName = objectTypeMap.get(or.uniqueID());
            } // if
            propagateContainerElements(ar, colTypeName, astTypeResolver, objectTypeMap);
            java.util.List<TraceValue> traceArray = arrayReferenceToList(
                    ar, outEncounteredReferences);
            return Optional.of(isList
                    ? new List(colTypeName, traceArray)
                    : new Collection(colTypeName, traceArray));
        } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException e) {
            throw new IllegalStateException("Failed to inspect collection", e);
        } catch (InvocationException ignored) {
            return Optional.empty();
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalArgumentException("Expected thread to be suspended", e);
        } // try
    } // handleCollection

    /**
     * Handles collections and maps conversion.
     *
     * @param mainThread The thread reference.
     * @param or The object reference.
     * @param outEncounteredReferences Accumulates references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @return Optional containing converted container TraceValue.
     */
    private static Optional<TraceValue> handleCollectionOrMap(
            ThreadReference mainThread,
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        if (!(or.referenceType() instanceof ClassType ct)) {
            return Optional.empty();
        } // if
        VirtualMachine vm = or.virtualMachine();

        boolean isCollection =
                !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.Collection"));
        if (isCollection) {
            return handleCollection(
                    mainThread, ct, or, outEncounteredReferences, astTypeResolver, objectTypeMap);
        } // if

        Optional<Map> maybeMap =
                Map.tryFromJdiObjectReference(mainThread, or, outEncounteredReferences);
        if (maybeMap.isPresent()) {
            Map map = maybeMap.get();
            java.lang.String mapTypeName = or.referenceType().name();
            if (objectTypeMap != null && objectTypeMap.containsKey(or.uniqueID())) {
                mapTypeName = objectTypeMap.get(or.uniqueID());
            } // if
            return Optional.of(new Map(mapTypeName, map.value()));
        } // if
        return Optional.empty();
    } // handleCollectionOrMap

    /**
     * Resolves the field type name applying generic bindings if available.
     *
     * @param objectField The JDI field.
     * @param classInfo Class generic metadata.
     * @param bindings Type parameter bindings.
     * @return Resolved field type string.
     */
    private static java.lang.String resolveFieldTypeName(
            Field objectField,
            Optional<AstTypeResolver.ClassGenericInfo> classInfo,
            java.util.Map<java.lang.String, java.lang.String> bindings) {
        java.lang.String fieldTypeName = objectField.typeName();
        if (classInfo.isPresent()) {
            java.lang.String declared = classInfo.get().fieldTypes().get(objectField.name());
            if (declared != null) {
                if (!bindings.isEmpty()) {
                    fieldTypeName = AstTypeResolver.substituteType(declared, bindings);
                } else {
                    if (!declared.contains("<")
                            && !classInfo.get().typeParameters().contains(declared)) {
                        fieldTypeName = declared;
                    } // if
                } // if
            } // if
        } // if
        return fieldTypeName;
    } // resolveFieldTypeName

    /**
     * Resolves the declaring enum type if the given reference is an enum instance.
     *
     * @param or The object reference.
     * @return The declaring enum ClassType, or null if not an enum.
     */
    private static ClassType resolveEnumDeclaringType(ObjectReference or) {
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
    private static java.lang.String extractEnumConstantName(
            ObjectReference or, ClassType enumDeclaringType) {
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
     * @param outEncounteredReferences Accumulates references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @param classInfo Generic class info.
     * @param bindings Type bindings.
     * @return Collection of snapshot fields.
     */
    private static java.util.Collection<ExecutionSnapshot.Field> extractSnapshotFields(
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap,
            Optional<AstTypeResolver.ClassGenericInfo> classInfo,
            java.util.Map<java.lang.String, java.lang.String> bindings) {
        java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields = new ArrayList<>();
        java.util.List<Field> objectJdiFields =
                or.referenceType().allFields().stream().filter(f -> !f.isStatic())
                        .filter(f -> includeThreadField(or, f)).toList();
        TraceSession.elements(objectJdiFields.size());
        for (Field objectField : objectJdiFields) {
            java.lang.String fieldTypeName =
                    resolveFieldTypeName(objectField, classInfo, bindings);
            Value fieldValue = or.getValue(objectField);
            switch (fieldValue) {
            case null -> objectSnapshotFields.add(new ExecutionSnapshot.Field(
                    objectField.isFinal(), fieldTypeName, objectField.name(), new Null()));
            case PrimitiveValue pf -> objectSnapshotFields.add(new ExecutionSnapshot.Field(
                    objectField.isFinal(),
                    fieldTypeName,
                    objectField.name(),
                    Primitive.fromJdiPrimitive(pf)));
            case ObjectReference of -> {
                if (objectTypeMap != null && fieldTypeName != null) {
                    java.lang.String ofRuntimeFqn = of.referenceType().name();
                    java.lang.String candidate = (astTypeResolver != null)
                            ? astTypeResolver.reconcileRuntimeType(ofRuntimeFqn, fieldTypeName)
                            : fieldTypeName;
                    if (!objectTypeMap.containsKey(of.uniqueID())
                            || DebugTraceHelper.isMoreSpecific(
                                    candidate,
                                    objectTypeMap.get(of.uniqueID()),
                                    ofRuntimeFqn)) {
                        objectTypeMap.put(of.uniqueID(), candidate);
                    } // if
                } // if
                objectSnapshotFields.add(new ExecutionSnapshot.Field(
                        objectField.isFinal(),
                        fieldTypeName,
                        objectField.name(),
                        new Reference(of.uniqueID())));
                outEncounteredReferences.ifPresent(l -> l.add(of));
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
     * @param object Object being inspected.
     * @param field Candidate instance field.
     * @return True when the field belongs in the heap view.
     */
    private static boolean includeThreadField(ObjectReference object, Field field) {
        TraceSession session = TraceSession.current();
        return session == null || session.threadCapture() == null
                || !(object instanceof ThreadReference)
                || session.threadCapture().applicationClass(field.declaringType().name());
    } // includeThreadField

    /**
     * Evaluates lazy enum hash code if configured and currently uninitialized.
     *
     * @param mainThread The thread reference.
     * @param or The enum object reference.
     * @param enumDeclaringType The declaring enum ClassType.
     */
    private static void evaluateEnumHashIfNeeded(
            ThreadReference mainThread, ObjectReference or, ClassType enumDeclaringType) {
        if (mainThread == null || enumDeclaringType == null || !TraceSession.shouldEvalEnumHash()) {
            return;
        } // if
        Field hashField = null;
        for (Field f : or.referenceType().allFields()) {
            if ("hash".equals(f.name()) && "int".equals(f.typeName())) {
                hashField = f;
                break;
            } // if
        } // for
        if (hashField != null && or.getValue(hashField) instanceof IntegerValue iv
                && iv.value() == 0 && or.referenceType() instanceof ClassType ct) {
            try {
                Method hashCodeMethod = ct.concreteMethodByName("hashCode", "()I");
                if (hashCodeMethod != null) {
                    or.invokeMethod(mainThread, hashCodeMethod, java.util.List.of(), 0);
                } // if
            } catch (Exception ignored) {
                // fallback to uninitialized hash
            } // try
        } // if
    } // evaluateEnumHashIfNeeded

    /**
     * Converts a regular user object reference with fields.
     *
     * @param mainThread The thread reference.
     * @param or The object reference.
     * @param outEncounteredReferences Accumulates references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @return Converted Object TraceValue.
     */
    private static TraceValue handleRegularObject(
            ThreadReference mainThread,
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        ClassType enumDeclaringType = resolveEnumDeclaringType(or);
        evaluateEnumHashIfNeeded(mainThread, or, enumDeclaringType);
        java.lang.String rawClassFqn = (enumDeclaringType != null)
                ? enumDeclaringType.name()
                : or.referenceType().name();
        java.lang.String reifiedClassType =
                (objectTypeMap != null) ? objectTypeMap.get(or.uniqueID()) : null;
        java.lang.String classFqn = (reifiedClassType != null) ? reifiedClassType : rawClassFqn;
        java.lang.String constantName = extractEnumConstantName(or, enumDeclaringType);
        java.util.Map<java.lang.String, java.lang.String> bindings =
                (reifiedClassType != null && astTypeResolver != null)
                        ? astTypeResolver.getTypeBindings(rawClassFqn, reifiedClassType)
                        : Collections.emptyMap();
        Optional<AstTypeResolver.ClassGenericInfo> classInfo =
                astTypeResolver != null
                        ? astTypeResolver.getClassGenericInfo(rawClassFqn)
                        : Optional.empty();
        java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields = extractSnapshotFields(
                or, outEncounteredReferences, astTypeResolver, objectTypeMap, classInfo, bindings);
        return new Object(classFqn, objectSnapshotFields, Optional.ofNullable(constantName));
    } // handleRegularObject

    /**
     * Convert a mirrored ArrayReference into an owned List.
     * @param arrayReference The array to convert.
     * @param outEncounteredReferences An out parameter for references encountered in the array.
     * @return A List with the same contents as the ArrayReference.
     */
    private static java.util.List<TraceValue> arrayReferenceToList(
            ArrayReference arrayReference,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences) {
        TraceSession.elements(arrayReference.length());
        java.util.List<TraceValue> tvs = new ArrayList<>(arrayReference.length());

        for (int i = 0; i < arrayReference.length(); i++) {
            switch (arrayReference.getValue(i)) {
            case null -> tvs.add(new Null());
            case PrimitiveValue pv -> tvs.add(Primitive.fromJdiPrimitive(pv));
            case ObjectReference or -> {
                outEncounteredReferences.ifPresent(l -> l.add(or));
                tvs.add(new Reference(or.uniqueID()));
            } // case
            case Value v -> throw new IllegalArgumentException("Unrecognized value type: " + v);
            } // switch
        } // for

        return tvs;
    } // arrayReferenceToList

    /** A primitive Java value. */
    sealed interface Primitive extends TraceValue {

        /**
         * Convert a mirrored JDI primitive into a primitive TraceValue.
         *
         * @param primitiveValue The mirrored JDI value to convert.
         * @return The converted primitive TraceValue.
         */
        static Primitive fromJdiPrimitive(PrimitiveValue primitiveValue) {
            return switch (primitiveValue) {
                case BooleanValue bv -> new Boolean(bv.value());
                case ByteValue bv -> new Byte(bv.value());
                case CharValue cv -> new Character(cv.value());
                case DoubleValue dv -> new Double(dv.value());
                case FloatValue fv -> new Float(fv.value());
                case IntegerValue iv -> new Integer(iv.value());
                case LongValue lv -> new Long(lv.value());
                case ShortValue sv -> new Short(sv.value());
                default -> throw new IllegalArgumentException(
                        "Unknown primitive: " + primitiveValue);
            }; // switch
        } // fromJdiPrimitive

        /**
         * Try to convert a mirrored JDI primitive or wrapper into a primitive TraceValue.
         *
         * @param mainThread The thread associated with the value you want to convert.
         * @param value The mirrored JDI value to convert.
         * @return The converted primitive TraceValue, or empty if conversion failed.
         */
        static Optional<Primitive> tryFromJdiValue(ThreadReference mainThread, Value value) {
            if (value instanceof PrimitiveValue pv) {
                return Optional.of(Primitive.fromJdiPrimitive(pv));
            } // if

            if (!(value instanceof ObjectReference objectReference)
                    || !(value.type() instanceof ClassType)) {
                return Optional.empty();
            } // if

            ClassType classType = (ClassType) objectReference.type();
            Getter getter = getPrimitiveGetter(classType.signature());
            if (getter == null) {
                return Optional.empty();
            } // if

            Method getterMethod =
                    classType.concreteMethodByName(getter.name(), getter.signature());
            if (getterMethod == null) {
                throw new IllegalStateException(java.lang.String.format(
                        "Expected method %s with signature %s.",
                        getter.name(), getter.signature()));
            } // if

            try {
                PrimitiveValue primitiveValue = (PrimitiveValue) objectReference.invokeMethod(
                        mainThread, getterMethod, java.util.List.of(), 0);
                return Optional.of(Primitive.fromJdiPrimitive(primitiveValue));
            } catch (IllegalArgumentException
                    | ClassNotLoadedException
                    | InvalidTypeException
                    | InvocationException e) {
                throw new IllegalStateException("Failed to invoke getter", e);
            } catch (IncompatibleThreadStateException e) {
                throw new IllegalArgumentException("Expected thread to be suspended", e);
            } // try
        } // tryFromJdiValue

        /**
         * A wrapper accessor and its matching JNI method signature.
         * @param name Accessor method name.
         * @param signature JNI method signature.
         */
        record Getter(java.lang.String name, java.lang.String signature) {} // Getter

        /**
         * Gets the accessor for a primitive wrapper in one lookup.
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
         * Convert this PrimitiveValue into a primitive wrapper object.
         *
         * @return A primitive wrapper object.
         */
        java.lang.Object toWrapperObject();

        /**
         * A {@code boolean} primitive.
         *
         * @param value Primitive value.
         */
        record Boolean(boolean value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Boolean

        /**
         * A {@code byte} primitive.
         *
         * @param value Primitive value.
         */
        record Byte(byte value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Byte

        /**
         * A {@code char} primitive.
         *
         * @param value Primitive value.
         */
        record Character(char value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Character

        /**
         * A {@code short} primitive.
         *
         * @param value Primitive value.
         */
        record Short(short value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Short

        /**
         * An int primitive.
         *
         * @param value Primitive value.
         */
        record Integer(int value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Integer

        /**
         * A {@code long} primitive.
         *
         * @param value Primitive value.
         */
        record Long(long value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Long

        /**
         * A {@code float} primitive.
         *
         * @param value Primitive value.
         */
        record Float(float value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Float

        /**
         * A {@code double} primitive.
         *
         * @param value Primitive value.
         */
        record Double(double value) implements Primitive {
            @Override
            public java.lang.Object toWrapperObject() {
                return value;
            } // toWrapperObject
        } // Double
    } // Primitive

    /**
     * A non-null reference.
     *
     * @param uniqueId The target object ID.
     */
    record Reference(long uniqueId) implements TraceValue {} // Reference

    /**
     * The null value.
     */
    record Null() implements TraceValue {} // Null

    /**
     * An object.
     *
     * @param classFqn Declaring class FQN.
     * @param fields Object fields.
     * @param enumConstant Optional enum constant name if this object is an enum constant.
     */
    record Object(
            java.lang.String classFqn,
            java.util.Collection<ExecutionSnapshot.Field> fields,
            Optional<java.lang.String> enumConstant)
            implements TraceValue {

        /**
         * Constructs an Object without an enum constant name.
         *
         * @param classFqn Declaring class FQN.
         * @param fields Object fields.
         */
        public Object(
                java.lang.String classFqn,
                java.util.Collection<ExecutionSnapshot.Field> fields) {
            this(classFqn, fields, Optional.empty());
        } // Object

    } // Object

    /**
     * A string.
     *
     * @param value String content.
     */
    record String(java.lang.String value) implements TraceValue {} // String

    /**
     * An object that implements {@link java.util.Map}.
     *
     * @param typeName Map type name.
     * @param value Key-value map entries.
     */
    record Map(
            java.lang.String typeName,
            java.util.Map<? extends TraceValue, ? extends TraceValue> value)
            implements TraceValue {

        /**
         * Convenience constructor with default typeName.
         *
         * @param value The map contents.
         */
        public Map(java.util.Map<? extends TraceValue, ? extends TraceValue> value) {
            this("java.util.Map", value);
        } // Map

        /**
         * Try to convert a mirrored JDI object into a Map TraceValue.
         *
         * @param mainThread The thread associated with the value you want to convert.
         * @param or The mirrored JDI object to convert.
         * @param outEncounteredReferences An out parameter for references.
         * @return The converted Map TraceValue, or empty if conversion failed.
         */
        public static Optional<Map> tryFromJdiObjectReference(
                ThreadReference mainThread,
                ObjectReference or,
                Optional<java.util.List<ObjectReference>> outEncounteredReferences) {
            ClassType ct = (ClassType) or.referenceType();
            VirtualMachine vm = or.virtualMachine();

            boolean isMap =
                    !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.Map"));
            if (!isMap) {
                return Optional.empty();
            } // if

            try {
                Method entrySet = ct.concreteMethodByName("entrySet", "()Ljava/util/Set;");
                ObjectReference entries =
                        (ObjectReference) or.invokeMethod(
                                mainThread, entrySet, java.util.List.of(), 0);
                ClassType entriesCt = (ClassType) entries.referenceType();
                Method entriesToArray =
                        entriesCt.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
                ArrayReference ar =
                        (ArrayReference) entries.invokeMethod(
                                mainThread, entriesToArray, java.util.List.of(), 0);
                TraceSession.elements(ar.length() * 2L);
                java.util.Map<TraceValue, TraceValue> map = new HashMap<>();
                for (int i = 0; i < ar.length(); i++) {
                    ObjectReference entry = (ObjectReference) ar.getValue(i);
                    ClassType entryCt = (ClassType) entry.referenceType();
                    Method entryGetKey =
                            entryCt.concreteMethodByName("getKey", "()Ljava/lang/Object;");
                    ObjectReference entryKey = (ObjectReference) entry.invokeMethod(
                            mainThread, entryGetKey, java.util.List.of(), 0);
                    Method entryGetValue =
                            entryCt.concreteMethodByName("getValue", "()Ljava/lang/Object;");
                    ObjectReference entryValue = (ObjectReference) entry.invokeMethod(
                            mainThread, entryGetValue, java.util.List.of(), 0);
                    map.put(mapReference(entryKey, outEncounteredReferences),
                            mapReference(entryValue, outEncounteredReferences));
                } // for
                return Optional.of(new Map(or.referenceType().name(), map));
            } catch (IllegalArgumentException
                    | ClassNotLoadedException
                    | InvalidTypeException e) {
                throw new IllegalStateException("Failed to inspect map entries", e);
            } catch (InvocationException ignored) {
                return Optional.empty();
            } catch (IncompatibleThreadStateException e) {
                throw new IllegalArgumentException("Expected thread to be suspended", e);
            } // try
        } // tryFromJdiObjectReference

        /**
         * Converts a nullable map entry reference and enqueues only live references.
         * @param value Nullable entry value.
         * @param references Encountered references.
         * @return Null or pointer value.
         */
        private static TraceValue mapReference(ObjectReference value,
                Optional<java.util.List<ObjectReference>> references) {
            if (value == null) {
                return new Null();
            } // if
            references.ifPresent(list -> list.add(value));
            return new Reference(value.uniqueID());
        } // mapReference

    } // Map

    /**
     * An object that implements {@link java.util.Collection}.
     *
     * @param typeName Collection type name.
     * @param value Collection elements.
     */
    record Collection(
            java.lang.String typeName,
            java.util.Collection<? extends TraceValue> value)
            implements TraceValue {

        /**
         * Convenience constructor with default typeName.
         *
         * @param value The collection contents.
         */
        public Collection(java.util.Collection<? extends TraceValue> value) {
            this("java.util.Collection", value);
        } // Collection
    } // Collection

    /**
     * An object that implements {@link java.util.List}, or an array.
     *
     * @param typeName List type name.
     * @param value List elements.
     */
    record List(
            java.lang.String typeName,
            java.util.List<? extends TraceValue> value)
            implements TraceValue {} // List

    /**
     * A lambda with reconstructed implementation.
     *
     * @param implementation Implementation expression or string.
     */
    record Lambda(java.lang.String implementation) implements TraceValue {} // Lambda

    /**
     * Represents a Color object trace value.
     *
     * @param classFqn The fully qualified class name.
     * @param hex The hexadecimal color string.
     */
    record Color(java.lang.String classFqn, java.lang.String hex)
            implements TraceValue {} // Color
} // TraceValue
