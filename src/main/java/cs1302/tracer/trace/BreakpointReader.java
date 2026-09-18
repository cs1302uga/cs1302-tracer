package cs1302.tracer.trace;

import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.execution.TraceSession;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Reads executable source lines from debug attributes without loading guest classes. */
public final class BreakpointReader {

    /** Prevents utility construction. */
    private BreakpointReader() {} // BreakpointReader

    /**
     * Reads every compiled class, including unused and nested declarations.
     * @param compiled Owned compilation output.
     * @return Source paths mapped to executable lines.
     * @throws IOException On class file read failure.
     */
    public static Map<String, Set<Integer>> read(CompilationResult compiled) throws IOException {
        Map<String, Set<Integer>> lines = new TreeMap<>();
        for (String name : new TreeSet<>(compiled.compiledClassNames())) {
            if (TraceSession.current() != null) {
                TraceSession.current().check();
            } // if
            var path = compiled.classPath().resolve(name.replace('.', '/') + ".class");
            try (var input = Files.newInputStream(path)) {
                ClassReader reader = new ClassReader(input);
                reader.accept(new LineVisitor(lines), ClassReader.SKIP_FRAMES);
            } // try
        } // for
        return lines;
    } // read

    /**
     * Resolves breakpoint specifications against the executable lines found in compiled classes.
     *
     * @param compiled Owned compilation output.
     * @param specs Breakpoint specifications.
     * @return Source paths mapped to matching executable lines.
     * @throws IOException On class file read failure.
     */
    public static Map<String, Set<Integer>> resolve(
            CompilationResult compiled, Collection<BreakpointSpec> specs) throws IOException {
        return resolve(read(compiled), specs);
    } // resolve

    /**
     * Resolves breakpoint specifications against a map of source paths to executable lines.
     *
     * @param availableLines Source paths mapped to valid executable lines.
     * @param specs Breakpoint specifications.
     * @return Source paths mapped to matching executable lines.
     */
    public static Map<String, Set<Integer>> resolve(
            Map<String, Set<Integer>> availableLines, Collection<BreakpointSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return availableLines;
        } // if
        Map<String, Set<Integer>> resolved = new TreeMap<>();
        for (BreakpointSpec spec : specs) {
            for (Map.Entry<String, Set<Integer>> entry : availableLines.entrySet()) {
                String sourcePath = entry.getKey();
                if (spec.matchesSourcePath(sourcePath)) {
                    if (spec.lineNumber() == -1 || entry.getValue().contains(spec.lineNumber())) {
                        resolved.computeIfAbsent(sourcePath, k -> new TreeSet<>())
                                .add(spec.lineNumber());
                    } // if
                } // if
            } // for
        } // for
        return resolved;
    } // resolve

    /** Accumulates line table entries under each class's SourceFile attribute. */
    private static final class LineVisitor extends ClassVisitor {
        private final Map<String, Set<Integer>> lines;
        private String packagePath;
        private String sourcePath;

        /**
         * Constructs a visitor sharing the output index.
         * @param lines Executable line index.
         */
        LineVisitor(Map<String, Set<Integer>> lines) {
            super(Opcodes.ASM9);
            this.lines = lines;
        } // LineVisitor

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            int separator = name.lastIndexOf('/');
            packagePath = name.substring(0, separator + 1);
        } // visit

        @Override
        public void visitSource(String source, String debug) {
            sourcePath = packagePath + source;
        } // visitSource

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitLineNumber(int line, Label start) {
                    if (sourcePath != null && line > 0) {
                        lines.computeIfAbsent(sourcePath, key -> new TreeSet<>()).add(line);
                    } // if
                } // visitLineNumber
            };
        } // visitMethod
    } // LineVisitor
} // BreakpointReader
