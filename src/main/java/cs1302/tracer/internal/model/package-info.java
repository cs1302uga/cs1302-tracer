/**
 * Shared source metadata, breakpoint descriptions, and primitive binary descriptors.
 *
 * <p>These types describe submitted source files and value metadata shared by serializers.
 * {@link cs1302.tracer.internal.model.SourceMetadata} is the output projection of detached
 * {@link cs1302.tracer.internal.SubmittedSources}; submitted-source identity interpretation stays with that owner.
 * {@link cs1302.tracer.internal.model.PrimitiveBinary} links to the encoding contract and its
 * normative and informative references.
 * Format-specific output records live in {@link cs1302.tracer.internal.model.modern} and
 * {@link cs1302.tracer.internal.model.pytutor}; captured execution state lives in {@link
 * cs1302.tracer.internal.trace}.</p>
 */
package cs1302.tracer.internal.model;
