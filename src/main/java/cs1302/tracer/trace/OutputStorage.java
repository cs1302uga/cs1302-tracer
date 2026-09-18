package cs1302.tracer.trace;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** Retains cumulative output once, keeping old prefixes stable across appends. */
public final class OutputStorage {
    private static final byte[] EMPTY = new byte[0];
    private History stdout = new History();
    private History stderr = new History();

    /**
     * Captures output prefixes and keeps only metadata in the underlying record.
     * @param snapshot Complete captured state.
     * @return Compact state with independently materializable output.
     */
    public Snapshot capture(ExecutionSnapshot snapshot) {
        stdout = stdout.append(snapshot.stdout());
        stderr = stderr.append(snapshot.stderr());
        ExecutionSnapshot metadata = new ExecutionSnapshot(snapshot.stack(), snapshot.statics(),
                snapshot.heap(), EMPTY, EMPTY, snapshot.sourcePath(),
                snapshot.stdinConsumed(), snapshot.stdinOffset());
        return new Captured(metadata, stdout, stdout.size(), stderr, stderr.size());
    } // capture

    /**
     * Refreshes output while preserving shared history when possible.
     * @param snapshot Original state.
     * @param out Updated stdout.
     * @param err Updated stderr.
     * @return Refreshed compact state.
     */
    public static Snapshot refresh(Snapshot snapshot, byte[] out, byte[] err) {
        OutputStorage storage = new OutputStorage();
        if (snapshot instanceof Captured captured) {
            storage.stdout = captured.out;
            storage.stderr = captured.err;
        } // if
        return storage.capture(new ExecutionSnapshot(snapshot.stack(), snapshot.statics(),
                snapshot.heap(), out, err, snapshot.sourcePath(), snapshot.stdinConsumed(),
                snapshot.stdinOffset()));
    } // refresh

    /**
     * Internal state; output lengths pin immutable prefixes of append-only buffers.
     * @param metadata Non-output state.
     * @param out Standard output history.
     * @param outLength Captured standard output prefix length.
     * @param err Standard error history.
     * @param errLength Captured standard error prefix length.
     */
    private record Captured(ExecutionSnapshot metadata, History out, int outLength,
            History err, int errLength) implements Snapshot {

        @Override
        public byte[] stdout() {
            return out.prefix(outLength);
        } // stdout

        @Override
        public byte[] stderr() {
            return err.prefix(errLength);
        } // stderr
    } // Captured

    /** Append-only byte history; changes to sanitized prefixes start a separate history. */
    private static final class History extends ByteArrayOutputStream {

        /**
         * Appends new bytes or forks when sanitization changes an earlier prefix.
         * @param bytes Cumulative output.
         * @return History containing exactly these bytes.
         */
        History append(byte[] bytes) {
            if (bytes.length < count || !Arrays.equals(buf, 0, count, bytes, 0, count)) {
                return new History().append(bytes);
            } // if
            write(bytes, count, bytes.length - count);
            return this;
        } // append

        /**
         * Materializes an independent output prefix.
         * @param length Captured prefix length.
         * @return Independent cumulative bytes.
         */
        byte[] prefix(int length) {
            return Arrays.copyOf(buf, length);
        } // prefix
    } // History
} // OutputStorage
