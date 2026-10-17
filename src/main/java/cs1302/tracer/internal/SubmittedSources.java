package cs1302.tracer.internal;

import cs1302.tracer.internal.model.SourceMetadata;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apiguardian.api.API;

/**
 * Detached interpretation of submitted source text, independent of compilation and guest resources.
 * Logical submission paths and package-relative debug identities remain distinct. Duplicate debug
 * identities are rejected only when metadata is requested, preserving rendering-time validation.
 */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class SubmittedSources {

    private final String code;
    private final List<File> files;
    private final int entryIndex;

    /**
     * Captures immutable text and identities from one compiler-owned parse.
     * @param code Original complete input.
     * @param sources Parsed submitted files, without neighboring evidence.
     */
    SubmittedSources(String code, List<CompilationHelper.SourceFile> sources) {
        this.code = code;
        this.files = sources.stream().map(source -> {
            String path = source.relativePath().replace('\\', '/');
            String name = path.substring(path.lastIndexOf('/') + 1);
            String debug = source.ast().getPackageDeclaration()
                    .map(pkg -> pkg.getNameAsString().replace('.', '/') + "/").orElse("") + name;
            return new File(source.relativePath(), source.content(), debug);
        }).toList();
        CompilationHelper.SourceFile entry = CompilationHelper.findEntryPoint(sources);
        int selected = 0;
        while (sources.get(selected) != entry) {
            selected++;
        } // while
        this.entryIndex = selected;
    } // SubmittedSources

    /**
     * Returns the original complete single-file or delimited input without reconstruction.
     * @return Original source text.
     */
    public String code() {
        return this.code;
    } // code

    /**
     * Returns captured submitted text and identities in submission order.
     * @return Immutable ordered files, without AST or filesystem ownership.
     */
    public List<File> files() {
        return this.files;
    } // files

    /**
     * Projects debug identities into the source map used by both output formats.
     * Validation occurs on every invocation; a failed retry receives a fresh exception.
     * @return Ordered exact source contents and selected entry identity.
     * @throws IllegalArgumentException If two submitted files have the same debug identity.
     */
    public SourceMetadata metadata() {
        Map<String, String> sources = new LinkedHashMap<>();
        for (File file : this.files) {
            if (sources.putIfAbsent(file.debugPath(), file.content()) != null) {
                throw new IllegalArgumentException("Duplicate debug source path: " + file.debugPath());
            } // if
        } // for
        return new SourceMetadata(Collections.unmodifiableMap(sources), this.files.get(this.entryIndex).debugPath());
    } // metadata

    /**
     * One immutable submitted file, retaining native and debug identities without its parsed AST.
     * @param relativePath Existing compiler-relative path using the native separator.
     * @param content Exact source contents supplied to compilation.
     * @param debugPath Package path plus source basename, using forward slashes.
     */
    @API(status = API.Status.INTERNAL, since = "4.1.0")
    public record File(String relativePath, String content, String debugPath) {

        /**
         * Returns the portable logical path displayed by source listing.
         * @return Relative submission path with forward slashes.
         */
        public String logicalPath() {
            return this.relativePath.replace(java.io.File.separatorChar, '/');
        } // logicalPath

        /**
         * Joins CLI listing lines with the existing exact/native and basename-suffix fallbacks.
         * An empty exact match still falls through; the first suffix match retains precedence.
         * This caller-specific rule is independent of breakpoint specification matching.
         * @param availableLines Executable line tables in their existing iteration order.
         * @return Existing matching line set, or an empty set when none matches.
         */
        public Set<Integer> listingLines(Map<String, Set<Integer>> availableLines) {
            Set<Integer> valid = availableLines.getOrDefault(this.logicalPath(),
                    availableLines.getOrDefault(this.relativePath, Collections.emptySet()));
            if (valid.isEmpty()) {
                String simpleName = Path.of(this.relativePath).getFileName().toString();
                for (Map.Entry<String, Set<Integer>> entry : availableLines.entrySet()) {
                    if (entry.getKey().endsWith(simpleName)) {
                        return entry.getValue();
                    } // if
                } // for
            } // if
            return valid;
        } // listingLines

    } // File

} // SubmittedSources
