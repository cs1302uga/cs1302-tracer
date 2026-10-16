/**
 * Output records for the modern JSON trace format.
 *
 * <p>{@link cs1302.tracer.internal.model.modern.Trace} groups execution steps and source metadata.
 * Steps describe call stacks, static variables, and heap objects; explicit
 * {@link cs1302.tracer.internal.model.modern.Reference} values preserve object identity and aliasing.
 * Optional binary descriptors accompany primitive values and array positions.
 * These records define serialized consumer data, independently of the debugger's internal snapshot representation.</p>
 */
package cs1302.tracer.internal.model.modern;
