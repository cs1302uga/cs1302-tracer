package cs1302.tracer;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.model.BreakpointEntry;
import cs1302.tracer.model.TraceFormat;
import cs1302.tracer.model.pytutor.PyTutorTrace;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import cs1302.tracer.trace.DebugTraceHelper;
import cs1302.tracer.trace.ExecutionSnapshot;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/** Entry point for the tracer program. */
@Command(
    name = "code-tracer",
    description = "Trace Java program execution and inspect memory states.",
    mixinStandardHelpOptions = true,
    versionProvider = App.PropertiesVersionProvider.class)
public class App {

  /** Provides version string resolved from Maven resource filtering at build time. */
  public static class PropertiesVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      try (InputStream is = App.class.getResourceAsStream("/cs1302/tracer/version.properties")) {
        if (is != null) {
          Properties props = new Properties();
          props.load(is);
          return new String[] {props.getProperty("version", "development")};
        }
      } catch (IOException ignored) {
      }
      return new String[] {"development"};
    }
  }

  static Consumer<Integer> systemExitHandler = System::exit;

  public static int execute(String[] args) {
    return new CommandLine(new App())
        .addSubcommand(new Trace())
        .addSubcommand(new ListBreakpoints())
        .addSubcommand(new ShowLicenses())
        .execute(args);
  }

  public static void main(String[] args) throws Exception {
    int exitCode = execute(args);
    systemExitHandler.accept(exitCode);
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

    Consumer<Integer> exitHandler = System::exit;

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

    protected Optional<Path> getInputPath() {
      return input != null ? Optional.of(input.toPath().toAbsolutePath()) : Optional.empty();
    }

    /**
     * Parse the given Java source code string without type solver.
     *
     * @param source The Java source code to parse.
     * @return The parsed Java source code.
     */
    protected CompilationUnit parseSource(String source) {
      return new com.github.javaparser.JavaParser(
              new com.github.javaparser.ParserConfiguration().setLanguageLevel(LanguageLevel.CURRENT))
          .parse(source)
          .getResult()
          .orElseThrow(() -> new IllegalArgumentException("Failed to parse Java source"));
    }

    /**
     * Parse the given Java source code string with optional source root for type resolution.
     *
     * @param source The Java source code to parse.
     * @param sourceRoot The root directory where source files for the program are located. This
     *     will be used to get more information when resolving types.
     * @return The parsed Java source code.
     * @throws ParseProblemException If parsing failed.
     */
    protected CompilationUnit parseSource(String source, Optional<Path> sourceRoot) {
      CombinedTypeSolver combinedTypeSolver = new CombinedTypeSolver();
      combinedTypeSolver.add(new ReflectionTypeSolver());
      sourceRoot.ifPresent(sr -> combinedTypeSolver.add(new JavaParserTypeSolver(sr)));
      JavaSymbolSolver symbolSolver = new JavaSymbolSolver(combinedTypeSolver);

      com.github.javaparser.ParserConfiguration config =
          new com.github.javaparser.ParserConfiguration()
              .setSymbolResolver(symbolSolver)
              .setLanguageLevel(LanguageLevel.CURRENT);

      return new com.github.javaparser.JavaParser(config)
          .parse(source)
          .getResult()
          .orElseThrow(
              () -> new IllegalArgumentException("Failed to parse Java source with symbol solver"));
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
        names = {"--all-breakpoints", "-a"},
        description =
            "Include all encountered breakpoint instances in chronological order in the output trace. "
                + "If no explicit breakpoints are specified, all valid lines are traced.")
    boolean allBreakpoints = false;

    @Option(
        names = {"--accumulate-breakpoints"},
        description =
            "Output an array of snapshots containing each time a breakpoint was "
                + "reached instead of just the last time.")
    boolean accumulateBreakpoints = false;

    @Option(
        names = {"--format", "-f"},
        description = "Output trace format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
        defaultValue = "pytutor")
    TraceFormat format = TraceFormat.PYTUTOR;

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

      // run a trace
      try {
        List<CompilationHelper.SourceFile> sourceFiles =
            CompilationHelper.parseMultiFileStream(source);
        CompilationHelper.SourceFile entryFile = CompilationHelper.findEntryPoint(sourceFiles);
        CompilationUnit preCu = entryFile.ast();
        Optional<Path> sourceRoot = CompilationHelper.findSourceRoot(preCu, getInputPath());

        try (CompilationResult compilationResult =
            CompilationHelper.compile(source, sourceRoot)) {
          Optional<Path> parserSourceRoot =
              sourceRoot.isPresent() ? sourceRoot : Optional.of(compilationResult.classPath());
          CompilationUnit cu = parseSource(entryFile.content(), parserSourceRoot);

          if (format == TraceFormat.MODERN) {
            ModernTraceSerializer modernSerializer =
                new ModernTraceSerializer(removeMainArgs, inlineStrings, removeMethodThis);

            if (allBreakpoints) {
              Collection<Integer> targetLines =
                  breakpoints != null
                      ? breakpoints
                      : DebugTraceHelper.getValidBreakpointLines(compilationResult);
              List<ExecutionSnapshot> chronologicalSnapshots =
                  DebugTraceHelper.traceChronological(compilationResult, targetLines, cu, true);
              cs1302.tracer.model.modern.Trace modernTrace =
                  modernSerializer.createTrace(source, chronologicalSnapshots);
              System.out.println(ModernTraceSerializer.getGson().toJson(modernTrace));
            } else if (breakpoints == null) {
              ExecutionSnapshot trace = DebugTraceHelper.trace(compilationResult, cu);
              cs1302.tracer.model.modern.Trace modernTrace = modernSerializer.createTrace(source, trace);
              System.out.println(ModernTraceSerializer.getGson().toJson(modernTrace));
            } else {
              Map<Integer, List<ExecutionSnapshot>> trace =
                  DebugTraceHelper.trace(compilationResult, breakpoints, cu);
              if (accumulateBreakpoints) {
                cs1302.tracer.model.modern.Trace modernTrace =
                    modernSerializer.createBreakpointsTrace(source, trace);
                System.out.println(ModernTraceSerializer.getGson().toJson(modernTrace));
              } else {
                Map<Integer, ExecutionSnapshot> singlePerBp =
                    trace.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getLast()));
                cs1302.tracer.model.modern.Trace modernTrace =
                    modernSerializer.createBreakpointsTrace(source, singlePerBp);
                System.out.println(ModernTraceSerializer.getGson().toJson(modernTrace));
              }
            }
          } else {
            PyTutorSerializer configuredSerializer =
                new PyTutorSerializer(removeMainArgs, inlineStrings, removeMethodThis);

            if (allBreakpoints) {
              Collection<Integer> targetLines =
                  breakpoints != null
                      ? breakpoints
                      : DebugTraceHelper.getValidBreakpointLines(compilationResult);
              List<ExecutionSnapshot> chronologicalSnapshots =
                  DebugTraceHelper.traceChronological(compilationResult, targetLines, cu, true);
              PyTutorTrace pyTutorTrace =
                  configuredSerializer.createTrace(source, chronologicalSnapshots);
              System.out.println(PyTutorSerializer.getGson().toJson(pyTutorTrace));
            } else if (breakpoints == null) {
              ExecutionSnapshot trace = DebugTraceHelper.trace(compilationResult, cu);
              String pyTutorSnapshot = configuredSerializer.serialize(source, trace);
              System.out.println(pyTutorSnapshot);
            } else {
              Map<Integer, List<ExecutionSnapshot>> trace =
                  DebugTraceHelper.trace(compilationResult, breakpoints, cu);
              if (accumulateBreakpoints) {
                Map<Integer, List<PyTutorTrace>> pyTutorSnapshots =
                    trace.entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                Map.Entry::getKey,
                                e ->
                                    e.getValue().stream()
                                        .map(s -> configuredSerializer.createTrace(source, s))
                                        .toList()));
                System.out.println(PyTutorSerializer.getGson().toJson(pyTutorSnapshots));
              } else {
                Map<Integer, PyTutorTrace> pyTutorSnapshots =
                    trace.entrySet().stream()
                        .collect(
                            Collectors.toMap(
                                Map.Entry::getKey,
                                e ->
                                    configuredSerializer.createTrace(
                                        source, e.getValue().getLast())));
                System.out.println(PyTutorSerializer.getGson().toJson(pyTutorSnapshots));
              }
            }
          } // if format
        }
      } catch (Throwable cause) {
        System.err.println("Unable to generate trace!");
        if (verbose) {
          cause.printStackTrace();
        } // if
        exitHandler.accept(1);
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

      // show breakpoints
      try {
        List<CompilationHelper.SourceFile> sourceFiles =
            CompilationHelper.parseMultiFileStream(source);
        CompilationHelper.SourceFile entryFile = CompilationHelper.findEntryPoint(sourceFiles);
        CompilationUnit preCu = entryFile.ast();
        Optional<Path> sourceRoot = CompilationHelper.findSourceRoot(preCu, getInputPath());

        try (CompilationResult compilationResult =
            CompilationHelper.compile(source, sourceRoot)) {

          Map<String, Set<Integer>> breakpointsByFile =
              DebugTraceHelper.getValidBreakpointLinesByFile(compilationResult);

          if (sourceFiles.size() > 1) {
            if (outputJson) {
              List<BreakpointEntry> output = new ArrayList<>();
              for (CompilationHelper.SourceFile sf : sourceFiles) {
                String normalizedPath = sf.relativePath().replace(File.separatorChar, '/');
                Set<Integer> validLines =
                    breakpointsByFile.getOrDefault(
                        normalizedPath,
                        breakpointsByFile.getOrDefault(sf.relativePath(), Collections.emptySet()));
                if (validLines.isEmpty()) {
                  String simpleName = Path.of(sf.relativePath()).getFileName().toString();
                  for (Map.Entry<String, Set<Integer>> entry : breakpointsByFile.entrySet()) {
                    if (entry.getKey().endsWith(simpleName)) {
                      validLines = entry.getValue();
                      break;
                    }
                  }
                }

                String[] fileLines = sf.content().split("\n");
                for (int i = 0; i < fileLines.length; i++) {
                  int lineNumber = i + 1;
                  boolean validBreakpoint = validLines.contains(lineNumber);
                  output.add(
                      new BreakpointEntry(lineNumber, validBreakpoint, fileLines[i], normalizedPath));
                }
              }
              System.out.println(PyTutorSerializer.getGson().toJson(output));
            } else {
              StringBuilder annotatedSource = new StringBuilder();
              for (int f = 0; f < sourceFiles.size(); f++) {
                CompilationHelper.SourceFile sf = sourceFiles.get(f);
                String normalizedPath = sf.relativePath().replace(File.separatorChar, '/');
                annotatedSource.append("// --- ").append(normalizedPath).append(" ---\n");
                Set<Integer> validLines =
                    breakpointsByFile.getOrDefault(
                        normalizedPath,
                        breakpointsByFile.getOrDefault(sf.relativePath(), Collections.emptySet()));
                if (validLines.isEmpty()) {
                  String simpleName = Path.of(sf.relativePath()).getFileName().toString();
                  for (Map.Entry<String, Set<Integer>> entry : breakpointsByFile.entrySet()) {
                    if (entry.getKey().endsWith(simpleName)) {
                      validLines = entry.getValue();
                      break;
                    }
                  }
                }

                String[] fileLines = sf.content().split("\n");
                int digitLength = ((int) Math.log10(Math.max(1, fileLines.length))) + 1;
                for (int i = 0; i < fileLines.length; i++) {
                  if (validLines.contains(i + 1)) {
                    annotatedSource.append(
                        Ansi.AUTO.string(String.format("@|green b %" + digitLength + "d | |@", i + 1)));
                  } else {
                    annotatedSource.append(String.format("  %" + digitLength + "d | ", i + 1));
                  }
                  annotatedSource.append(fileLines[i]);
                  if (i < fileLines.length - 1 || f < sourceFiles.size() - 1) {
                    annotatedSource.append('\n');
                  }
                }
              }
              System.out.println(annotatedSource.toString());
            }
          } else {
            Collection<Integer> availableBreakpoints =
                DebugTraceHelper.getValidBreakpointLines(compilationResult);
            String[] sourceLines = source.split("\n");
            int digitLength = ((int) Math.log10(Math.max(1, sourceLines.length))) + 1;

            if (outputJson) {
              List<BreakpointEntry> output = new ArrayList<>();
              for (int i = 0; i < sourceLines.length; i++) {
                int lineNumber = i + 1;
                boolean validBreakpoint = availableBreakpoints.contains(lineNumber);
                String lineContent = sourceLines[i];
                output.add(new BreakpointEntry(lineNumber, validBreakpoint, lineContent));
              } // for
              System.out.println(PyTutorSerializer.getGson().toJson(output));
            } else {
              StringBuilder annotatedSource = new StringBuilder();
              for (int i = 0; i < sourceLines.length; i++) {
                if (availableBreakpoints.contains(i + 1)) {
                  annotatedSource.append(
                      Ansi.AUTO.string(String.format("@|green b %" + digitLength + "d | |@", i + 1)));
                } else {
                  annotatedSource.append(String.format("  %" + digitLength + "d | ", i + 1));
                }
                annotatedSource.append(sourceLines[i]);
                if (i < sourceLines.length - 1) {
                  annotatedSource.append('\n');
                } // if
              } // for
              System.out.println(annotatedSource.toString());
            } // if
          }
        }
      } catch (Throwable cause) {
        System.err.println("Unable to list breakpoints!");
        if (verbose) {
          cause.printStackTrace();
        } // if
        exitHandler.accept(1);
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
      System.out.println(LicenseHelper.getLicenseText());
    }
  }
}
