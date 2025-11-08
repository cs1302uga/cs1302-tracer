package cs1302.tracer;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;
import org.json.JSONArray;
import org.json.JSONObject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Entry point for the tracer program. */
@Command(name = "code-tracer")
public class App {

  public static void main(String[] args) throws Exception {
    int exitCode =
        new CommandLine(new App())
            .addSubcommand(new Trace())
            .addSubcommand(new ListBreakpoints())
            .addSubcommand(new ShowLicenses())
            .execute(args);

    System.exit(exitCode);
  } // main

  /** Base class that holds common CLI parameters. */
  @Command
  abstract static class CommandBase implements Runnable {
    @Option(
        names = {"--verbose", "-v"},
        description = "Output messages about what the tracer is doing.")
    boolean verbose = false;

    @Option(
        names = {"--input", "-i"},
        description = "Input path to Java source file (defaults to stdin if omitted).")
    File input = null;

    /**
     * Read the entirety of {@code input} into a string. If {@code input} is null, it reads and
     * returns the content of stdin.
     *
     * @return The read contents of the file.
     * @throws RuntimeException if an IO exception occured
     */
    protected String readInputFile() {
      if (input == null) {
        // read stdin
        StringBuilder sb = new StringBuilder();
        try (Scanner scan = new Scanner(System.in)) {
          while (scan.hasNextLine()) {
            sb.append(scan.nextLine()).append("\n");
          } // while
        } // try
        return sb.toString();
      } else {
        try {
          return Files.readString(input.toPath());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    } // readInputFile

    /**
     * Parse the given Java source code string.
     *
     * @param source The Java source code to parse.
     * @return The parsed Java source code.
     * @throws ParseProblemException If parsing failed.
     */
    protected CompilationUnit parseSource(String source) {
      CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
      combinedTypeSolver.add(new ReflectionTypeSolver());
      JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

      StaticJavaParser.getParserConfiguration()
          .setSymbolResolver(symbolSolver)
          .setLanguageLevel(LanguageLevel.CURRENT);

      CompilationUnit cu = StaticJavaParser.parse(source);

      return cu;
    }
  }

  /** Run a trace. */
  @Command(
      name = "trace",
      description = "Generate an execution trace for a Java program.",
      mixinStandardHelpOptions = true)
  static class Trace extends CommandBase {
    @Option(
        names = {"--remove-main-args"},
        description = "Don't include the main method's `args` parameter in the output.")
    boolean removeMainArgs = false;

    @Option(
        names = {"--inline-strings", "-s"},
        description =
            "If provided, strings are inlined into fields instead "
                + "of going through a reference.")
    boolean inlineStrings = false;

    @Option(
        names = {"--remove-method-this"},
        description = "Don't include the value of `this` for methods in the output.")
    boolean removeMethodThis = false;

    @Option(
        names = {"--accumulate-breakpoints"},
        description =
            "Output an array of snapshots containing each time a breakpoint was "
                + "reached instead of just the last time.")
    boolean accumulateBreakpoints = false;

    @Option(
        names = {"--breakpoints", "-b"},
        description =
            "Breakpoints at which to take snapshots. The snapshots taken will "
                + "represent the state of memory immediately before each line is executed. "
                + "If no breakpoints are provided, the default behavior is to take"
                + "one snapshot at the end of the program's main method.")
    List<Integer> breakpoints = null;

    /** Run and trace a compiled Java program and output the resulting trace JSON to stdout. */
    @Override
    public void run() {
      String source = readInputFile();
      CompilationUnit cu = parseSource(source);

      // run a trace
      try {
        CompilationResult compilationResult = CompilationHelper.compile(source, cu);

        PyTutorSerializer configuredSerializer =
            new PyTutorSerializer(removeMainArgs, inlineStrings, removeMethodThis);

        if (breakpoints == null) {
          ExecutionSnapshot trace = DebugTraceHelper.trace(compilationResult, cu);
          JSONObject pyTutorSnapshot = configuredSerializer.serialize(source, trace);
          System.out.println(pyTutorSnapshot);
        } else {
          Map<Integer, List<ExecutionSnapshot>> trace =
              DebugTraceHelper.trace(compilationResult, breakpoints, cu);
          if (accumulateBreakpoints) {
            Map<Integer, JSONArray> pyTutorSnapshots =
                trace.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e ->
                                new JSONArray(
                                    e.getValue().stream()
                                        .map(s -> configuredSerializer.serialize(source, s))
                                        .toList())));
            System.out.println(new JSONObject(pyTutorSnapshots));
          } else {
            Map<Integer, JSONObject> pyTutorSnapshots =
                (Map<Integer, JSONObject>)
                    trace.entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                Map.Entry::getKey,
                                e ->
                                    configuredSerializer.serialize(
                                        source, e.getValue().getLast())));
            System.out.println(new JSONObject(pyTutorSnapshots));
          }
        } // if
      } catch (Throwable cause) {
        System.err.println("Unable to generate trace!");
        if (verbose) {
          cause.printStackTrace();
        } // if
        System.exit(1);
      } // try
    }
  }

  /** List the breakpoint lines available for a compiled Java program. */
  @Command(
      name = "list-breakpoints",
      description = "List the breakpoints available in the provided source file.",
      mixinStandardHelpOptions = true)
  static class ListBreakpoints extends CommandBase {
    @Option(
        names = {"--json", "-j"},
        description = "Output available breakpoints in JSON format.")
    boolean outputJson = false;

    @Override
    public void run() {
      String source = readInputFile();
      CompilationUnit cu = parseSource(source);

      // show breakpoints
      try {
        CompilationResult compilationResult = CompilationHelper.compile(source, cu);

        Collection<Integer> availableBreakpoints =
            DebugTraceHelper.getValidBreakpointLines(compilationResult);
        String[] sourceLines = source.split("\n");
        int digitLength = ((int) Math.log10(sourceLines.length)) + 1;

        if (outputJson) {
          JSONArray output = new JSONArray();
          for (int i = 0; i < sourceLines.length; i++) {
            int lineNumber = i + 1;
            boolean validBreakpoint = availableBreakpoints.contains(lineNumber);
            String lineContent = sourceLines[i];
            JSONObject lineEntry =
                new JSONObject()
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
                  Ansi.ansi().fgGreen().a(String.format("b %" + digitLength + "d | ", i + 1)));
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
        if (verbose) {
          cause.printStackTrace();
        } // if
        System.exit(1);
      } // try
    }
  }

  /** Print dependency licenses to console. */
  @Command(
      name = "show-licenses",
      description = "Show the licenses for projects used in this program and then exit.",
      mixinStandardHelpOptions = true)
  static class ShowLicenses implements Runnable {
    @Override
    public void run() {
      System.out.println(
          """
          This program includes and uses several open source projects.
          \tJansi (https://fusesource.github.io/jansi/)
          \tJavaParser (https://javaparser.org/),
          \tJSON-Java (https://github.com/stleary/JSON-java)
          \tPicocli (https://picocli.info/)
          Jansi, JavaParser, and picocli are licensed under the Apache
          License 2.0, which can be found below this message. JSON-Java has been
          dedicated to the public domain. Thank you to the authors and contributors of
          those projects!
          """
              + LicenseHelper.APACHE_2_0);
    }
  }
}
