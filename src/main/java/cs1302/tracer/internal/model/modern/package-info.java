/**
 * Output records for the modern JSON trace format.
 *
 * <p>{@link cs1302.tracer.internal.model.modern.Trace} groups execution steps and source metadata.
 * Steps describe call stacks, static variables, and heap objects; explicit
 * {@link cs1302.tracer.internal.model.modern.Reference} values preserve object identity and aliasing.
 * Optional {@link cs1302.tracer.internal.model.PrimitiveBinary binary descriptors} accompany primitive
 * values and array positions; their shared contract links to normative and informative encoding references.
 * Step builders detach immediate collections while preserving nulls and iteration order; nested values remain
 * shared. Existing constructors retain caller collections. Format mapping remains owned by the serializer.
 * These records define serialized consumer data, independently of the debugger's internal snapshot representation.</p>
 */
package cs1302.tracer.internal.model.modern;
