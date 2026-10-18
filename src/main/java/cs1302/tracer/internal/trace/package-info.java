/**
 * Debugger execution, source analysis, reachable-heap inspection, and job-scoped observation.
 *
 * <p>{@link cs1302.tracer.internal.trace.DebugTraceHelper} coordinates dedicated JDI event handling.
 * The package-private snapshot capture module assembles complete
 * {@link cs1302.tracer.internal.trace.ExecutionSnapshot} values for both guest paths, hiding type prepass ordering
 * and shared heap traversal. Sessions retain budget and admission ownership.
 * Source analysis and prepared class-file descriptors and signatures supply evidence for generic type label
 * and lambda reconstruction. Binary declaration identities stay separate from canonical display names.
 * {@link cs1302.tracer.internal.trace.ReifiedTypeMetadata} owns type evidence for one snapshot,
 * while {@link cs1302.tracer.internal.trace.JobOutput} owns each job's captured output and finalization.
 * Hidden binary lambda objects have separate functional-interface views. Heap inspection finalizes their
 * interface-based {@code λ} headings after traversal without changing semantic generic bindings or source
 * implementation bodies.</p>
 *
 * <p>Package-private {@link cs1302.tracer.internal.trace.HeapInspection} owns one snapshot's reachable reference
 * queue, admitted identities, heap values, and JDI inspection. It borrows the snapshot's type evidence;
 * capture retains root ordering, source observations, input/output sampling, and completed-snapshot admission.
 * {@link cs1302.tracer.internal.trace.TraceValue} contains host-owned captured values, independently of JDI
 * conversion. Inspection preserves trusted invocation and field-only policies.</p>
 *
 * <p>Dedicated and persistent guest execution share capture and observation policies. Retained snapshots must remain
 * valid after guest completion or reuse, without depending on mutable buffers from later jobs.</p>
 *
 * <p>Guest launchers pass the prepared binary dependency order from
 * {@link cs1302.tracer.internal.CompilationHelper.CompilationResult} through a job-owned descriptor.
 * Dedicated and persistent guests use the explicit loaders in {@code internal.guest};
 * archive views and optional dependency copies remain owned by
 * {@link cs1302.tracer.internal.classpath.PreparedClassPath}, held by
 * {@link cs1302.tracer.internal.SourcePreparation} until guest execution and source analysis finish.
 * Generic superclass and interface bindings compose across hierarchy levels; instance fields use the metadata of
 * their declaring owner, including inherited and hidden fields. Binary generic declarations do not require library
 * source or local-variable debug tables. Unresolved arguments retain conservative erased or wildcard labels.
 * Precompiled dependency classes supply runtime behavior and type evidence, but do not add source-line
 * breakpoints to submitted or discovered Java source.</p>
 *
 * <p>Primitive trace values are immutable. Boolean conversion reuses
 * {@link cs1302.tracer.internal.trace.TraceValue.Primitive.Boolean#TRUE} and
 * {@link cs1302.tracer.internal.trace.TraceValue.Primitive.Boolean#FALSE}; these constants contain no job state.</p>
 */
package cs1302.tracer.internal.trace;
