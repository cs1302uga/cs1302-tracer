package cs1302.tracer.trace;

import com.sun.jdi.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Optional;

/** A Java value (primitive, null, reference, or object). */
public sealed interface TraceValue {

    /**
     * Convert a JDI value mirror into a TraceValue that is owned by our JVM.
     *
     * @param mainThread The thread associated with the value you want to convert.
     * @param value The value you want to convert.
     * @param outEncounteredReferences An out parameter which, if present, will accumulate the
     *                                 references that were encountered when converting this value.
     *                                 Compound data types (such as objects) may refer to other
     *                                 objects in its fields. When an object is converted, these
     *                                 contained references are converted into unique IDs that refer
     *                                 to the objects, not actual objects. In order to obtain a
     *                                 complete picture of an object, these contained references
     *                                 should also be converted later on.
     * @return A TraceValue that contains the same information as the given value.
     */
    public static TraceValue fromJdiValue(
        ThreadReference mainThread,
        Value value,
        Optional<java.util.List<ObjectReference>> outEncounteredReferences
    ) {
        VirtualMachine vm = value.virtualMachine();

        return switch (value) {
        case null -> new Null();
        case PrimitiveValue pv -> Primitive.fromJdiPrimitive(pv);
        case ArrayReference ar ->
            new List(arrayReferenceToList(mainThread, ar, outEncounteredReferences));
        case StringReference sr -> new String(sr.value());
        case ObjectReference or -> {
            Optional<Primitive> maybeWrappedPrimitive =
                Primitive.tryFromJdiValue(mainThread, value);
            if (maybeWrappedPrimitive.isPresent()) {
                outEncounteredReferences.ifPresent(l -> l.add(or));
                yield maybeWrappedPrimitive.get();
            }

            // handle composite terminating objects (lists, collections, maps)
            if (or.referenceType() instanceof ClassType ct) {
                // wrap up collections and lists
                boolean isCollection = !Collections.disjoint(ct.allInterfaces(),
                    vm.classesByName("java.util.Collection"));
                if (isCollection) {
                    try {
                        Method toArray =
                            ct.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
                        ArrayReference ar =
                            (ArrayReference) or.invokeMethod(mainThread, toArray,
                                java.util.List.of(),
                                0);
                        boolean isList = !Collections.disjoint(ct.allInterfaces(),
                            vm.classesByName("java.util.List"));
                        java.util.List<TraceValue> traceArray =
                            arrayReferenceToList(mainThread, ar,
                                outEncounteredReferences);
                        if (isList) {
                            yield new List(traceArray);
                        } else {
                            yield new Collection(traceArray);
                        }
                    } catch (IllegalArgumentException | ClassNotLoadedException |
                             InvalidTypeException e) {
                        throw new IllegalStateException(
                            "The previous exception should not have been able to occur.", e);
                    } catch (InvocationException e) {
                        // the collection object threw an exception while we were examining its
                        // state, so we can't parse it
                    } catch (IncompatibleThreadStateException e) {
                        // rethrow as an unchecked exception so we don't have to clutter siguatures
                        // up the calling chain
                        throw new IllegalArgumentException(
                            "Expected the passed thread to be suspended by an event.", e);
                    }
                } else {
                    Optional<Map> maybeMap = Map.tryFromJdiObjectReference(mainThread, or,
                        outEncounteredReferences);
                    if (maybeMap.isPresent()) {
                        yield maybeMap.get();
                    }
                }
            }

            // only output a stub object (with no fields) for Java builtin types
            // (https://docs.oracle.com/en/java/javase/21/docs/api/allpackages-index.html)
            // TODO this should probably be configurable. maybe also whitelist classes
            // compiled by our program instead of blacklisting java builtins? might make it
            // unnecessary to change this if java adds new builtins later.
            java.lang.String[] builtInPackages = {
                "com.sun.", "java.", "javax.", "jdk.", "netscape.javascript.",
                "org.ietf.jgss.", "org.w3c.dom.", "org.xml.sax."
            };
            for (java.lang.String packagePrefix : builtInPackages) {
                if (or.referenceType().name().startsWith(packagePrefix)) {
                    yield new Object(or.referenceType().name(), java.util.List.of());
                }
            }

            // not a terminating object, fall back to simply listing object fields
            java.util.Collection<ExecutionSnapshot.Field> objectSnapshotFields =
                new ArrayList<>();
            java.util.List<Field> objectJdiFields = or.referenceType().allFields().stream()
                .filter(f -> !f.isStatic())
                .toList();
            for (Field objectField : objectJdiFields) {
                Value fieldValue = or.getValue(objectField);
                switch (fieldValue) {
                case null -> objectSnapshotFields.add(
                    new ExecutionSnapshot.Field(objectField.typeName(), objectField.name(),
                        new Null()));
                case PrimitiveValue pf -> objectSnapshotFields
                    .add(new ExecutionSnapshot.Field(objectField.typeName(), objectField.name(),
                        Primitive.fromJdiPrimitive(pf)));
                case ObjectReference of -> {
                    objectSnapshotFields.add(
                        new ExecutionSnapshot.Field(objectField.typeName(), objectField.name(),
                            new Reference(of.uniqueID())));
                    outEncounteredReferences.ifPresent(l -> l.add(of));
                }
                default -> {
                }
                }
            }

            yield new Object(or.referenceType().name(), objectSnapshotFields);
        }
        default -> throw new IllegalArgumentException(
            "Couldn't figure out what this value is. This should be unreachable.");
        };
    }

