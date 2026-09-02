package cs1302.tracer.trace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Asynchronously drains an {@link InputStream} into an in-memory byte buffer and provides
 * synchronization barriers to ensure all emitted bytes are captured before inspection.
 */
public class StreamDrainer implements AutoCloseable {

    private static final int BUFFER_SIZE = 4096;
    private static final long DEFAULT_MAX_WAIT_MILLIS = 50;
    private static final long DEFAULT_QUIET_PERIOD_MILLIS = 5;

    private final InputStream source;
    private final ByteArrayOutputStream sink;
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
        this.source = source;
        this.sink = new ByteArrayOutputStream();
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
                    sink.write(buffer, 0, read);
                } // synchronized
                lastReadNanos = System.nanoTime();
            } catch (IOException ioe) {
                break;
            } // try
        } // while
    } // drainLoop

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
} // StreamDrainer
