/**
 * Conversion of captured execution snapshots into supported JSON output models.
 *
 * <p>{@link cs1302.tracer.internal.serialize.PyTutorSerializer} produces Python Tutor-compatible output, and
 * {@link cs1302.tracer.internal.serialize.ModernTraceSerializer} produces modern object-graph output.
 * Both apply presentation options and source metadata to captured state. Changes to internal Java tracing APIs must
 * preserve these serialized contracts unless an output-format change is explicitly intended.</p>
 */
package cs1302.tracer.internal.serialize;
