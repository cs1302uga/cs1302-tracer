/**
 * Stable command-line launcher for the tracer.
 *
 * <p>{@link cs1302.tracer.App} dispatches CLI arguments and runs the CLI's JDK environment checks, including
 * for help and version requests. Some command failures terminate the calling JVM. Applications that embed
 * tracing use {@link cs1302.tracer.api.Tracer} for one request or {@link cs1302.tracer.api.BatchTracer} for
 * NDJSON streams, with detached results and explicitly owned worker lifetimes.</p>
 *
 * <p>This launcher is part of the supported Java interface. Classes under {@code cs1302.tracer.internal}
 * implement parsing, debugging, and serialization and carry no supported compatibility promise.</p>
 *
 * @see cs1302.tracer.App
 * @see cs1302.tracer.api
 */
package cs1302.tracer;
