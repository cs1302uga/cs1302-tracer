package cs1302.tracer.model.pytutor;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Represents the root OnlinePythonTutor trace output format.
 *
 * @param code The original Java source code.
 * @param stdin The input provided via standard input.
 * @param trace The list of trace steps representing memory snapshots.
 * @param userlog User log messages, if any.
 */
public record PyTutorTrace(
        @SerializedName("code") String code,
        @SerializedName("stdin") String stdin,
        @SerializedName("trace") List<TraceStep> trace,
        @SerializedName("userlog") String userlog) {
} // PyTutorTrace
