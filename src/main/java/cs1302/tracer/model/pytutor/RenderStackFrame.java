package cs1302.tracer.model.pytutor;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Represents a stack frame to be rendered by OnlinePythonTutor.
 *
 * @param funcName Formatted function name with line (e.g. "main:4").
 * @param encodedLocals Local variables mapped to their serialized values.
 * @param localsAttrs Type attributes for local variables.
 * @param orderedVarnames Ordered list of variable names in the frame.
 * @param parentFrameIdList List of parent frame IDs (empty for standard frames).
 * @param isHighlighted True if this is the currently executing frame.
 * @param isZombie True if the frame is a zombie/retained frame.
 * @param isParent True if this frame acts as a parent for inner frames.
 * @param uniqueHash Unique string identifier for the frame.
 * @param frameId Numeric identifier for the frame.
 * @param file The optional relative source file path for multi-file programs.
 */
public record RenderStackFrame(
        @SerializedName("func_name") String funcName,
        @SerializedName("encoded_locals") Map<String, Object> encodedLocals,
        @SerializedName("locals_attrs") Map<String, Object> localsAttrs,
        @SerializedName("ordered_varnames") List<String> orderedVarnames,
        @SerializedName("parent_frame_id_list") List<Integer> parentFrameIdList,
        @SerializedName("is_highlighted") boolean isHighlighted,
        @SerializedName("is_zombie") boolean isZombie,
        @SerializedName("is_parent") boolean isParent,
        @SerializedName("unique_hash") String uniqueHash,
        @SerializedName("frame_id") int frameId,
        @SerializedName("file") String file) {

    /**
     * Constructs a single-file render stack frame without a file path.
     *
     * @param funcName The function name.
     * @param encodedLocals The local variables.
     * @param localsAttrs The local variable attributes.
     * @param orderedVarnames The ordered variable names.
     * @param parentFrameIdList The parent frame ID list.
     * @param isHighlighted True if highlighted.
     * @param isZombie True if zombie.
     * @param isParent True if parent.
     * @param uniqueHash The unique frame hash.
     * @param frameId The frame ID.
     */
    public RenderStackFrame(
            String funcName,
            Map<String, Object> encodedLocals,
            Map<String, Object> localsAttrs,
            List<String> orderedVarnames,
            List<Integer> parentFrameIdList,
            boolean isHighlighted,
            boolean isZombie,
            boolean isParent,
            String uniqueHash,
            int frameId) {
        this(
                funcName,
                encodedLocals,
                localsAttrs,
                orderedVarnames,
                parentFrameIdList,
                isHighlighted,
                isZombie,
                isParent,
                uniqueHash,
                frameId,
                null);
    } // RenderStackFrame
} // RenderStackFrame
