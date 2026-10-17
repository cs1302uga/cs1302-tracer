/**
 * Trace rendering from completed execution snapshots through final JSON.
 *
 * <p>{@link cs1302.tracer.internal.serialize.TraceRendering} owns root selection, presentation, envelope freezing,
 * and batch response encoding. Execution retains status and diagnostics; batch processing retains correlation,
 * ordering, and writing. Its format adapters preserve their distinct wire contracts.
 * {@link cs1302.tracer.internal.execution.TraceCompletion} coordinates snapshot eligibility and rendering-failure
 * rules for envelope CLI and Java/NDJSON workers. Ordinary CLI renders its capture directly.
 * {@link cs1302.tracer.internal.serialize.PyTutorSerializer} produces Python Tutor-compatible output, and
 * {@link cs1302.tracer.internal.serialize.ModernTraceSerializer} produces modern object-graph output.
 * Both apply presentation options and source metadata to captured state.
 * Primitive binary descriptors are derived at rendering time and omitted unless requested.
 * Their <a href="https://github.com/cs1302uga/cs1302-tracer/blob/main/docs/reference/output.md#encoding-definitions-and-references">
 * encoding reference</a> distinguishes normative specifications from informative explanations.
 * Changes to internal Java tracing APIs must
 * preserve these serialized contracts unless an output-format change is explicitly intended.</p>
 */
package cs1302.tracer.internal.serialize;
