package cs1302.tracer.trace;

import cs1302.tracer.execution.TraceSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * Asynchronously drains an {@link InputStream} into an in-memory byte buffer and provides
 * synchronization barriers to ensure all emitted bytes are captured before inspection.
 */
public class StreamDrainer implements AutoCloseable {

    private static final int BUFFER_SIZE = 4096;
    private static final long DEFAULT_MAX_WAIT_MILLIS = 50;
    private static final long DEFAULT_QUIET_PERIOD_MILLIS = 5;

    private volatile TraceSession session;
    private final long limit;
    private final InputStream source;
    private final AccessibleByteArrayOutputStream sink;
    private final Thread readerThread;

    private volatile long lastReadNanos;
    private volatile boolean closed;
    private volatile boolean eofReached;

    /**
     * Constructs a new StreamDrainer for the specified source stream.
     *
     * @param source The input stream to drain.
     */
    public StreamDrainer(InputStream source) {
        if (source == null) {
            throw new IllegalArgumentException("source input stream cannot be null");
        } // if
        this.session = TraceSession.current();
        this.limit = session == null ? 0 : session.outputLimit();
        if (session != null) {
            session.register(this);
        } // if
        this.source = source;
        this.sink = new AccessibleByteArrayOutputStream();
        this.lastReadNanos = System.nanoTime();
        this.closed = false;
        this.eofReached = false;
        this.readerThread = Thread.ofVirtual().start(this::drainLoop);
    } // StreamDrainer

