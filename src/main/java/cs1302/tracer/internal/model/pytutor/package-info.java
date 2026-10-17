/**
 * Output records for Python Tutor-compatible JSON traces.
 *
 * <p>{@link cs1302.tracer.internal.model.pytutor.PyTutorTrace} contains trace steps and source metadata.
 * {@link cs1302.tracer.internal.model.pytutor.TraceStep} and {@link
 * cs1302.tracer.internal.model.pytutor.RenderStackFrame} represent the
 * stack, globals, heap, and rendering metadata consumed by compatible visualizers.
 * Opt-in {@link cs1302.tracer.internal.model.PrimitiveBinary binary descriptors} live in rendering attributes,
 * preserving tuple payloads; their shared contract links to normative and informative encoding references.
 * Step builders detach immediate collections, preserving null payloads and iteration order; nested values remain
 * shared. Existing constructors retain caller collections. The serializer owns correlated globals and attributes.
 * Serialized field names and value conventions are part of the output compatibility contract.</p>
 */
package cs1302.tracer.internal.model.pytutor;
