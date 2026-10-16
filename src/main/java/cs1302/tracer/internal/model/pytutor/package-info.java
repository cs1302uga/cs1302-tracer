/**
 * Output records for Python Tutor-compatible JSON traces.
 *
 * <p>{@link cs1302.tracer.internal.model.pytutor.PyTutorTrace} contains trace steps and source metadata.
 * {@link cs1302.tracer.internal.model.pytutor.TraceStep} and {@link
 * cs1302.tracer.internal.model.pytutor.RenderStackFrame} represent the
 * stack, globals, heap, and rendering metadata consumed by compatible visualizers.
 * Serialized field names and value conventions are part of the output compatibility contract.</p>
 */
package cs1302.tracer.internal.model.pytutor;
