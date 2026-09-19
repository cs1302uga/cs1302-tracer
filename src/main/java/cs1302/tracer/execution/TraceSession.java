package cs1302.tracer.execution;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.sun.jdi.VirtualMachine;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.trace.OutputSlice;
import cs1302.tracer.trace.StreamDrainer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns a single tracing operation and its cancellation/retention budgets.
 * The thread-local binding bridges existing extraction helpers; it is never inherited.
 */
public final class TraceSession implements AutoCloseable {

    private static final ThreadLocal<TraceSession> CURRENT = new ThreadLocal<>();
    private static final Gson GSON = new com.google.gson.GsonBuilder()
            .registerTypeHierarchyAdapter(java.util.Optional.class,
                    (com.google.gson.JsonSerializer<java.util.Optional<?>>)
                    (value, type, context) -> context.serialize(value.orElse(null)))
            .registerTypeAdapter(OutputSlice.class, new OutputSliceTypeAdapter().nullSafe())
            .create();
    private final TraceLimits limits;
    private final InspectionPolicy inspection;
    private final boolean accumulate;
    private final Thread owner = Thread.currentThread();
    private final AtomicReference<String> reason = new AtomicReference<>();
    private final List<ExecutionSnapshot> completed = new ArrayList<>();
    private final Map<SnapshotLineKey, ExecutionSnapshot> latest = new LinkedHashMap<>();
    private final Map<ExecutionSnapshot, Long> sizes = new java.util.IdentityHashMap<>();
    private final Set<Long> objects = new HashSet<>();
    private final List<StreamDrainer> drainers = new ArrayList<>();
    private volatile Process process;
    private volatile boolean finished;
    private boolean destroyOnClose = true;
    private String explicitStdout;
    private String explicitStderr;
    private Long explicitStdoutBytes;
    private Long explicitStderrBytes;
    private long started;
    private Thread watchdog;
    private long captured;
    private long elements;
    private long buildingBytes;
    private long retainedBytes;
    private boolean extracting;
    private boolean guestFailed;
    private boolean droppedSnapshot;
    private String phase = "source";
    private final List<String> diagnostics = new ArrayList<>();
    private final boolean evalEnumHash;
    private static final ThreadLocal<Boolean> UNBOUNDED_EVAL_ENUM_HASH = new ThreadLocal<>();

    /**
     * Binds a session to the calling thread until closed.
     * @param limits Effective budgets.
     * @param inspection Guest inspection policy.
     * @param accumulate Whether every snapshot is retained.
     */
    public TraceSession(TraceLimits limits, InspectionPolicy inspection, boolean accumulate) {
        this(limits, inspection, accumulate,
                UNBOUNDED_EVAL_ENUM_HASH.get() == null || UNBOUNDED_EVAL_ENUM_HASH.get());
    } // TraceSession

