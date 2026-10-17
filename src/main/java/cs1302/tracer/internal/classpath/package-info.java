/**
 * Operation-scoped binary dependency selection, manifest resolution, and optional file capture.
 * Prepared paths retain effective search order and ownership separately from compiled job output.
 * Consumers use the prepared closure without expanding archive manifests again; closing its owner
 * closes archive readers before removing captured files, while preserving caller-owned inputs.
 * {@link cs1302.tracer.internal.classpath.PreparedClassPath} owns both dependency locations and correlated
 * runtime-version archive directory views for compilation and source analysis. It uses the shared
 * {@link cs1302.tracer.internal.FileSelection} dependency operation for glob selections; copying and deletion
 * stay within dependency preparation. {@link cs1302.tracer.internal.classpath.BinaryTypeSolver} reads
 * selected bytecode with job-local caches, without loading dependency classes into the host JVM.
 * The enclosing {@link cs1302.tracer.internal.SourcePreparation} closes compiled output before releasing
 * the prepared class path. Guest execution and lazy source analysis finish before that scope closes.
 */
package cs1302.tracer.internal.classpath;
