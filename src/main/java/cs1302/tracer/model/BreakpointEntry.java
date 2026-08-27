package cs1302.tracer.model;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a line entry for list-breakpoints in JSON format.
 *
 * @param lineNumber The 1-based source line number.
 * @param validBreakpoint True if this line is a valid breakpoint location.
 * @param lineContent The source code text of this line.
 * @param file The optional relative source file path for multi-file programs.
 */
public record BreakpointEntry(
    @SerializedName("lineNumber") int lineNumber,
    @SerializedName("validBreakpoint") boolean validBreakpoint,
    @SerializedName("lineContent") String lineContent,
    @SerializedName("file") String file) {

  public BreakpointEntry(int lineNumber, boolean validBreakpoint, String lineContent) {
    this(lineNumber, validBreakpoint, lineContent, null);
  }
}
