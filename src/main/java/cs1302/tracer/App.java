package cs1302.tracer;

import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Entry point for the tracer program.
 */
@Command(name = "code-tracer",
         description = "generate an execution trace for a Java program",
         mixinStandardHelpOptions = true)
public class App implements Runnable {
    @Option(names = { "--verbose", "-v" },
            description = "output messages about what the tracer is doing")
    boolean verbose = false;

    @Option(names = { "--remove-main-args" },
           description = "don't include the main method's `args` parameter in the output")
    boolean removeMainArgs = false;

    @Option(names = { "--inline-strings", "-s" },
            description = "if provided, strings are inlined into fields instead "
                          + "of going through a reference")
    boolean inlineStrings = false;

    @Option(names = { "--remove-method-this" },
            description = "don't include the value of `this` for methods in the output")
    boolean removeMethodThis = false;

    @Option(names = { "--breakpoints", "-b" },
            description = "breakpoints at which to take snapshots. the snapshots taken will "
                    + "represent the state of memory immediately before each line is executed. "
                    + "if no breakpoints are provided, the default behavior is to take"
                    + "one snapshot at the end of the program's main method.")
    List<Integer> breakpoints = null;

    @Option(names = { "--input", "-i" },
            description = "input path to Java source file (defaults to stdin if omitted)")
    File input = null;

    /**
     * Run and trace a compiled Java program and output the resulting trace JSON to
     * stdout.
     */
    @Override
    public void run() {
        String source = switch (input) {
        case null -> readStdIn();
        default -> {
            try {
                yield Files.readString(input.toPath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        }; // switch

        // run a trace
        try {
            CompilationResult compilationResult = CompilationHelper.compile(source);

            PyTutorSerializer configuredSerializer = new PyTutorSerializer(
                    removeMainArgs, inlineStrings, removeMethodThis);

            if (breakpoints == null) {
                ExecutionSnapshot trace = DebugTraceHelper.trace(compilationResult);
                JSONObject pyTutorSnapshot = configuredSerializer.serialize(source, trace);
                System.out.println(pyTutorSnapshot);
            } else {
                Map<Integer, ExecutionSnapshot> trace = DebugTraceHelper.trace(
                        compilationResult,
                        breakpoints);
                Map<Integer, JSONObject> pyTutorSnapshots = (Map<Integer, JSONObject>) trace
                        .entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> configuredSerializer.serialize(source, e.getValue())));
                System.out.println(new JSONObject(pyTutorSnapshots));
            } // if
        } catch (Throwable cause) {
            System.err.println("Unable to generate trace!");
            if (verbose) {
                cause.printStackTrace();
            } // if
            System.exit(1);
        } // try
    }

    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new App())
                .addSubcommand(new ListBreakpoints())
                .addSubcommand(new ShowLicenses())
                .execute(args);

        System.exit(exitCode);
    } // main

    /** List the breakpoint lines available for a compiled Java program. */
    @Command(name = "list-breakpoints",
             description = "list the breakpoints available in the provided source file",
             mixinStandardHelpOptions = true)
    static class ListBreakpoints implements Runnable {
        @Option(names = { "--verbose", "-v" },
                description = "output messages about what the tracer is doing")
        boolean verbose = false;

        @Option(names = { "--json", "-j" },
                description = "output available breakpoints in JSON format")
        boolean outputJson = false;

        @Option(names = { "--input", "-i" },
                description = "input path to Java source file (defaults to stdin if omitted)")
        File input = null;

        @Override
        public void run() {
            String source = switch (input) {
            case null -> readStdIn();
            default -> {
                try {
                    yield Files.readString(input.toPath());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            }; // switch

            // show breakpoints
            try {
                CompilationResult compilationResult = CompilationHelper.compile(source);

                Collection<Integer> availableBreakpoints = DebugTraceHelper
                        .getValidBreakpointLines(compilationResult);
                String[] sourceLines = source.split("\n");
                int digitLength = ((int) Math.log10(sourceLines.length)) + 1;

                if (outputJson) {
                    JSONArray output = new JSONArray();
                    for (int i = 0; i < sourceLines.length; i++) {
                        int lineNumber = i + 1;
                        boolean validBreakpoint = availableBreakpoints.contains(lineNumber);
                        String lineContent = sourceLines[i];
                        JSONObject lineEntry = new JSONObject()
                                .put("lineNumber", lineNumber)
                                .put("validBreakpoint", validBreakpoint)
                                .put("lineContent", lineContent);
                        output.put(lineEntry);
                    } // for
                    System.out.println(output);
                } else {
                    StringBuilder annotatedSource = new StringBuilder();
                    AnsiConsole.systemInstall();
                    for (int i = 0; i < sourceLines.length; i++) {

                        if (availableBreakpoints.contains(i + 1)) {
                            annotatedSource.append(
                                    Ansi.ansi().fgGreen()
                                            .a(String.format("b %" + digitLength + "d | ", i + 1)));
                        } else {
                            annotatedSource.append(
                                    String.format("  %" + digitLength + "d | ", i + 1));
                        }

                        annotatedSource.append(sourceLines[i]);
                        annotatedSource.append(Ansi.ansi().reset());

                        if (i < sourceLines.length - 1) {
                            annotatedSource.append('\n');
                        } // if
                    } // for
                    AnsiConsole.systemUninstall();
                    System.out.println(annotatedSource.toString());
                } // if

            } catch (Throwable cause) {
                System.err.println("Unable to list breakpoints!");
                if (verbose) {
                    cause.printStackTrace();
                } // if
                System.exit(1);
            } // try
        }
    }

    /** Print dependency licenses to console. */
    @Command(name = "show-licenses",
             description = "show the licenses for projects used in this program and then exit",
             mixinStandardHelpOptions = true)
    static class ShowLicenses implements Runnable {
        @Override
        public void run() {
            System.out.println("""
                    This program includes and uses several open source projects.
                    \tJansi (https://fusesource.github.io/jansi/)
                    \tJavaParser (https://javaparser.org/),
                    \tJSON-Java (https://github.com/stleary/JSON-java)
                    \tPicocli (https://picocli.info/)
                    Jansi, JavaParser, and picocli are licensed under the Apache
                    License 2.0, which can be found below this message. JSON-Java has been
                    dedicated to the public domain. Thank you to the authors and contributors of
                    those projects!
                    """ + LicenseHelper.APACHE_2_0);
        }
    }

    /**
     * Read the entirety of stdin into a string.
     *
     * @return The read contents of stdin.
     */
    private static String readStdIn() {
        StringBuilder sb = new StringBuilder();
        try (Scanner scan = new Scanner(System.in)) {
            while (scan.hasNextLine()) {
                sb.append(scan.nextLine()).append("\n");
            } // while
        } // try
        return sb.toString();
    } // readStdIn
}
