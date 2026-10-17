package cs1302.tracer.internal.trace;

import org.apiguardian.api.API;

import cs1302.tracer.internal.SourcePreparation;
import java.util.List;
import java.util.Map;

/** Selects a tracing mode independently of output rendering. */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public final class TraceDispatch {

    private final Guest guest;

    /**
     * Creates a dispatcher with the selected guest lifecycle.
     *
     * @param guest Guest execution adapter.
     */
    private TraceDispatch(Guest guest) {
        this.guest = guest;
    } // TraceDispatch

    /**
     * Creates a dispatcher that owns a fresh guest for each capture.
     *
     * @return Dedicated guest dispatcher.
     */
    public static TraceDispatch dedicated() {
        return new TraceDispatch(new DedicatedGuest());
    } // dedicated

    /**
     * Creates a dispatcher borrowing an existing persistent guest.
     * The caller retains responsibility for closing or recycling the guest.
     *
     * @param guest Existing persistent guest.
     * @return Persistent guest dispatcher.
     */
    public static TraceDispatch persistent(PersistentGuestSession guest) {
        return new TraceDispatch(new PersistentGuest(guest));
    } // persistent

    /**
     * Requested capture selection.
     *
     * @param chronological Whether to preserve event order.
     * @param accumulate Whether to retain repeated breakpoint hits.
     * @param breakpoints Selected locations, or null for the default selection.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record Selection(boolean chronological, boolean accumulate, List<BreakpointSpec> breakpoints) {

        /**
         * Selects the ordinary tracing behavior.
         *
         * @param chronological Whether to preserve event order.
         * @param accumulate Whether to retain repeated breakpoint hits.
         * @param breakpoints Selected locations, or null for the default selection.
         * @return Requested selection.
         */
        public static Selection select(boolean chronological, boolean accumulate, List<BreakpointSpec> breakpoints) {
            return new Selection(chronological, accumulate, breakpoints);
        } // select

    } // Selection

    /** A captured result awaiting presentation. */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public sealed interface Capture permits FinalState, Breakpoints, Chronological {} // Capture

    /**
     * A final program state.
     *
     * @param snapshot Captured final state.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record FinalState(ExecutionSnapshot snapshot) implements Capture {} // FinalState

    /**
     * States grouped by selected breakpoint line.
     *
     * @param snapshots Captured states at each location.
     * @param accumulated Whether repeated hits remain in the presentation.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record Breakpoints(Map<Integer, List<ExecutionSnapshot>> snapshots, boolean accumulated)
            implements Capture {} // Breakpoints

    /**
     * States in execution order.
     *
     * @param snapshots Chronological captured states.
     */
    @API(status = API.Status.INTERNAL, since = "4.0.0")
    public record Chronological(List<ExecutionSnapshot> snapshots) implements Capture {} // Chronological

    /**
     * Captures the requested execution state.
     *
     * @param prepared Owned compiled program and source evidence.
     * @param selection Requested capture selection.
     * @param stdin Guest standard input.
     * @return Captured state for rendering.
     * @throws Exception On tracing or guest cleanup failure.
     */
    public Capture capture(SourcePreparation prepared, Selection selection, String stdin) throws Exception {
        if (selection.chronological()) {
            List<BreakpointSpec> specs = selection.breakpoints() == null
                    ? DebugTraceHelper.getValidBreakpointLines(prepared.compilation()).stream()
                            .map(BreakpointSpec::of).toList()
                    : selection.breakpoints();
            return new Chronological(this.guest.chronological(prepared, specs, stdin));
        } // if
        if (selection.breakpoints() == null) {
            return new FinalState(this.guest.selected(prepared, List.of(), stdin, true).get(-1).getLast());
        } // if
        Map<Integer, List<ExecutionSnapshot>> snapshots = this.guest.selected(prepared, selection.breakpoints(), stdin,
                selection.accumulate());
        return new Breakpoints(snapshots, selection.accumulate());
    } // capture

    /** Executes captures using one guest lifecycle. */
    private interface Guest {

        /**
         * Captures selected locations.
         *
         * @param prepared Prepared program.
         * @param specs Selected locations.
         * @param stdin Guest input.
         * @param accumulate Whether to retain repeated hits in the projection.
         * @return States grouped by line.
         * @throws Exception On tracing failure.
         */
        Map<Integer, List<ExecutionSnapshot>> selected(SourcePreparation prepared, List<BreakpointSpec> specs,
                String stdin, boolean accumulate) throws Exception;

        /**
         * Captures locations in execution order, including main exit.
         *
         * @param prepared Prepared program.
         * @param specs Selected locations.
         * @param stdin Guest input.
         * @return Chronological states.
         * @throws Exception On tracing failure.
         */
        List<ExecutionSnapshot> chronological(SourcePreparation prepared, List<BreakpointSpec> specs, String stdin)
                throws Exception;

    } // Guest

    /** Uses the dedicated guest's existing launch, event loop, and cleanup. */
    private static final class DedicatedGuest implements Guest {

        @Override
        public Map<Integer, List<ExecutionSnapshot>> selected(SourcePreparation prepared, List<BreakpointSpec> specs,
                String stdin, boolean accumulate) throws Exception {
            return accumulate
                    ? DebugTraceHelper.traceWithSpecs(prepared.compilation(), specs, prepared.units(), stdin)
                    : DebugTraceHelper.traceLatestWithSpecs(prepared.compilation(), specs, prepared.units(), stdin);
        } // selected

        @Override
        public List<ExecutionSnapshot> chronological(SourcePreparation prepared, List<BreakpointSpec> specs,
                String stdin) throws Exception {
            return DebugTraceHelper.traceChronologicalWithSpecs(prepared.compilation(), specs, prepared.units(), true,
                    stdin);
        } // chronological

    } // DedicatedGuest

    /**
     * Uses the persistent guest's existing job protocol and event loop.
     *
     * @param session Borrowed guest session.
     */
    private record PersistentGuest(PersistentGuestSession session) implements Guest {

        @Override
        public Map<Integer, List<ExecutionSnapshot>> selected(SourcePreparation prepared, List<BreakpointSpec> specs,
                String stdin, boolean accumulate) throws Exception {
            return this.session.traceWithSpecs(prepared.compilation(), specs, prepared.units(), stdin,
                    accumulate);
        } // selected

        @Override
        public List<ExecutionSnapshot> chronological(SourcePreparation prepared, List<BreakpointSpec> specs,
                String stdin) throws Exception {
            return this.session.traceChronologicalWithSpecs(prepared.compilation(), specs, prepared.units(), true,
                    stdin);
        } // chronological

    } // PersistentGuest

} // TraceDispatch
