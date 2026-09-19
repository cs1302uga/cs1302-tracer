package cs1302.tracer.trace;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents an immutable slice of captured output bytes without requiring premature copying.
 */
public final class OutputSlice {

    private static final byte[] EMPTY = new byte[0];
    private static final OutputSlice EMPTY_SLICE = new OutputSlice(EMPTY, null, 0, 0);

    private final byte[] directBytes;
    private final transient StreamDrainer drainer;
    private final int offset;
    private final int length;

    /**
     * Constructs a private OutputSlice instance.
     *
     * @param directBytes Backing array when not backed by drainer.
     * @param drainer Backing StreamDrainer instance, or null.
     * @param offset Byte offset into backing buffer.
     * @param length Byte length of this slice.
     */
    private OutputSlice(byte[] directBytes, StreamDrainer drainer, int offset, int length) {
        this.directBytes = directBytes;
        this.drainer = drainer;
        this.offset = Math.max(0, offset);
        this.length = Math.max(0, length);
    } // OutputSlice

    /**
     * Returns an empty output slice.
     *
     * @return An empty OutputSlice instance.
     */
    public static OutputSlice empty() {
        return EMPTY_SLICE;
    } // empty

    /**
     * Wraps an entire byte array as an OutputSlice.
     *
     * @param bytes Direct byte array, or null.
     * @return OutputSlice instance.
     */
    public static OutputSlice from(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return EMPTY_SLICE;
        } // if
        return new OutputSlice(bytes.clone(), null, 0, bytes.length);
    } // from

    /**
     * Creates an OutputSlice representing a subrange of a byte array.
     *
     * @param bytes Direct byte array, or null.
     * @param offset Starting byte index.
     * @param length Number of bytes in slice.
     * @return OutputSlice instance.
     */
    public static OutputSlice from(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || offset >= bytes.length) {
            return EMPTY_SLICE;
        } // if
        int safeOffset = Math.max(0, offset);
        int safeLength = Math.min(length, bytes.length - safeOffset);
        if (safeLength <= 0) {
            return EMPTY_SLICE;
        } // if
        return new OutputSlice(
                Arrays.copyOfRange(bytes, safeOffset, safeOffset + safeLength),
                null, 0, safeLength);
    } // from

    /**
     * Wraps a shared immutable byte array without defensive copying.
     *
     * @param bytes Direct shared byte array, or null.
     * @param offset Starting byte index.
     * @param length Number of bytes in slice.
     * @return OutputSlice instance.
     */
    static OutputSlice wrapShared(byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0 || offset >= bytes.length) {
            return EMPTY_SLICE;
        } // if
        int safeOffset = Math.max(0, offset);
        int safeLength = Math.min(length, bytes.length - safeOffset);
        return new OutputSlice(bytes, null, safeOffset, safeLength);
    } // wrapShared

    /**
     * Creates an OutputSlice backed by an active StreamDrainer sink.
     *
     * @param drainer The source StreamDrainer.
     * @param offset Starting byte offset in drainer buffer.
     * @param length Captured byte length.
     * @return OutputSlice instance.
     */
    public static OutputSlice from(StreamDrainer drainer, int offset, int length) {
        if (drainer == null || length <= 0) {
            return EMPTY_SLICE;
        } // if
        int drainerSize = drainer.size();
        if (drainerSize <= 0 || offset >= drainerSize) {
            return EMPTY_SLICE;
        } // if
        int safeOffset = Math.max(0, offset);
        int safeLength = Math.min(length, drainerSize - safeOffset);
        return new OutputSlice(null, drainer, safeOffset, safeLength);
    } // from

    /**
     * Materializes this slice into a self-contained slice detached from any drainer.
     *
     * @return Materialized OutputSlice instance.
     */
    public OutputSlice materialize() {
        if (drainer == null) {
            return this;
        } // if
        return from(toByteArray());
    } // materialize

    /**
     * Returns the number of bytes contained in this slice.
     *
     * @return Byte length.
     */
    public int length() {
        return length;
    } // length

    /**
     * Returns true if this slice contains zero bytes.
     *
     * @return True if empty.
     */
    public boolean isEmpty() {
        return length == 0;
    } // isEmpty

    /**
     * Materializes and returns the slice contents as a newly allocated byte array.
     *
     * @return Copied byte array.
     */
    public byte[] toByteArray() {
        if (length == 0) {
            return EMPTY;
        } // if
        if (drainer != null) {
            return drainer.getBytes(offset, length);
        } // if
        if (offset == 0 && length == directBytes.length) {
            return directBytes.clone();
        } // if
        return Arrays.copyOfRange(directBytes, offset, offset + length);
    } // toByteArray

    /**
     * Returns the contents of this slice decoded as a UTF-8 string.
     *
     * @return Decoded string.
     */
    public String asUtf8String() {
        return asString(StandardCharsets.UTF_8);
    } // asUtf8String

    /**
     * Returns the contents of this slice decoded using the specified charset.
     *
     * @param charset Character encoding to use.
     * @return Decoded string.
     */
    public String asString(Charset charset) {
        if (length == 0) {
            return "";
        } // if
        if (drainer != null) {
            return drainer.getString(offset, length, charset);
        } // if
        return new String(directBytes, offset, length, charset);
    } // asString

    /**
     * Creates a new sub-slice with relative offset and length.
     *
     * @param relativeOffset Relative byte offset from the start of this slice.
     * @param subLength Desired byte length of the sub-slice.
     * @return New sub-slice.
     */
    public OutputSlice subSlice(int relativeOffset, int subLength) {
        if (subLength <= 0 || relativeOffset >= length) {
            return EMPTY_SLICE;
        } // if
        int safeOffset = Math.max(0, relativeOffset);
        int safeLength = Math.min(subLength, length - safeOffset);
        return new OutputSlice(directBytes, drainer, offset + safeOffset, safeLength);
    } // subSlice

    /**
     * Creates a new sub-slice starting at the specified relative offset.
     *
     * @param relativeOffset Relative byte offset from the start of this slice.
     * @return New sub-slice.
     */
    public OutputSlice subSlice(int relativeOffset) {
        if (relativeOffset <= 0) {
            return this;
        } // if
        return subSlice(relativeOffset, length - relativeOffset);
    } // subSlice

    /**
     * Returns the byte at the specified index within this slice.
     *
     * @param index The 0-based index within this slice.
     * @return The byte value at the index.
     */
    public byte byteAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " out of bounds for length " + length);
        } // if
        if (drainer != null) {
            return drainer.byteAt(offset + index);
        } // if
        return directBytes[offset + index];
    } // byteAt

    /**
     * Streams all bytes in this slice to the given consumer without allocating a byte array.
     *
     * @param consumer Consumer called for each byte.
     * @throws IOException If the consumer throws an IOException.
     */
    public void forEachByte(StreamDrainer.ByteConsumer consumer) throws IOException {
        if (length == 0) {
            return;
        } // if
        if (drainer != null) {
            drainer.forEachByte(offset, length, consumer);
            return;
        } // if
        for (int i = 0; i < length; i++) {
            consumer.accept(directBytes[offset + i]);
        } // for
    } // forEachByte

    /**
     * Checks if this slice starts with the specified byte prefix without allocating memory.
     *
     * @param prefix The byte sequence to look for.
     * @return True if this slice starts with prefix.
     */
    public boolean startsWith(byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return true;
        } // if
        if (this.length < prefix.length) {
            return false;
        } // if
        if (drainer != null) {
            return drainer.startsWith(offset, length, prefix);
        } // if
        for (int i = 0; i < prefix.length; i++) {
            if (directBytes[offset + i] != prefix[i]) {
                return false;
            } // if
        } // for
        return true;
    } // startsWith

    /**
     * Returns the index within this slice of the first occurrence of the specified byte,
     * starting at the specified index.
     *
     * @param b The byte to search for.
     * @param fromIndex The index to start the search from.
     * @return The index of the byte within this slice, or -1 if not found.
     */
    public int indexOf(byte b, int fromIndex) {
        if (fromIndex >= length) {
            return -1;
        } // if
        int start = Math.max(0, fromIndex);
        if (drainer != null) {
            return drainer.indexOf(offset, length, b, start);
        } // if
        for (int i = start; i < length; i++) {
            if (directBytes[offset + i] == b) {
                return i;
            } // if
        } // for
        return -1;
    } // indexOf

    /**
     * Compares the byte contents of this slice with another slice without allocating byte arrays.
     *
     * @param other The other slice to compare against.
     * @return True if byte contents match.
     */
    public boolean contentEquals(OutputSlice other) {
        if (this == normalSlice(other)) {
            return true;
        } // if
        if (other == null || this.length != other.length) {
            return false;
        } // if
        if (this.directBytes != null && other.directBytes != null) {
            return Arrays.equals(
                    this.directBytes, this.offset, this.offset + this.length,
                    other.directBytes, other.offset, other.offset + other.length);
        } // if
        for (int i = 0; i < length; i++) {
            if (this.byteAt(i) != other.byteAt(i)) {
                return false;
            } // if
        } // for
        return true;
    } // contentEquals

    /**
     * Helper returning normalized reference for self-comparison.
     *
     * @param other Target slice.
     * @return Same slice if identical.
     */
    private OutputSlice normalSlice(OutputSlice other) {
        return this == other ? this : null;
    } // normalSlice

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } // if
        if (obj instanceof OutputSlice other) {
            return contentEquals(other);
        } // if
        return false;
    } // equals

    @Override
    public int hashCode() {
        return Arrays.hashCode(toByteArray());
    } // hashCode

    @Override
    public String toString() {
        return asUtf8String();
    } // toString
} // OutputSlice
