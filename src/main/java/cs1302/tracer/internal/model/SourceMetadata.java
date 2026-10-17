package cs1302.tracer.internal.model;

import org.apiguardian.api.API;

import cs1302.tracer.internal.CompilationHelper;
import java.util.Map;

/**
 * Source text and entry point shared by both trace formats.
 *
 * @param sources Source paths mapped to the exact contents supplied to the compiler.
 * @param entryFile The selected entry point's source path.
 */
@API(status = API.Status.INTERNAL, since = "4.0.0")
public record SourceMetadata(Map<String, String> sources, String entryFile) {

    /**
     * Interprets raw input without requiring compilation or a launchable entry point.
     * Prepared tracing reuses its detached description instead of parsing again.
     * @param code Original single-file source or delimited source stream.
     * @return Submitted source metadata independent of which files appear in the trace.
     */
    public static SourceMetadata from(String code) {
        return CompilationHelper.parseSources(code).submitted().metadata();
    } // from

} // SourceMetadata
