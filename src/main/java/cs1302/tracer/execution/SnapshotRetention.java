package cs1302.tracer.execution;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.OutputSlice;
import cs1302.tracer.trace.ValueTraversal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.TreeMap;
import java.util.Optional;

/** Owns admitted snapshot history, its accounting, and presentation projections. */
public final class SnapshotRetention {

    private static final Gson GSON = new com.google.gson.GsonBuilder()
            .registerTypeHierarchyAdapter(java.util.Optional.class,
                    (com.google.gson.JsonSerializer<java.util.Optional<?>>) (value, type, context) -> context
                            .serialize(value.orElse(null)))
            .registerTypeAdapter(OutputSlice.class, new OutputSliceTypeAdapter().nullSafe()).create();
    private final List<ExecutionSnapshot> completed = new ArrayList<>();
    private final Map<SnapshotLineKey, ExecutionSnapshot> latest = new LinkedHashMap<>();
    private final Map<ExecutionSnapshot, Long> sizes = new java.util.IdentityHashMap<>();
    private final TraceLimits limits;
    private final boolean accumulate;
    private final Runnable checkpoint;
    private long retainedBytes;
    private long captured;

    /**
     * Creates an empty retained history.
     *
     * @param limits Snapshot and retained-history budgets.
     * @param accumulate Whether to retain every admitted state.
     * @param checkpoint Cancellation and deadline check invoked during admission.
     */
    public SnapshotRetention(TraceLimits limits, boolean accumulate, Runnable checkpoint) {
        this.limits = limits;
        this.accumulate = accumulate;
        this.checkpoint = checkpoint;
    } // SnapshotRetention

    /**
     * Returns admitted snapshots in capture order.
     *
     * @return Immutable history copy.
     */
    public List<ExecutionSnapshot> snapshots() {
        return List.copyOf(this.completed);
    } // snapshots

    /**
     * Returns the number of states currently retained.
     *
     * @return Retained snapshot count.
     */
    public int size() {
        return this.completed.size();
    } // size

