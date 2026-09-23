package cs1302.tracer.model;

import cs1302.tracer.CompilationHelper;
import cs1302.tracer.CompilationHelper.SourceFile;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Source text and entry point shared by both trace formats.
 *
 * @param sources Source paths mapped to the exact contents supplied to the compiler.
 * @param entryFile The selected entry point's source path.
 */
public record SourceMetadata(Map<String, String> sources, String entryFile) {

    /**
     * Splits the original input using the compiler's source parser.
     *
     * @param code Original single-file source or delimited source stream.
     * @return Source metadata independent of which files appear in the trace.
     */
    public static SourceMetadata from(String code) {
        List<SourceFile> files = CompilationHelper.parseMultiFileStream(code);
        Map<String, String> sources = new LinkedHashMap<>();
        for (SourceFile file : files) {
            String path = sourcePath(file);
            if (sources.putIfAbsent(path, file.content()) != null) {
                throw new IllegalArgumentException("Duplicate debug source path: " + path);
            } // if
        } // for
        String entryFile = sourcePath(CompilationHelper.findEntryPoint(files));
        return new SourceMetadata(Collections.unmodifiableMap(sources), entryFile);
    } // from

    /**
     * Returns the package-relative path reported by Java debug locations.
     *
     * @param file Parsed source file.
     * @return Package path followed by the source filename, using forward slashes.
     */
    private static String sourcePath(SourceFile file) {
        String path = file.relativePath().replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        return file.ast().getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString().replace('.', '/') + "/")
                .orElse("") + name;
    } // sourcePath
} // SourceMetadata
