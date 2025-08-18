package cs1302.tracer;

import org.apache.commons.cli.Options;

import org.apache.commons.cli.ParseException;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;

import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import cs1302.tracer.serialize.PyTutorSerializer;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Entry point for the tracer program
 */
public class App {

    private static String readStdIn() {
        StringBuilder sb = new StringBuilder();
        try (Scanner scan = new Scanner(System.in)) {
            while (scan.hasNextLine()) {
                sb.append(scan.nextLine()).append("\n");
            } // while
        } // try
        return sb.toString();
    } // readStdIn

    public static void main(String[] args) throws Exception {

        CommandLine opts = App.getOptions(args);

        String source = switch (opts.getOptionValue("input")) {
            case null -> readStdIn();
            default -> Files.readString(Paths.get(opts.getOptionValue("input")));
        }; // switch

        // compile the source code
        CompilationResult compilationResult = CompilationHelper.compile(source);

        // list breakpoints if that options is set
        if (opts.hasOption("list-available-breakpoints")
            || opts.hasOption("list-available-breakpoints-json")) {
            App.listBreakpoints(opts, source, compilationResult);
            System.exit(0);
        } //

        // generate trace by default
        generateTrace(opts, source, compilationResult);
        System.exit(0);

    } // main

    public static void generateTrace(CommandLine opts, String source, CompilationResult compilationResult) {
        // run a trace
        try {
            String[] breakpoints = opts.getOptionValues("breakpoint");
            if (breakpoints == null) {
                ExecutionSnapshot trace = DebugTraceHelper.trace(compilationResult);
                JSONObject pyTutorSnapshot = PyTutorSerializer.serialize(
                    source,
                    trace,
                    opts.hasOption("inline-strings"),
                    opts.hasOption("remove-main-args")
                );
                System.out.println(pyTutorSnapshot);
            } else {
                Map<Integer, ExecutionSnapshot> trace = DebugTraceHelper.trace(
                    compilationResult,
                    Stream.of(breakpoints).map(Integer::parseInt).toList());
                Map<Integer, JSONObject> pyTutorSnapshots = (Map<Integer, JSONObject>) trace
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                        e -> e.getKey(),
                        e -> PyTutorSerializer.serialize(
                            source,
                            e.getValue(),
                            opts.hasOption("inline-strings"),
                            opts.hasOption("remove-main-args")
                        )));
                System.out.println(new JSONObject(pyTutorSnapshots));
            } // if
        } catch (Throwable cause) {
            System.err.println("Unable to generate trace!");
            if (opts.hasOption("verbose")) {
                cause.printStackTrace();
            } // if
            System.exit(1);
        } // try
    } // generateTrace

    public static void listBreakpoints(CommandLine opts, String source, CompilationResult compilationResult) {
        // show breakpoints
        try {
            boolean outputJson = opts.hasOption("list-available-breakpoints-json");
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
                        annotatedSource.append(String.format("  %" + digitLength + "d | ", i + 1));
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
            if (opts.hasOption("verbose")) {
                cause.printStackTrace();
            } // if
            System.exit(1);
        } // try
    } // listBreakpoints

    /**
     * Parse command-line options into a CommandLine instance. If parsing fails or
     * the help option is encountered, a usage message is printed to stdout and the
     * process exits.
     *
     * @param args command-line arguments, usually provided directly from main
     * @return a constructed CommandLine object
     */
    public static CommandLine getOptions(String[] args) {

        Options options = new Options();

        // TODO: add an option for restricting which classes/objects have their full set of fields
        // dumped vs just a class fqn. would help declutter snapshots containing java builtins like
        // scanner, stringbuilder, etc

        options.addOption(
            Option.builder("i")
                .longOpt("input")
                .desc("input path to Java source file (defaults to stdin if omitted)")
                .required(false)
                .hasArg()
                .build());

        options.addOption(
            Option.builder("o")
                .longOpt("output")
                .desc("output path (defaults to stdout if omitted)")
                .required(false)
                .hasArg()
                .build());

        options.addOption(
            Option.builder("b")
                .longOpt("breakpoint")
                .desc(
                    """
                    breakpoint at which to take a snapshot. the snapshot taken will represent the
                    state of memory immediately before this line is executed. multiple instances
                    "of this option can be provided. if none are provided, the default behavior is
                    to take one snapshot at the end of the program's main method.
                    """.strip()
                ) // desc
                .required(false)
                .hasArg()
                .build());

        options.addOption(
            Option.builder("l")
                .longOpt("list-available-breakpoints")
                .desc(
                    """
                    instead of running a trace, list the breakpoints available in the provided
                    source file.
                    """.strip())
                .required(false)
                .build());

        options.addOption(
            Option.builder("L")
                .longOpt("list-available-breakpoints-json")
                .desc(
                    """
                    instead of running a trace, list the breakpoints available in the provided
                    source file in JSON format.
                    """.strip())
                .required(false)
                .build());

        options.addOption(
            Option.builder("s")
                .longOpt("inline-strings")
                .desc("if provided, strings are inlined into fields instead of going through a reference")
                .required(false)
                .build());

        options.addOption(
            Option.builder()
                .longOpt("remove-main-args")
                .desc("don't include the main method's `args` parameter in the output")
                .required(false)
                .build());

        options.addOption(
            Option.builder("v")
                .longOpt("verbose")
                .desc("output messages about what the tracer is doing")
                .required(false)
                .build());

        options.addOption(
            Option.builder()
                .longOpt("show-licenses")
                .desc("show the licenses for projects used in this program and then exit")
                .required(false)
                .build());

        options.addOption(
            Option.builder("h")
                .longOpt("help")
                .desc("print this help message and then exit")
                .required(false)
                        .build());

        CommandLineParser parser = new DefaultParser();

        String header =
            """
            Generate an execution trace for a Java program.
            """.strip();

        String footer = "";

        HelpFormatter helpFormatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("help")) {
                helpFormatter.printHelp("cs1302-tracer", header, options, footer, true);
                System.exit(0);
            } else if (cmd.hasOption("show-licenses")) {
                System.out.println(
                    """
            This program includes and uses several open source projects.
            \tApache Commons CLI (https://commons.apache.org/proper/commons-cli/)
            \tJansi (https://fusesource.github.io/jansi/)
            \tJavaParser (https://javaparser.org/),
            \tJSON-Java (https://github.com/stleary/JSON-java)
            Apache Commons CLI, Jansi, and JavaParser are licensed under the Apache License
            2.0, which can be found below this message. JSON-Java has been dedicated to the
            public domain. Thank you to the authors and contributors of those projects!
            """ + LicenseHelper.APACHE_2_0);
                System.exit(0);
            }

            return cmd;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            new HelpFormatter().printHelp("code-tracer", options);
            System.exit(-1);
        }

        throw new IllegalStateException("Unreachable.");
    }
}
