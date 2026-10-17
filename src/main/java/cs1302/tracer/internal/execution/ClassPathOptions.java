package cs1302.tracer.internal.execution;

import cs1302.tracer.api.TraceClassPath;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apiguardian.api.API;
import picocli.CommandLine.Option;

/** Command-only syntax adaptation; filesystem selection remains with job preparation. */
@API(status = API.Status.INTERNAL, since = "4.1.0")
public final class ClassPathOptions {

    @Option(names = { "--class-path", "-classpath", "-cp" }, paramLabel = "<paths>",
            description = "Binary dependency path or platform-separated path list (: on Unix, ; on Windows). "
                    + "Repeat to append in order. Empty components mean the working directory; default: no entries. "
                    + "Quote patterns: lib/* selects immediate JARs; lib/**/*.jar also searches descendants. "
                    + "Matches are sorted; missing entries and patterns without matches fail preparation.")
    private List<String> pathLists = new ArrayList<>();

    @Option(names = "--copy-class-path", description = "Copy selected dependencies and their local manifest "
            + "dependencies before compilation (default: false). Otherwise keep borrowed inputs unchanged "
            + "until the job completes. Applies to --class-path entries.")
    private boolean copy;

    @Option(names = "--follow-class-path-links", description = "Follow symbolic links during class-path glob "
            + "discovery and directory copying, including outside targets (default: false). Broken links and "
            + "cycles fail. By default glob discovery skips links and copying rejects encountered links.")
    private boolean followLinks;

    /** Creates omitted command settings. */
    public ClassPathOptions() {} // ClassPathOptions

    /**
     * Parses native lists and pattern roots without consulting the filesystem.
     *
     * @return Immutable selections for preparation of one job.
     */
    public TraceClassPath resolve() {
        TraceClassPath.Builder builder = TraceClassPath.builder().copy(this.copy).followLinks(this.followLinks);
        for (String pathList : this.pathLists) {
            for (String selection : pathList.split(Pattern.quote(File.pathSeparator), -1)) {
                addSelection(builder, selection, File.separatorChar);
            } // for
        } // for
        return builder.build();
    } // resolve

    /**
     * Separates a native literal prefix from a portable glob at its first metacharacter.
     * Native roots, parent components, and drive/UNC prefixes stay in the filesystem path.
     *
     * @param builder Destination preserving encounter order.
     * @param selection One unsplit native class-path entry.
     * @param nativeSeparator Native filesystem separator.
     */
    static void addSelection(TraceClassPath.Builder builder, String selection, char nativeSeparator) {
        int patternStart = -1;
        for (int index = 0; index < selection.length(); index++) {
            if ("*?[{".indexOf(selection.charAt(index)) >= 0) {
                patternStart = index;
                break;
            } // if
        } // for
        if (patternStart < 0) {
            builder.addPath(Path.of(selection));
            return;
        } // if
        int separator = selection.lastIndexOf('/', patternStart);
        if (nativeSeparator == '\\') {
            separator = Math.max(separator, selection.lastIndexOf('\\', patternStart));
        } // if
        int prefixLength = separator + 1;
        if (nativeSeparator == '\\' && prefixLength == 0 && patternStart == 2 && selection.charAt(1) == ':') {
            prefixLength = 2;
        } // if
        Path root = Path.of(selection.substring(0, prefixLength));
        String glob = selection.substring(prefixLength);
        if (nativeSeparator == '\\') {
            glob = glob.replace('\\', '/');
        } // if
        builder.addGlob(root, glob.equals("*") ? "*.{jar,JAR}" : glob);
    } // addSelection

} // ClassPathOptions
