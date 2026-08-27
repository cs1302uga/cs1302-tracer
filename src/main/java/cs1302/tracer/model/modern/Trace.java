package cs1302.tracer.model.modern;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Root output model for the modern trace format.
 *
 * @param code The original Java source code (or delimited multi-file stream).
 * @param format The trace format identifier ("modern").
 * @param steps The sequential list of trace steps.
 * @param breakpoints Optional mapping of breakpoint line numbers to steps when tracing specific breakpoints.
 */
public record Trace(
    @SerializedName("code") String code,
    @SerializedName("format") String format,
    @SerializedName("steps") List<Step> steps,
    @SerializedName("breakpoints") Map<Integer, Object> breakpoints) {

  public Trace(String code, List<Step> steps) {
    this(code, "modern", steps, null);
  }

  public Trace(String code, Map<Integer, Object> breakpoints) {
    this(code, "modern", null, breakpoints);
  }
}