    /**
     * Convert a mirrored ArrayReference into an owned List.
     *
     * @param mainThread The thread associated with the ArrayReference you want to convert.
     * @param arrayReference The ArrayReference you want to convert.
     * @param outEncounteredReferences An out parameter for references encountered in the referenced
     *                                 array. For more information, see
     *                                 {@link #fromJdiValue(ThreadReference, Value, Optional)}.
     * @return A List with the same contents as the ArrayReference.
     */
    private static java.util.List<TraceValue> arrayReferenceToList(
        ThreadReference mainThread,
        ArrayReference arrayReference,
        Optional<java.util.List<ObjectReference>> outEncounteredReferences
    ) {
        java.util.List<TraceValue> tvs = new ArrayList<>(arrayReference.length());

        for (int i = 0; i < arrayReference.length(); i++) {
            switch (arrayReference.getValue(i)) {
            case null -> tvs.add(new Null());
            case PrimitiveValue pv -> tvs.add(Primitive.fromJdiPrimitive(pv));
            case ObjectReference or -> {
                outEncounteredReferences.ifPresent(l -> l.add(or));
                tvs.add(new Reference(or.uniqueID()));
            }
            case Value v -> tvs.add(fromJdiValue(mainThread, v, outEncounteredReferences));
            }
        }

        return tvs;
    }

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
                """
                    Primitive value must be one of the following types:
                    boolean, byte, char, double, float, integer, long, short
                    """);
            };
        }

        /**
         * Try to convert a mirrored JDI primitive or primitive wrapper object into a primitive
         * TraceValue.
         *
         * @param mainThread The thread associated with the value you want to convert.
         * @param value The mirrored JDI value to convert.
         * @return The converted primitive TraceValue, or empty if conversion failed.
         */
        static Optional<Primitive> tryFromJdiValue(ThreadReference mainThread, Value value) {
            if (value instanceof PrimitiveValue pv) {
                return Optional.of(Primitive.fromJdiPrimitive(pv));
            }

            if (!(value instanceof ObjectReference objectReference) ||
                !(value.type() instanceof ClassType)) {
                return Optional.empty();
            }

            ClassType classType = (ClassType) objectReference.type();

            java.lang.String getterPrefix = switch (classType.signature()) {
            case "Ljava/lang/Boolean;" -> "boolean";
            case "Ljava/lang/Byte;" -> "byte";
            case "Ljava/lang/Character;" -> "char";
            case "Ljava/lang/Short;" -> "short";
            case "Ljava/lang/Integer;" -> "int";
            case "Ljava/lang/Long;" -> "long";
            case "Ljava/lang/Float;" -> "float";
            case "Ljava/lang/Double;" -> "double";
            default -> null;
            };

            if (getterPrefix == null) {
                // value is not a primitive wrapper
                return Optional.empty();
            }

            java.lang.String primitiveJniSignature = switch (getterPrefix) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> throw new IllegalStateException("Unreachable.");
            };

            Method getterMethod = classType.concreteMethodByName(getterPrefix + "Value",
                "()" + primitiveJniSignature);

            if (getterMethod == null) {
                throw new IllegalStateException(java.lang.String.format(
                    "Expected JDI value with JNI signature %s to have a method named %s with JNI "
                    + "signature %s.",
                    classType.signature(), getterPrefix + "Value", "()" + classType.signature()));
            }

            try {
                // this cast is safe because we've confirmed that the return type is a primitive
                // via the previous switch and the method signature we requested
                PrimitiveValue primitiveValue =
                    (PrimitiveValue) objectReference.invokeMethod(mainThread, getterMethod,
                        java.util.List.of(), 0);

                return Optional.of(Primitive.fromJdiPrimitive(primitiveValue));
            } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException
                     | InvocationException e) {
                // these exceptions should be impossible to trigger since given the constraints
                // we established above.
                throw new IllegalStateException(
                    "The previous exception should not have been able to occur.", e);
            } catch (IncompatibleThreadStateException e) {
                // rethrow as an unchecked exception so we don't have to clutter siguatures up
                // the calling chain
                throw new IllegalArgumentException(
                    "Expected the passed thread to be suspended by an event.", e);
            }
        }

        /**
         * Convert this PrimitiveValue into a
         * <a href="https://en.wikipedia.org/wiki/Primitive_wrapper_class_in_Java">primitive wrapper
         * object</a> (Boolean, Byte, Character, etc).
         *
         * @return A primitive wrapper object, upcast to Object since the wrappers don't share
         *         any other common ancestor.
         */
        java.lang.Object toWrapperObject();

        /** A {@code boolean} primitive. */
        record Boolean(boolean value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code byte} primitive. */
        record Byte(byte value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code char} primitive. */
        record Character(char value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code short} primitive. */
        record Short(short value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** An int primitive. */
        record Integer(int value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code long} primitive. */
        record Long(long value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code float} primitive. */
        record Float(float value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }

        /** A {@code double} primitive. */
        record Double(double value) implements Primitive {
            /** {@inheritDoc} */
            public java.lang.Object toWrapperObject() {
                return value;
            }
        }
    }

    /** A reference. */
    record Reference(long uniqueId) implements TraceValue {
    }

    /** The null value. */
    record Null() implements TraceValue {
    }

    /** An object. */
    record Object(
        java.lang.String classFqn,
        java.util.Collection<ExecutionSnapshot.Field> fields) implements TraceValue {
    }

    /** A string. */
    record String(java.lang.String value) implements TraceValue {
    }

    /** An object that implements {@link java.util.Map}. */
    record Map(
        java.util.Map<? extends TraceValue, ? extends TraceValue> value) implements TraceValue {

        /**
         * Try to convert a mirrored JDI object into a Map TraceValue.
         *
         * @param mainThread The thread associated with the value you want to convert.
         * @param or The mirrored JDI object to convert.
         * @param outEncounteredReferences An out parameter for references encountered in the
         *                                 referenced array. For more information, see
         *                                 {@link #fromJdiValue(ThreadReference, Value, Optional)}.
         * @return The converted Map TraceValue, or empty if conversion failed.
         */
        public static Optional<Map> tryFromJdiObjectReference(
            ThreadReference mainThread,
            ObjectReference or,
            Optional<java.util.List<ObjectReference>> outEncounteredReferences
        ) {
            ClassType ct = (ClassType) or.referenceType();
            VirtualMachine vm = or.virtualMachine();

            boolean isMap =
                !Collections.disjoint(ct.allInterfaces(), vm.classesByName("java.util.Map"));
            if (!isMap) {
                return Optional.empty();
            }

            try {
                Method entrySet = ct.concreteMethodByName("entrySet", "()Ljava/util/Set;");
                ObjectReference entries =
                    (ObjectReference) or.invokeMethod(mainThread, entrySet, java.util.List.of(),
                        0);
                ClassType entriesCt = (ClassType) entries.referenceType();
                Method entriesToArray =
                    entriesCt.concreteMethodByName("toArray", "()[Ljava/lang/Object;");
                ArrayReference ar =
                    (ArrayReference) entries.invokeMethod(mainThread, entriesToArray,
                        java.util.List.of(),
                        0);
                java.util.Map<TraceValue, TraceValue> map = new HashMap<>();
                for (int i = 0; i < ar.length(); i++) {
                    ObjectReference entry = (ObjectReference) ar.getValue(i);
                    ClassType entryCt = (ClassType) entry.referenceType();
                    Method entryGetKey =
                        entryCt.concreteMethodByName("getKey", "()Ljava/lang/Object;");
                    ObjectReference entryKey =
                        (ObjectReference) entry.invokeMethod(mainThread, entryGetKey,
                            java.util.List.of(),
                            0);
                    Method entryGetValue =
                        entryCt.concreteMethodByName("getValue", "()Ljava/lang/Object;");
                    ObjectReference entryValue =
                        (ObjectReference) entry.invokeMethod(mainThread, entryGetValue,
                            java.util.List.of(), 0);
                    outEncounteredReferences.ifPresent(l -> l.add(entryKey));
                    outEncounteredReferences.ifPresent(l -> l.add(entryValue));
                    map.put(new Reference(entryKey.uniqueID()),
                        new Reference(entryValue.uniqueID()));
                }
                return Optional.of(new Map(map));
            } catch (IllegalArgumentException | ClassNotLoadedException | InvalidTypeException e) {
                throw new IllegalStateException(
                    "The previous exception should not have been able to occur.", e);
            } catch (InvocationException e) {
                // the map object threw an exception while we were examining its state, so we
                // can't parse it
                return Optional.empty();
            } catch (IncompatibleThreadStateException e) {
                // rethrow as an unchecked exception so we don't have to clutter siguatures up
                // the calling chain
                throw new IllegalArgumentException(
                    "Expected the passed thread to be suspended by an event.", e);
            }
        }
    }

    /** An object that implements {@link java.util.Collection}. */
    record Collection(java.util.Collection<? extends TraceValue> value)
        implements TraceValue {
    }

    /** An object that implements {@link java.util.List}. */
    record List(java.util.List<? extends TraceValue> value)
        implements TraceValue {

    }
}
