package cs1302.tracer.model.pytutor;

import com.google.gson.annotations.SerializedName;
import cs1302.tracer.model.SourceMetadata;
import java.util.List;
import java.util.Map;

/**
 * Represents the root OnlinePythonTutor trace output format.
 *
 * @param code The original Java source code.
 * @param sources All submitted source files keyed by relative path.
 * @param entryFile The selected entry point source path.
 * @param stdin The input provided via standard input.
 * @param trace The list of trace steps representing memory snapshots.
 * @param userlog User log messages, if any.
 */
public record PyTutorTrace(
        @SerializedName("code") String code,
        @SerializedName("stdin") String stdin,
        @SerializedName("trace") List<TraceStep> trace,
        @SerializedName("userlog") String userlog,
        @SerializedName("sources") Map<String, String> sources,
        @SerializedName("entryFile") String entryFile) {

    /**
     * Constructs a trace and derives source metadata from the original input.
     *
     * @param code Original source input.
     * @param stdin Standard input.
     * @param trace Trace steps.
     * @param userlog User log messages.
     */
    public PyTutorTrace(String code, String stdin, List<TraceStep> trace, String userlog) {
        this(code, stdin, trace, userlog, SourceMetadata.from(code));
    } // PyTutorTrace

    /**
     * Constructs a trace using source metadata parsed once.
     *
     * @param code Original source input.
     * @param stdin Standard input.
     * @param trace Trace steps.
     * @param userlog User log messages.
     * @param metadata Parsed source metadata.
     */
    private PyTutorTrace(String code, String stdin, List<TraceStep> trace, String userlog,
            SourceMetadata metadata) {
        this(code, stdin, trace, userlog, metadata.sources(), metadata.entryFile());
    } // PyTutorTrace
} // PyTutorTrace
