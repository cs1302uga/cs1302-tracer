package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import java.util.Optional;

/**
 * A host-owned captured Java value (primitive, null, reference, or object).
 *
 * <p>Normative References: <ul> <li>The Java Language Specification (Java SE 21 Edition), &sect;4.1 (The Kinds of Types
 * and Values). <li>The Java Language Specification (Java SE 21 Edition), &sect;4.2 (Primitive Types and Values).
 * <li>The Java Language Specification (Java SE 21 Edition), &sect;4.3 (Reference Types and Values). <li>The Java Debug
 * Interface (JDI) Specification (Java SE 21 Edition, {@code com.sun.jdi.Value}). </ul>
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public sealed interface TraceValue {

    /** A primitive Java value. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    sealed interface Primitive extends TraceValue {

        /**
         * Converts this captured primitive into a Java wrapper object.
         *
         * @return A primitive wrapper object.
         */
        java.lang.Object toWrapperObject();

        /**
         * A {@code boolean} primitive. Reuse {@link #TRUE} and {@link #FALSE} when convenient;
         * direct construction remains available and record equality compares the primitive value.
         *
         * @param value Primitive value.
         */
        @API(status = API.Status.INTERNAL, since = "4.0.0")
        record Boolean(boolean value) implements Primitive {

            /**
             * Shared immutable value representing {@code true}.
             * @since 4.1.0
             */
            public static final Boolean TRUE = new Boolean(true);

            /**
             * Shared immutable value representing {@code false}.
             * @since 4.1.0
             */
            public static final Boolean FALSE = new Boolean(false);

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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
        @API(status = API.Status.INTERNAL, since = "4.0.0")
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
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Reference(long uniqueId) implements TraceValue {} // Reference

    /**
     * The null value.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Null() implements TraceValue {} // Null

    /**
     * An object.
     *
     * @param classFqn Declaring class FQN.
     * @param fields Object fields.
     * @param enumConstant Optional enum constant name if this object is an enum constant.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Object(java.lang.String classFqn, java.util.Collection<ExecutionSnapshot.Field> fields,
            Optional<java.lang.String> enumConstant) implements TraceValue {

        /**
         * Constructs an Object without an enum constant name.
         *
         * @param classFqn Declaring class FQN.
         * @param fields Object fields.
         */
        public Object(java.lang.String classFqn, java.util.Collection<ExecutionSnapshot.Field> fields) {
            this(classFqn, fields, Optional.empty());
        } // Object

    } // Object

    /**
     * A string.
     *
     * @param value String content.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record String(java.lang.String value) implements TraceValue {} // String

    /**
     * An object that implements {@link java.util.Map}.
     *
     * @param typeName Map type name.
     * @param value Key-value map entries.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Map(java.lang.String typeName, java.util.Map<? extends TraceValue, ? extends TraceValue> value)
            implements TraceValue {

        /**
         * Convenience constructor with default typeName.
         *
         * @param value The map contents.
         */
        public Map(java.util.Map<? extends TraceValue, ? extends TraceValue> value) {
            this("java.util.Map", value);
        } // Map

    } // Map

    /**
     * An object that implements {@link java.util.Collection}.
     *
     * @param typeName Collection type name.
     * @param value Collection elements.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Collection(java.lang.String typeName, java.util.Collection<? extends TraceValue> value)
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
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record List(java.lang.String typeName, java.util.List<? extends TraceValue> value) implements TraceValue {} // List

    /**
     * A lambda with reconstructed implementation.
     *
     * @param implementation Implementation expression or string.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Lambda(java.lang.String implementation) implements TraceValue {} // Lambda

    /**
     * Represents a Color object trace value.
     *
     * @param classFqn The fully qualified class name.
     * @param hex The hexadecimal color string.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    record Color(java.lang.String classFqn, java.lang.String hex) implements TraceValue {} // Color

} // TraceValue