    /**
     * Continuous background loop reading chunks from source into the sink.
     */
    private void drainLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (!closed) {
            try {
                int read = source.read(buffer);
                if (read == -1) {
                    eofReached = true;
                    break;
                } // if
                synchronized (sink) {
                    int retained = limit == 0 ? read
                            : (int) Math.min(read, Math.max(0, limit - sink.size()));
                    sink.write(buffer, 0, retained);
                    if (retained < read) {
                        TraceSession active = session;
                        if (active != null) {
                            active.stop("output_limit");
                        } // if
                    } // if
                } // synchronized
                lastReadNanos = System.nanoTime();
            } catch (IOException ioe) {
                break;
            } // try
        } // while
    } // drainLoop

    /**
     * Detaches the active trace session so this drainer does not retain it.
     */
    public void detachSession() {
        this.session = null;
    } // detachSession

    /**
     * Synchronizes the stream using default wait and quiet-period thresholds.
     */
    public void sync() {
        sync(DEFAULT_MAX_WAIT_MILLIS, DEFAULT_QUIET_PERIOD_MILLIS);
    } // sync

    /**
     * Synchronizes the stream with adaptive quiet-period polling without contending on the
     * underlying input stream lock.
     *
     * <p>If bytes have recently been read or arrive after the flush, this method waits
     * until no new bytes arrive for {@code quietPeriodMillis}, or until {@code maxWaitMillis}
     * elapses. If no bytes arrive after a micro-wait, it returns immediately.
     *
     * @param maxWaitMillis Maximum milliseconds to wait.
     * @param quietPeriodMillis Milliseconds of quiet time required after reading bytes.
     */
    public void sync(long maxWaitMillis, long quietPeriodMillis) {
        if (eofReached || closed) {
            return;
        } // if

        long startNanos = System.nanoTime();
        long maxWaitNanos = maxWaitMillis * 1_000_000L;
        long quietPeriodNanos = quietPeriodMillis * 1_000_000L;
        int initialSize = size();

        try {
            // Micro-wait to allow in-flight OS pipe writes to be processed by the reader thread
            Thread.sleep(2);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } // try

        while (System.nanoTime() - startNanos < maxWaitNanos) {
            if (eofReached) {
                break;
            } // if

            int currentSize = size();
            boolean bytesArrived = currentSize > initialSize;

            if (bytesArrived) {
                if (System.nanoTime() - lastReadNanos >= quietPeriodNanos) {
                    break;
                } // if
            } else {
                // If no bytes arrived after the micro-wait, the stream is idle
                break;
            } // if

            try {
                Thread.sleep(1);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } // try
        } // while
    } // sync

    /**
     * Returns a copy of the accumulated bytes.
     *
     * @return Byte array containing all captured output.
     */
    public byte[] getBytes() {
        synchronized (sink) {
            return sink.toByteArray();
        } // synchronized
    } // getBytes

    /**
     * Returns an OutputSlice referencing the current accumulated bytes in this drainer.
     *
     * @return Captured OutputSlice.
     */
    public OutputSlice snapshotOutput() {
        return OutputSlice.from(this, 0, size());
    } // snapshotOutput

    /**
     * Returns a copy of the specified subrange of accumulated bytes.
     *
     * @param offset Starting byte offset.
     * @param length Number of bytes to copy.
     * @return Subrange byte array.
     */
    public byte[] getBytes(int offset, int length) {
        synchronized (sink) {
            return sink.copyRange(offset, length);
        } // synchronized
    } // getBytes

    /**
     * Returns the byte at the specified index in the captured buffer.
     *
     * @param index Byte index to inspect.
     * @return Byte at specified index.
     */
    public byte byteAt(int index) {
        synchronized (sink) {
            return sink.byteAt(index);
        } // synchronized
    } // byteAt

    /**
     * Functional interface for streaming individual bytes with IO exceptions.
     */
    @FunctionalInterface
    public interface ByteConsumer {
        /**
         * Consumes a single byte.
         *
         * @param b Byte value.
         * @throws IOException On I/O failure.
         */
        void accept(byte b) throws IOException;
    } // ByteConsumer

    /**
     * Streams a subrange of bytes under a single lock acquisition to the specified consumer.
     *
     * @param offset Starting byte offset.
     * @param length Number of bytes to consume.
     * @param consumer Consumer invoked for each byte.
     * @throws IOException If the consumer throws an IOException.
     */
    public void forEachByte(int offset, int length, ByteConsumer consumer) throws IOException {
        synchronized (sink) {
            int safeOffset = Math.max(0, offset);
            int safeLength = Math.min(length, sink.size() - safeOffset);
            for (int i = 0; i < safeLength; i++) {
                consumer.accept(sink.byteAt(safeOffset + i));
            } // for
        } // synchronized
    } // forEachByte

    /**
     * Checks if a subrange of bytes starts with the given prefix under a single lock acquisition.
     *
     * @param offset Starting byte offset.
     * @param length Available byte length.
     * @param prefix Prefix to check.
     * @return True if the subrange starts with the prefix.
     */
    public boolean startsWith(int offset, int length, byte[] prefix) {
        if (prefix == null || prefix.length == 0) {
            return true;
        } // if
        synchronized (sink) {
            int safeOffset = Math.max(0, offset);
            int safeLength = Math.min(length, sink.size() - safeOffset);
            if (safeLength < prefix.length) {
                return false;
            } // if
            for (int i = 0; i < prefix.length; i++) {
                if (sink.byteAt(safeOffset + i) != prefix[i]) {
                    return false;
                } // if
            } // for
            return true;
        } // synchronized
    } // startsWith

    /**
     * Searches for the first occurrence of a byte in a subrange under a single lock acquisition.
     *
     * @param offset Starting byte offset.
     * @param length Available byte length.
     * @param b Byte to find.
     * @param fromIndex Relative index within the subrange to start searching.
     * @return Relative index of first match within the subrange, or -1 if not found.
     */
    public int indexOf(int offset, int length, byte b, int fromIndex) {
        synchronized (sink) {
            int safeOffset = Math.max(0, offset);
            int safeLength = Math.min(length, sink.size() - safeOffset);
            int start = Math.max(0, fromIndex);
            for (int i = start; i < safeLength; i++) {
                if (sink.byteAt(safeOffset + i) == b) {
                    return i;
                } // if
            } // for
            return -1;
        } // synchronized
    } // indexOf

    /**
     * Decodes the specified subrange of accumulated bytes into a string.
     *
     * @param offset Starting byte offset.
     * @param length Number of bytes to decode.
     * @param charset Character encoding to use.
     * @return Decoded string.
     */
    public String getString(int offset, int length, Charset charset) {
        synchronized (sink) {
            return sink.toStringRange(offset, length, charset);
        } // synchronized
    } // getString

    /**
     * Returns the number of bytes currently accumulated in the sink.
     *
     * @return Accumulated byte count.
     */
    public int size() {
        synchronized (sink) {
            return sink.size();
        } // synchronized
    } // size

    /**
     * Returns true if EOF has been encountered on the source stream.
     *
     * @return True if EOF reached.
     */
    public boolean isEof() {
        return eofReached;
    } // isEof

    /**
     * Waits up to {@code timeoutMillis} for the stream reader to reach EOF.
     *
     * @param timeoutMillis Maximum milliseconds to wait.
     */
    public void waitForEof(long timeoutMillis) {
        try {
            readerThread.join(java.time.Duration.ofMillis(timeoutMillis));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } // try
    } // waitForEof

    @Override
    public void close() {
        closed = true;
        detachSession();
        try {
            source.close();
        } catch (IOException ignored) {
            // ignore stream close errors
        } // try
        try {
            readerThread.join(java.time.Duration.ofMillis(100));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } // try
    } // close

    /**
     * Internal ByteArrayOutputStream that allows direct subrange access without copying.
     */
    private static final class AccessibleByteArrayOutputStream extends ByteArrayOutputStream {

        /**
         * Copies a subrange of bytes from the internal buffer.
         *
         * @param offset Starting byte offset.
         * @param length Number of bytes to copy.
         * @return Copied byte array.
         */
        byte[] copyRange(int offset, int length) {
            if (length <= 0 || offset >= count) {
                return new byte[0];
            } // if
            int safeOffset = Math.max(0, offset);
            int safeLen = Math.min(length, count - safeOffset);
            byte[] result = new byte[safeLen];
            System.arraycopy(buf, safeOffset, result, 0, safeLen);
            return result;
        } // copyRange

        /**
         * Decodes a subrange of bytes from the internal buffer as a string.
         *
         * @param offset Starting byte offset.
         * @param length Number of bytes to decode.
         * @param charset Character set to decode with.
         * @return Decoded string.
         */
        String toStringRange(int offset, int length, Charset charset) {
            if (length <= 0 || offset >= count) {
                return "";
            } // if
            int safeOffset = Math.max(0, offset);
            int safeLen = Math.min(length, count - safeOffset);
            return new String(buf, safeOffset, safeLen, charset);
        } // toStringRange

        /**
         * Returns the byte at the specified index within the buffer.
         *
         * @param index Byte index.
         * @return Byte at index.
         */
        byte byteAt(int index) {
            return buf[index];
        } // byteAt
    } // AccessibleByteArrayOutputStream
} // StreamDrainer