    /**
     * Projects final output onto the latest hit at each targeted location.
     * This presentation copy does not change budgeted history or accounting.
     *
     * @return Targeted snapshots in capture order.
     */
    public List<ExecutionSnapshot> targetedSnapshots() {
        List<ExecutionSnapshot> snapshots = new ArrayList<>(this.completed);
        if (snapshots.isEmpty()) {
            return snapshots;
        } // if
        ExecutionSnapshot last = snapshots.getLast();
        Map<Optional<String>, Set<Long>> updated = new HashMap<>();
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            ExecutionSnapshot snapshot = snapshots.get(i);
            long line = snapshot.stack().isEmpty() ? -1 : snapshot.stack().getLast().methodLine();
            if (updated.computeIfAbsent(snapshot.sourcePath(), key -> new HashSet<>()).add(line)) {
                snapshots.set(i, snapshot.withOutput(last.stdoutSlice(), last.stderrSlice()));
            } // if
        } // for
        return snapshots;
    } // targetedSnapshots

    /**
     * Returns retained storage accounting.
     *
     * @return Conservative retained byte count.
     */
    public long retainedBytes() {
        return this.retainedBytes;
    } // retainedBytes

    /**
     * Returns the number of admitted states, including replaced states.
     *
     * @return Admitted snapshot count.
     */
    public long captured() {
        return this.captured;
    } // captured

    /**
     * Checks a budget before changing the retained history.
     *
     * @param used Proposed accounted usage.
     * @param limit Zero or finite ceiling.
     * @param failure Machine-readable rejection reason.
     */
    private void enforce(long used, long limit, String failure) {
        this.checkpoint.run();
        if (limit != 0 && used > limit) {
            throw new LimitExceeded(failure);
        } // if
    } // enforce

    /** Admission failure that leaves retained history and accounting unchanged. */
    public static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates a budget rejection without an expensive stack trace.
         *
         * @param reason Machine-readable budget reason.
         */
        public LimitExceeded(String reason) {
            super(reason, null, false, false);
        } // LimitExceeded
    } // LimitExceeded

    /**
     * Commits a completed snapshot, retaining only the latest when requested.
     *
     * @param snapshot Fully extracted state.
     * @param buildingBytes Conservative extraction storage estimate.
     */
    public void commit(ExecutionSnapshot snapshot, long buildingBytes) {
        this.checkpoint.run();
        ValueTraversal.validate(snapshot);
        SnapshotCounter counter = new SnapshotCounter();
        GSON.toJson(snapshot, counter);
        long size = Math.max(buildingBytes, counter.bytes);
        enforce(size, limits.snapshotBytes(), "snapshot_byte_limit");
        enforce(Math.addExact(retainedBytes, size), limits.traceBytes(), "trace_limit");
        if (!accumulate) {
            int line = snapshot.stack().isEmpty() ? -1 : (int) snapshot.stack().getLast().methodLine();
            SnapshotLineKey key = new SnapshotLineKey(line, snapshot.sourcePath());
            ExecutionSnapshot previous = latest.put(key, snapshot);
            if (previous != null) {
                retainedBytes -= sizes.remove(previous);
                completed.remove(previous);
            } // if
        } // if
        completed.add(snapshot);
        sizes.put(snapshot, size);
        retainedBytes += size;
        captured++;
    } // commit

    /**
     * Refreshes final output on the last complete snapshot using explicit slices.
     *
     * @param stdout Captured standard output slice.
     * @param stderr Captured standard error slice.
     * @return Budget rejection reason, or null when accepted.
     */
    public String finishOutput(OutputSlice stdout, OutputSlice stderr) {
        if (completed.isEmpty()) {
            return null;
        } // if
        OutputSlice safeOut = stdout != null ? stdout.materialize() : null;
        OutputSlice safeErr = stderr != null ? stderr.materialize() : null;
        materializeSnapshots(safeOut, safeErr);
        ExecutionSnapshot last = completed.getLast();
        int outLen = safeOut != null ? safeOut.length() : last.stdoutLength();
        int errLen = safeErr != null ? safeErr.length() : last.stderrLength();
        long extra = Math.max(0, outLen - last.stdoutLength()) + Math.max(0, errLen - last.stderrLength());
        if (extra == 0) {
            return null;
        } // if
        // Raw byte arrays cost at most five ASCII JSON characters per byte in accounting.
        long newRetained = Math.addExact(retainedBytes, extra * 15);
        if (limits.snapshotBytes() != 0 && sizes.get(last) + extra * 15 > limits.snapshotBytes()) {
            return "snapshot_byte_limit";
        } // if
        if (limits.traceBytes() != 0 && newRetained > limits.traceBytes()) {
            return "trace_limit";
        } // if
        ExecutionSnapshot updated = last.withOutput(safeOut != null ? safeOut : last.stdoutSlice(),
                safeErr != null ? safeErr : last.stderrSlice());
        completed.set(completed.size() - 1, updated);
        latest.replaceAll((key, snapshot) -> snapshot == last ? updated : snapshot);
        sizes.put(updated, sizes.remove(last) + extra * 15);
        retainedBytes = newRetained;
        return null;
    } // finishOutput

    /** Materializes all completed snapshot output slices into self-contained buffers. */
    public synchronized void materializeSnapshots() {
        for (int i = 0; i < completed.size(); i++) {
            ExecutionSnapshot oldSnap = completed.get(i);
            ExecutionSnapshot newSnap = oldSnap.materializeOutput();
            updateMaterializedSnapshot(i, oldSnap, newSnap);
        } // for
    } // materializeSnapshots

    /**
     * Materializes all completed snapshot output slices using shared output buffers.
     *
     * @param sharedStdout Shared standard output buffer.
     * @param sharedStderr Shared standard error buffer.
     */
    public synchronized void materializeSnapshots(byte[] sharedStdout, byte[] sharedStderr) {
        materializeSnapshots(OutputSlice.from(sharedStdout), OutputSlice.from(sharedStderr));
    } // materializeSnapshots

    /**
     * Detaches snapshot prefixes while sharing the finalized output slices.
     *
     * @param stdout Final standard output, or null to retain captured output.
     * @param stderr Final standard error, or null to retain captured output.
     */
    private synchronized void materializeSnapshots(OutputSlice stdout, OutputSlice stderr) {
        for (int i = 0; i < completed.size(); i++) {
            ExecutionSnapshot oldSnap = completed.get(i);
            if (oldSnap.stdoutLength() == 0 && oldSnap.stderrLength() == 0) {
                continue;
            } // if
            ExecutionSnapshot newSnap = oldSnap.withOutput(
                    stdout != null ? stdout.subSlice(0, oldSnap.stdoutLength()) : oldSnap.stdoutSlice().materialize(),
                    stderr != null ? stderr.subSlice(0, oldSnap.stderrLength()) : oldSnap.stderrSlice().materialize());
            updateMaterializedSnapshot(i, oldSnap, newSnap);
        } // for
    } // materializeSnapshots

    /**
     * Updates completed snapshot reference and maintains sizes and latest maps.
     *
     * @param index Index in completed list.
     * @param oldSnap Previous snapshot instance.
     * @param newSnap Replaced snapshot instance.
     */
    private void updateMaterializedSnapshot(int index, ExecutionSnapshot oldSnap, ExecutionSnapshot newSnap) {
        if (oldSnap != newSnap) {
            completed.set(index, newSnap);
            sizes.put(newSnap, sizes.remove(oldSnap));
            latest.replaceAll((key, snapshot) -> snapshot == oldSnap ? newSnap : snapshot);
        } // if
    } // updateMaterializedSnapshot

    /** Releases references to all retained snapshots. */
    public void clear() {
        this.completed.clear();
        this.latest.clear();
        this.sizes.clear();
    } // clear

    /**
     * Reduces snapshot lists to only their latest entry per distinct source file and line.
     *
     * @param snapshots Breakpoint snapshot mapping.
     * @return Reduced mapping containing the latest snapshot per source file for each line.
     */
    public static Map<Integer, List<ExecutionSnapshot>> keepLatestOnly(
            Map<Integer, List<ExecutionSnapshot>> snapshots) {
        Map<Integer, List<ExecutionSnapshot>> latest = new TreeMap<>();
        for (Map.Entry<Integer, List<ExecutionSnapshot>> entry : snapshots.entrySet()) {
            List<ExecutionSnapshot> list = entry.getValue();
            Map<Optional<String>, ExecutionSnapshot> perSource = new LinkedHashMap<>();
            for (ExecutionSnapshot snap : list) {
                perSource.put(snap.sourcePath(), snap);
            } // for
            latest.put(entry.getKey(), new ArrayList<>(perSource.values()));
        } // for
        return latest;
    } // keepLatestOnly

    /**
     * Checks whether an execution snapshot is completely redundant with a previous snapshot.
     *
     * @param prev Previous execution snapshot.
     * @param current Current execution snapshot.
     * @return True if both snapshots share identical location and execution state.
     */
    public static boolean isRedundantSnapshot(ExecutionSnapshot prev, ExecutionSnapshot current) {
        if (prev == current) {
            return true;
        } // if
        if (prev == null || current == null) {
            return false;
        } // if
        return Objects.equals(prev.sourcePath(), current.sourcePath())
                && Objects.equals(prev.threads(), current.threads())
                && Objects.equals(prev.triggeringThreadId(), current.triggeringThreadId())
                && Objects.equals(prev.event(), current.event()) && Objects.equals(prev.stack(), current.stack())
                && Objects.equals(prev.statics(), current.statics()) && Objects.equals(prev.heap(), current.heap())
                && prev.stdoutSlice().contentEquals(current.stdoutSlice())
                && prev.stderrSlice().contentEquals(current.stderrSlice())
                && Objects.equals(prev.stdinConsumed(), current.stdinConsumed())
                && prev.stdinOffset() == current.stdinOffset();
    } // isRedundantSnapshot

    /**
     * Adds a state to a legacy breakpoint projection under the selected retention policy.
     *
     * @param snapshots Breakpoint projection.
     * @param line Breakpoint line.
     * @param snapshot Completed state.
     * @param accumulate Whether to retain previous hits at this source location.
     */
    public static void storeBreakpoint(Map<Integer, List<ExecutionSnapshot>> snapshots, int line,
            ExecutionSnapshot snapshot, boolean accumulate) {
        List<ExecutionSnapshot> entries = snapshots.computeIfAbsent(line, key -> new ArrayList<>());
        if (!accumulate) {
            Optional<String> src = snapshot.sourcePath();
            entries.removeIf(existing -> Objects.equals(existing.sourcePath(), src));
        } // if
        entries.add(snapshot);
    } // storeBreakpoint

    /** Counts an upper bound of UTF-8 snapshot bytes without building a JSON string. */
    private final class SnapshotCounter extends Writer {
        private long bytes;

        /** Constructs a snapshot size counter. */
        SnapshotCounter() {} // SnapshotCounter

        @Override
        public void write(char[] buffer, int offset, int length) {
            bytes = Math.addExact(bytes, length * 3L);
            enforce(Math.addExact(retainedBytes, bytes), limits.traceBytes(), "trace_limit");
        } // write

        @Override
        public void flush() {} // flush

        @Override
        public void close() {} // close
    } // SnapshotCounter

    /** Streams OutputSlice byte elements without materializing a byte array. */
    private static final class OutputSliceTypeAdapter extends TypeAdapter<OutputSlice> {
        @Override
        public void write(JsonWriter out, OutputSlice slice) throws IOException {
            if (slice == null) {
                out.nullValue();
                return;
            } // if
            out.beginArray();
            slice.forEachByte(b -> out.value(b));
            out.endArray();
        } // write

        @Override
        public OutputSlice read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            } // if
            in.beginArray();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while (in.hasNext()) {
                baos.write((byte) in.nextInt());
            } // while
            in.endArray();
            return OutputSlice.from(baos.toByteArray());
        } // read
    } // OutputSliceTypeAdapter

    /** Key for tracking latest snapshot per line and source file. */
    private record SnapshotLineKey(int line, Optional<String> sourcePath) {}
} // SnapshotRetention