    /**
     * Binds a session to the calling thread until closed with enum hash configuration.
     * @param limits Effective budgets.
     * @param inspection Guest inspection policy.
     * @param accumulate Whether every snapshot is retained.
     * @param evalEnumHash Whether lazy enum hash codes should be evaluated.
     */
    public TraceSession(TraceLimits limits, InspectionPolicy inspection, boolean accumulate,
            boolean evalEnumHash) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A trace session is already active on this thread");
        } // if
        this.limits = limits;
        this.inspection = inspection;
        if (inspection == InspectionPolicy.FIELDS) {
            diagnostics.add("FIELDS inspection uses raw fields for collections and wrappers; "
                    + "guest methods and stream flush are not invoked.");
        } // if
        this.accumulate = accumulate;
        this.evalEnumHash = evalEnumHash;
        CURRENT.set(this);
    } // TraceSession

    /**
     * Returns the calling thread's session, or null for legacy API calls.
     * @return Current session.
     */
    public static TraceSession current() {
        return CURRENT.get();
    } // current

    /**
     * Temporarily sets enum hash evaluation setting when running outside a session.
     * @param evalEnumHash Whether lazy enum hash codes should be evaluated.
     * @return AutoCloseable scope restoring previous setting.
     */
    public static AutoCloseable withEvalEnumHash(boolean evalEnumHash) {
        Boolean previous = UNBOUNDED_EVAL_ENUM_HASH.get();
        UNBOUNDED_EVAL_ENUM_HASH.set(evalEnumHash);
        return () -> {
            if (previous == null) {
                UNBOUNDED_EVAL_ENUM_HASH.remove();
            } else {
                UNBOUNDED_EVAL_ENUM_HASH.set(previous);
            } // if
        };
    } // withEvalEnumHash

    /**
     * Returns whether helpers may invoke guest methods.
     * @return True for trusted inspection.
     */
    public static boolean mayInvoke() {
        return current() == null || current().inspection == InspectionPolicy.TRUSTED;
    } // mayInvoke

    /**
     * Returns whether helpers should evaluate lazy enum hash codes.
     * @return True if enabled and methods may be invoked.
     */
    public static boolean shouldEvalEnumHash() {
        if (!mayInvoke()) {
            return false;
        } // if
        TraceSession session = current();
        if (session != null) {
            return session.evalEnumHash;
        } // if
        Boolean override = UNBOUNDED_EVAL_ENUM_HASH.get();
        return override == null || override;
    } // shouldEvalEnumHash

    /** Checks cancellation and the monotonic tracing deadline. */
    public void check() {
        if (owner.isInterrupted()) {
            stop("cancelled");
        } // if
        if (started != 0 && limits.timeoutMillis() != 0
                && System.nanoTime() - started >= limits.timeoutMillis() * 1_000_000) {
            stop("timeout");
        } // if
        if (reason.get() != null) {
            throw new Stopped(reason.get());
        } // if
    } // check

    /**
     * Starts the independent watchdog before guest launch.
     * @param value Current execution phase.
     */
    public void phase(String value) {
        phase = value;
        if (value.equals("trace") && started == 0) {
            started = System.nanoTime();
            watchdog = Thread.ofPlatform().daemon().name("tracer-watchdog").start(() -> {
                while (!finished) {
                    try {
                        check();
                        Thread.sleep(10);
                    } catch (Stopped stopped) {
                        return;
                    } catch (InterruptedException interrupted) {
                        return;
                    } // try
                } // while
            });
        } // if
    } // phase

    /**
     * Attaches a launched guest, handling cancellation during launch.
     * @param vm Guest debugger connection.
     */
    public void attach(VirtualMachine vm) {
        attach(vm.process(), true);
    } // attach

    /**
     * Attaches a launched guest process with control over destruction on normal close.
     * @param guest Launched process.
     * @param destroyOnClose Whether process should be forcibly destroyed on normal close.
     */
    public void attach(Process guest, boolean destroyOnClose) {
        this.process = guest;
        this.destroyOnClose = destroyOnClose;
        if (reason.get() != null) {
            stop(reason.get());
        } // if
        check();
    } // attach

    /**
     * Registers an output drainer for session cleanup and counters.
     * @param drainer Guest stream drainer.
     */
    public void register(StreamDrainer drainer) {
        drainers.add(drainer);
    } // register

    /**
     * Returns the per-stream output cap.
     * @return Byte limit, or zero for unlimited.
     */
    public long outputLimit() {
        return limits.outputBytes();
    } // outputLimit

    /** Requests cancellation from any thread. */
    public void cancel() {
        stop("cancelled");
    } // cancel

    /**
     * Returns whether this session has been stopped or cancelled.
     * @return True if stopped.
     */
    public boolean isStopped() {
        return reason.get() != null;
    } // isStopped

    /**
     * Returns the machine-readable stop reason if stopped, or null.
     * @return Machine-readable stop reason.
     */
    public String stopReason() {
        return reason.get();
    } // stopReason

    /**
     * Stops this job; the first observed reason wins.
     * @param value Machine-readable stop reason.
     */
    public void stop(String value) {
        reason.compareAndSet(null, value);
        Process guest = process;
        if (guest != null && guest.isAlive()) {
            guest.destroyForcibly();
        } // if
    } // stop

    /** Begins an atomic snapshot extraction. */
    public void beginSnapshot() {
        check();
        if (limits.snapshots() != 0 && captured >= limits.snapshots()) {
            stop("snapshot_limit");
            throw new Stopped(reason.get());
        } // if
        extracting = true;
        elements = 0;
        buildingBytes = 256;
        objects.clear();
    } // beginSnapshot

    /**
     * Accounts element storage before fetching or allocating it.
     * @param count Elements to inspect.
     */
    public static void elements(long count) {
        TraceSession session = current();
        if (session != null) {
            session.check();
            session.elements = Math.addExact(session.elements, count);
            session.enforce(session.elements, session.limits.elements(), "element_limit");
            session.allocate(Math.multiplyExact(count, 64));
        } // if
    } // elements

    /**
     * Accounts estimated extraction storage before allocation.
     * @param bytes Conservative storage units.
     */
    public void allocate(long bytes) {
        buildingBytes = Math.addExact(buildingBytes, bytes);
        enforce(Math.addExact(retainedBytes, buildingBytes), limits.traceBytes(), "trace_limit");
    } // allocate

    /**
     * Accounts a reachable reference before queueing it.
     * @param id Guest object identity.
     * @return True if this is the first encounter in this snapshot.
     */
    public boolean encounter(long id) {
        check();
        if (objects.contains(id)) {
            return false;
        } // if
        enforce(objects.size() + 1L, limits.heapObjects(), "heap_limit");
        allocate(128);
        objects.add(id);
        return true;
    } // encounter

    /**
     * Applies a resource ceiling, permitting values exactly at the boundary.
     * @param used Accounted usage.
     * @param limit Zero or finite ceiling.
     * @param failure Stop reason.
     */
    public void enforce(long used, long limit, String failure) {
        check();
        if (limit != 0 && used > limit) {
            stop(failure);
            throw new Stopped(reason.get());
        } // if
    } // enforce

    /**
     * Commits a completed snapshot, retaining only the latest when requested.
     * @param snapshot Fully extracted state.
     */
    public void commit(ExecutionSnapshot snapshot) {
        check();
        SnapshotCounter counter = new SnapshotCounter();
        GSON.toJson(snapshot, counter);
        long size = Math.max(buildingBytes, counter.bytes);
        enforce(Math.addExact(retainedBytes, size), limits.traceBytes(), "trace_limit");
        if (!accumulate) {
            int line = snapshot.stack().isEmpty() ? -1
                    : (int) snapshot.stack().getLast().methodLine();
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
        extracting = false;
    } // commit

    /**
     * Records an observed uncaught exception without losing later output.
     * @param description Exception type and source location.
     */
    public void guestException(String description) {
        guestFailed = true;
        diagnostics.add(description);
    } // guestException

    /**
     * Sets isolated guest process output explicitly.
     * @param stdout Captured standard output string.
     * @param stderr Captured standard error string.
     * @param stdoutBytes Total standard output bytes.
     * @param stderrBytes Total standard error bytes.
     */
    public void setCapturedOutput(
            String stdout, String stderr, long stdoutBytes, long stderrBytes) {
        this.explicitStdout = stdout;
        this.explicitStderr = stderr;
        this.explicitStdoutBytes = stdoutBytes;
        this.explicitStderrBytes = stderrBytes;
    } // setCapturedOutput

    /**
     * Returns whether a guest was launched, even if it produced no snapshots.
     * @return Whether trace capture was available.
     */
    public boolean traceAvailable() {
        return process != null;
    } // traceAvailable

    /**
     * Reports whether all captured states are retained.
     * @return True for chronological or accumulating jobs.
     */
    public boolean accumulates() {
        return accumulate;
    } // accumulates

    /**
     * Returns a copy of committed snapshots for serialization.
     * @return Completed snapshots only.
     */
    public List<ExecutionSnapshot> snapshots() {
        return List.copyOf(completed);
    } // snapshots

    /**
     * Builds a result after tracing or a recoverable failure.
     * @param format Payload format name.
     * @param payload Trace or null when unavailable.
     * @param failure Optional caught failure.
     * @return Versioned result.
     */
    public TraceResult result(String format, Object payload, Throwable failure) {
        finished = true;
        droppedSnapshot = extracting;
        String stopped = reason.get();
        if (failure != null && stopped == null) {
            stopped = phase.equals("compile") || phase.equals("source")
                    ? "compile_error" : "tracer_error";
            diagnostics.add(failure.toString());
        } // if
        if (stopped == null && guestFailed) {
            stopped = "guest_exception";
        } // if
        Map<String, Long> counts = new LinkedHashMap<>();
        if (process != null && !process.isAlive()) {
            int exitCode = process.exitValue();
            counts.put("guestExitCode", (long) exitCode);
            if (stopped == null && exitCode != 0) {
                stopped = "guest_exit";
                diagnostics.add("Guest process exited with code " + exitCode);
            } // if
        } // if
        counts.put("snapshotsCaptured", captured);
        counts.put("snapshotsRetained", (long) completed.size());
        counts.put("retainedBytes", retainedBytes);
        counts.put("droppedSnapshots", droppedSnapshot ? 1L : 0L);
        counts.put("elapsedMillis", started == 0 ? 0 : (System.nanoTime() - started) / 1_000_000);
        if (explicitStderrBytes != null) {
            counts.put("stderrBytes", explicitStderrBytes);
        } else {
            if (!drainers.isEmpty()) {
                counts.put("stderrBytes", (long) drainers.get(0).size());
            } // if
        } // if
        if (explicitStdoutBytes != null) {
            counts.put("stdoutBytes", explicitStdoutBytes);
        } else {
            if (drainers.size() > 1) {
                counts.put("stdoutBytes", (long) drainers.get(1).size());
            } // if
        } // if
        String status = stopped == null ? "completed"
                : stopped.endsWith("error") || stopped.equals("guest_exception")
                        || stopped.equals("guest_exit")
                        ? "failed" : "stopped";
        return new TraceResult(1, format, status, stopped, phase, stopped == null,
                payload, limits, counts, List.copyOf(diagnostics), output(1), output(0));
    } // result

    /**
     * Returns bounded guest output even when no snapshot was completed.
     * @param index Registered drainer index (stderr first).
     * @return UTF-8 output with replacement for incomplete byte sequences.
     */
    private String output(int index) {
        if (index == 1 && explicitStdout != null) {
            return explicitStdout;
        } // if
        if (index == 0 && explicitStderr != null) {
            return explicitStderr;
        } // if
        return index >= drainers.size() ? "" : new String(drainers.get(index).getBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
    } // output

    /** Refreshes final output on the last complete snapshot after successful execution. */
    public void finishOutput() {
        if (completed.isEmpty() || drainers.size() != 2) {
            return;
        } // if
        finishOutput(drainers.get(1).snapshotOutput(), drainers.get(0).snapshotOutput());
    } // finishOutput

    /**
     * Refreshes final output on the last complete snapshot using explicit slices.
     * @param stdout Captured standard output slice.
     * @param stderr Captured standard error slice.
     */
    public void finishOutput(OutputSlice stdout, OutputSlice stderr) {
        if (reason.get() == null) {
            check();
        } // if
        if (completed.isEmpty()) {
            return;
        } // if
        OutputSlice safeOut = stdout != null ? stdout.materialize() : null;
        OutputSlice safeErr = stderr != null ? stderr.materialize() : null;
        ExecutionSnapshot last = completed.getLast();
        int outLen = safeOut != null ? safeOut.length() : last.stdoutLength();
        int errLen = safeErr != null ? safeErr.length() : last.stderrLength();
        long extra = Math.max(0, outLen - last.stdoutLength())
                + Math.max(0, errLen - last.stderrLength());
        if (extra == 0) {
            materializeSnapshots();
            return;
        } // if
        // Raw byte arrays cost at most five ASCII JSON characters per byte in accounting.
        long newRetained = Math.addExact(retainedBytes, extra * 15);
        if (limits.traceBytes() != 0 && newRetained > limits.traceBytes()) {
            stop("trace_limit");
            materializeSnapshots();
            return;
        } // if
        ExecutionSnapshot updated = new ExecutionSnapshot(
                last.stack(), last.statics(), last.heap(),
                safeOut != null ? safeOut : last.stdoutSlice(),
                safeErr != null ? safeErr : last.stderrSlice(),
                last.sourcePath(), last.stdinConsumed(), last.stdinOffset());
        completed.set(completed.size() - 1, updated);
        latest.replaceAll((key, snapshot) -> snapshot == last ? updated : snapshot);
        sizes.put(updated, sizes.remove(last) + extra * 15);
        retainedBytes = newRetained;
        materializeSnapshots();
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
        for (int i = 0; i < completed.size(); i++) {
            ExecutionSnapshot oldSnap = completed.get(i);
            ExecutionSnapshot newSnap = new ExecutionSnapshot(
                    oldSnap.stack(), oldSnap.statics(), oldSnap.heap(),
                    OutputSlice.from(sharedStdout, 0, oldSnap.stdoutLength()),
                    OutputSlice.from(sharedStderr, 0, oldSnap.stderrLength()),
                    oldSnap.sourcePath(), oldSnap.stdinConsumed(), oldSnap.stdinOffset());
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
    private void updateMaterializedSnapshot(
            int index, ExecutionSnapshot oldSnap, ExecutionSnapshot newSnap) {
        if (oldSnap != newSnap) {
            completed.set(index, newSnap);
            sizes.put(newSnap, sizes.remove(oldSnap));
            latest.replaceAll((key, snapshot) -> snapshot == oldSnap ? newSnap : snapshot);
        } // if
    } // updateMaterializedSnapshot

    @Override
    public void close() {
        finished = true;
        if (watchdog != null) {
            watchdog.interrupt();
        } // if
        Process guest = process;
        if (guest != null && guest.isAlive() && (destroyOnClose || reason.get() != null)) {
            guest.destroyForcibly();
            try {
                guest.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                owner.interrupt();
            } // try
        } // if
        drainers.forEach(StreamDrainer::close);
        drainers.forEach(StreamDrainer::detachSession);
        drainers.clear();
        completed.clear();
        latest.clear();
        sizes.clear();
        process = null;
        CURRENT.remove();
    } // close

    /** Controlled non-success exit from extraction or event handling. */
    public static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Constructs a stop signal without a costly stack trace.
         * @param reason Machine-readable reason.
         */
        Stopped(String reason) {
            super(reason, null, false, false);
        } // Stopped
    } // Stopped

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
} // TraceSession
