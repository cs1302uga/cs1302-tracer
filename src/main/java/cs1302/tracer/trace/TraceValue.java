package cs1302.tracer.trace;

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
                        arrayReferenceToList(
                                mainThread,
                                ar,
                                outEncounteredReferences,
                                astTypeResolver,
                                objectTypeMap));
            } // case
            case StringReference sr -> new String(sr.value());
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

        if (isBuiltInType(or.referenceType().name())) {
            return new Object(or.referenceType().name(), java.util.List.of());
        } // if

        return handleRegularObject(or, outEncounteredReferences, astTypeResolver, objectTypeMap);
    } // handleObjectReference

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
            try {
                Method toArray = ct.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
                ArrayReference ar =
                        (ArrayReference) or.invokeMethod(
                                mainThread, toArray, java.util.List.of(), 0);
                boolean isList =
                        !Collections.disjoint(
                                ct.allInterfaces(), vm.classesByName("java.util.List"));
                java.util.List<TraceValue> traceArray = arrayReferenceToList(
                        mainThread, ar, outEncounteredReferences, astTypeResolver, objectTypeMap);
                java.lang.String colTypeName = or.referenceType().name();
                if (objectTypeMap != null && objectTypeMap.containsKey(or.uniqueID())) {
                    colTypeName = objectTypeMap.get(or.uniqueID());
                } // if
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
        } // if

        Optional<Map> maybeMap =
                Map.tryFromJdiObjectReference(mainThread, or, outEncounteredReferences);
        if (maybeMap.isPresent()) {
            Map map = maybeMap.get();
            if (objectTypeMap != null && objectTypeMap.containsKey(or.uniqueID())) {
                return Optional.of(new Map(objectTypeMap.get(or.uniqueID()), map.value()));
            } // if
            return Optional.of(map);
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
     * Converts a regular user object reference with fields.
     *
     * @param or The object reference.
     * @param outEncounteredReferences Accumulates references.
     * @param astTypeResolver AstTypeResolver instance.
     * @param objectTypeMap Reified type map.
     * @return Converted Object TraceValue.
     */
    private static TraceValue handleRegularObject(
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        java.lang.String rawClassFqn = or.referenceType().name();
        java.lang.String reifiedClassType =
                (objectTypeMap != null) ? objectTypeMap.get(or.uniqueID()) : null;
        java.lang.String classFqn = (reifiedClassType != null) ? reifiedClassType : rawClassFqn;
        java.util.Map<java.lang.String, java.lang.String> bindings =
                (reifiedClassType != null && astTypeResolver != null)
                        ? astTypeResolver.getTypeBindings(rawClassFqn, reifiedClassType)
                        : Collections.emptyMap();
        Optional<AstTypeResolver.ClassGenericInfo> classInfo =
                astTypeResolver != null
                        ? astTypeResolver.getClassGenericInfo(rawClassFqn)
                        : Optional.empty();

        java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields = new ArrayList<>();
        java.util.List<Field> objectJdiFields =
                or.referenceType().allFields().stream().filter(f -> !f.isStatic()).toList();

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
                if (objectTypeMap != null
                        && fieldTypeName != null
                        && (fieldTypeName.contains("<")
                        || (classInfo.isPresent()
                        && classInfo.get().typeParameters().isEmpty()))) {
                    objectTypeMap.putIfAbsent(of.uniqueID(), fieldTypeName);
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

        return new Object(classFqn, objectSnapshotFields);
    } // handleRegularObject

    /**
     * Convert a mirrored ArrayReference into an owned List.
     *
     * @param mainThread The thread associated with the ArrayReference you want to convert.
     * @param arrayReference The ArrayReference you want to convert.
     * @param outEncounteredReferences An out parameter for references encountered in the array.
     * @param astTypeResolver Optional AstTypeResolver.
     * @param objectTypeMap Reified type map.
     * @return A List with the same contents as the ArrayReference.
     */
    private static java.util.List<TraceValue> arrayReferenceToList(
            ThreadReference mainThread,
            ArrayReference arrayReference,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences,
            AstTypeResolver astTypeResolver,
            java.util.Map<java.lang.Long, java.lang.String> objectTypeMap) {
        java.util.List<TraceValue> tvs = new ArrayList<>(arrayReference.length());

        for (int i = 0; i < arrayReference.length(); i++) {
            switch (arrayReference.getValue(i)) {
            case null -> tvs.add(new Null());
            case PrimitiveValue pv -> tvs.add(Primitive.fromJdiPrimitive(pv));
            case ObjectReference or -> {
                outEncounteredReferences.ifPresent(l -> l.add(or));
                tvs.add(new Reference(or.uniqueID()));
            } // case
            case Value v -> tvs.add(fromJdiValue(
                    mainThread, v, outEncounteredReferences, astTypeResolver, objectTypeMap));
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
            java.lang.String getterPrefix = getPrimitiveGetterPrefix(classType.signature());
            if (getterPrefix == null) {
                return Optional.empty();
            } // if

            java.lang.String jniSig = getPrimitiveJniSignature(getterPrefix);
            Method getterMethod = classType.concreteMethodByName(
                    getterPrefix + "Value", "()" + jniSig);
            if (getterMethod == null) {
                throw new IllegalStateException(java.lang.String.format(
                        "Expected method %s with signature ()%s.",
                        getterPrefix + "Value", jniSig));
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
         * Gets getter prefix for primitive wrappers.
         *
         * @param signature Class signature.
         * @return Getter prefix or null.
         */
        private static java.lang.String getPrimitiveGetterPrefix(java.lang.String signature) {
            return switch (signature) {
                case "Ljava/lang/Boolean;" -> "boolean";
                case "Ljava/lang/Byte;" -> "byte";
                case "Ljava/lang/Character;" -> "char";
                case "Ljava/lang/Short;" -> "short";
                case "Ljava/lang/Integer;" -> "int";
                case "Ljava/lang/Long;" -> "long";
                case "Ljava/lang/Float;" -> "float";
                case "Ljava/lang/Double;" -> "double";
                default -> null;
            }; // switch
        } // getPrimitiveGetterPrefix

        /**
         * Gets primitive JNI signature character.
         *
         * @param prefix Getter prefix.
         * @return JNI signature string.
         */
        private static java.lang.String getPrimitiveJniSignature(java.lang.String prefix) {
            return switch (prefix) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> throw new IllegalStateException("Unreachable.");
            }; // switch
        } // getPrimitiveJniSignature

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
     */
    record Object(
            java.lang.String classFqn,
            java.util.Collection<ExecutionSnapshot.Field> fields)
            implements TraceValue {} // Object

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
                    outEncounteredReferences.ifPresent(l -> l.add(entryKey));
                    outEncounteredReferences.ifPresent(l -> l.add(entryValue));
                    map.put(
                            new Reference(entryKey.uniqueID()),
                            new Reference(entryValue.uniqueID()));
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
} // TraceValue
