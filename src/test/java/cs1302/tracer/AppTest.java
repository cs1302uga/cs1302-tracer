package cs1302.tracer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.App.CommandBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import picocli.CommandLine;

/** Tests for cs1302-tracer. */
@DisplayName("App CLI")
public class AppTest {

  /**
   * Execute a tracer command with a program input and command-line arguments, and get the command's
   * standard output.
   *
   * @param commandSupplier A supplier for the command you want to run.
   * @param testProgram A string containing the Java input you want to give to the command.
   * @param options The command-line arguments you want to pass to the command. Do not include
   *     -i/--input.
   * @param <T> The type of the command that you want to run.
   * @return The standard output of the command, or empty if command execution failed.
   */
  static <T extends CommandBase> Optional<String> executeCommand(
      Supplier<T> commandSupplier, String testProgram, String... options) {
    File tempFile = null;
    try {
      tempFile = File.createTempFile("cs1302-tracer", ".java");
      tempFile.deleteOnExit();
      Files.writeString(tempFile.toPath(), testProgram);

      T app = commandSupplier.get();
      app.exitHandler = code -> {};
      CommandLine cmd = new CommandLine(app);

      ArrayList<String> args = new ArrayList<>();
      args.addAll(Arrays.asList(options));
      args.add("--input=" + tempFile.getCanonicalPath());

      PrintStream originalOut = System.out;
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      boolean ranSuccessfully = false;
      try {
        System.setOut(new PrintStream(baos));
        ranSuccessfully = cmd.execute(args.toArray(String[]::new)) == 0;
      } finally {
        System.setOut(originalOut);
      }

      if (ranSuccessfully) {
        return Optional.of(baos.toString());
      } else {
        return Optional.empty();
      }
    } catch (IOException e) {
      return Optional.empty();
    } finally {
      if (tempFile != null) {
        try {
          tempFile.delete();
        } catch (SecurityException e) {
          // do nothing, at least we tried
        }
      }
    }
  }

  @Test
  @DisplayName("should instantiate App constructor and run main")
  void testAppConstructorAndMain() throws Exception {
    App app = new App();
    assertThat(app).isNotNull();

    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(new ByteArrayOutputStream()));
      AtomicInteger capturedExit = new AtomicInteger(-1);
      App.systemExitHandler = capturedExit::set;
      App.main(new String[] {"show-licenses"});
      assertThat(capturedExit.get()).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }
  }

  @Test
  @DisplayName("should execute App.execute with arguments")
  void testAppExecute() {
    PrintStream originalOut = System.out;
    try {
      System.setOut(new PrintStream(new ByteArrayOutputStream()));
      int exitCode = App.execute(new String[] {"show-licenses"});
      assertThat(exitCode).isEqualTo(0);
    } finally {
      System.setOut(originalOut);
    }
  }

  @Test
  @DisplayName("should display usage for top-level command")
  void shouldDisplayHelpForTopLevelCommand() {
    CommandLine cmd =
        new CommandLine(new App())
            .addSubcommand(new App.Trace())
            .addSubcommand(new App.ListBreakpoints())
            .addSubcommand(new App.ShowLicenses());

    StringWriter sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    int exitCode = cmd.execute("--help");
    assertThat(exitCode).isEqualTo(0);
    String output = sw.toString();
    assertThat(output).contains("Usage: code-tracer");
    assertThat(output).contains("Trace Java program execution");
    assertThat(output).contains("trace");
    assertThat(output).contains("list-breakpoints");
    assertThat(output).contains("show-licenses");

    // Short option -h
    StringWriter swShort = new StringWriter();
    cmd.setOut(new PrintWriter(swShort));
    int exitCodeShort = cmd.execute("-h");
    assertThat(exitCodeShort).isEqualTo(0);
    assertThat(swShort.toString()).contains("Usage: code-tracer");

    StringWriter swVersion = new StringWriter();
    cmd.setOut(new PrintWriter(swVersion));
    int exitCodeVersion = cmd.execute("--version");
    assertThat(exitCodeVersion).isEqualTo(0);
    assertThat(swVersion.toString()).contains(new App.PropertiesVersionProvider().getVersion()[0]);
  }

  @Test
  @DisplayName("should display usage for trace subcommand")
  void shouldDisplayHelpForSubcommand() {
    CommandLine cmd =
        new CommandLine(new App())
            .addSubcommand(new App.Trace())
            .addSubcommand(new App.ListBreakpoints())
            .addSubcommand(new App.ShowLicenses());

    StringWriter sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    int exitCode = cmd.execute("trace", "--help");

    assertThat(exitCode).isEqualTo(0);
    String output = sw.toString();
    assertThat(output).contains("Usage: code-tracer trace");
    assertThat(output).contains("--breakpoints");
    assertThat(output).contains("--inline-strings");
  }

  @Test
  @DisplayName("should register expected subcommands")
  void shouldRegisterSubcommands() {
    CommandLine cmd =
        new CommandLine(new App())
            .addSubcommand(new App.Trace())
            .addSubcommand(new App.ListBreakpoints())
            .addSubcommand(new App.ShowLicenses());

    assertThat(cmd.getSubcommands()).containsKeys("trace", "list-breakpoints", "show-licenses");
  }

  @Test
  @DisplayName("show-licenses subcommand should execute cleanly")
  void shouldExecuteShowLicenses() {
    LicenseHelper helper = new LicenseHelper();
    assertThat(helper).isNotNull();

    PrintStream originalOut = System.out;
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      System.setOut(new PrintStream(baos));
      App.ShowLicenses showLicenses = new App.ShowLicenses();
      CommandLine cmd = new CommandLine(showLicenses);

      StringWriter sw = new StringWriter();
      cmd.setOut(new PrintWriter(sw));

      int exitCode = cmd.execute();
      assertThat(exitCode).isEqualTo(0);
      assertThat(LicenseHelper.APACHE_2_0).isNotEmpty();
      assertThat(LicenseHelper.getThirdPartyNotices()).isNotEmpty();
      assertThat(LicenseHelper.getLicenseText()).contains(LicenseHelper.APACHE_2_0);
      assertThat(baos.toString()).contains("Apache License");
    } finally {
      System.setOut(originalOut);
    }
  }

  @Test
  @DisplayName("should read source from stdin when no input file is supplied")
  void shouldReadFromStdin() {
    String testProgram = "public class Main { public static void main(String[] args) {} }\n";
    InputStream originalIn = System.in;
    try {
      System.setIn(new ByteArrayInputStream(testProgram.getBytes()));
      App.Trace trace = new App.Trace();
      String readSource = trace.readInputFile();
      assertThat(readSource).isEqualTo(testProgram);
    } finally {
      System.setIn(originalIn);
    }
  }

  @Test
  @DisplayName("should throw RuntimeException if input file cannot be read")
  void shouldThrowWhenInputFileInvalid() {
    App.Trace trace = new App.Trace();
    trace.input = new File("/non/existent/path/for/sure.java");
    assertThatThrownBy(trace::readInputFile).isInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("should handle error in Trace.run with verbose=true and verbose=false")
  void shouldHandleTraceErrors() throws IOException {
    File badFile = File.createTempFile("bad", ".java");
    badFile.deleteOnExit();
    Files.writeString(badFile.toPath(), "class BadNoMain {}");

    AtomicInteger exitCode = new AtomicInteger(-1);
    PrintStream originalErr = System.err;

    try {
      System.setErr(new PrintStream(new ByteArrayOutputStream()));

      // Non-verbose
      App.Trace traceNonVerbose = new App.Trace();
      traceNonVerbose.input = badFile;
      traceNonVerbose.exitHandler = exitCode::set;
      traceNonVerbose.verbose = false;
      traceNonVerbose.run();
      assertThat(exitCode.get()).isEqualTo(1);

      // Verbose
      App.Trace traceVerbose = new App.Trace();
      traceVerbose.input = badFile;
      traceVerbose.exitHandler = exitCode::set;
      traceVerbose.verbose = true;
      traceVerbose.run();
      assertThat(exitCode.get()).isEqualTo(1);
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  @DisplayName("should handle error in ListBreakpoints.run with verbose=true and verbose=false")
  void shouldHandleListBreakpointsErrors() throws IOException {
    File badFile = File.createTempFile("bad", ".java");
    badFile.deleteOnExit();
    Files.writeString(badFile.toPath(), "class BadNoMain {}");

    AtomicInteger exitCode = new AtomicInteger(-1);
    PrintStream originalErr = System.err;

    try {
      System.setErr(new PrintStream(new ByteArrayOutputStream()));

      // Non-verbose
      App.ListBreakpoints lbNonVerbose = new App.ListBreakpoints();
      lbNonVerbose.input = badFile;
      lbNonVerbose.exitHandler = exitCode::set;
      lbNonVerbose.verbose = false;
      lbNonVerbose.run();
      assertThat(exitCode.get()).isEqualTo(1);

      // Verbose
      App.ListBreakpoints lbVerbose = new App.ListBreakpoints();
      lbVerbose.input = badFile;
      lbVerbose.exitHandler = exitCode::set;
      lbVerbose.verbose = true;
      lbVerbose.run();
      assertThat(exitCode.get()).isEqualTo(1);
    } finally {
      System.setErr(originalErr);
    }
  }

  @Test
  @DisplayName("should parse source with and without source root")
  void shouldParseSourceCorrectly() {
    App.Trace trace = new App.Trace();
    String source = "public class Main { public static void main(String[] args) {} }";

    CompilationUnit cuWithoutRoot = trace.parseSource(source, Optional.empty());
    assertThat(cuWithoutRoot).isNotNull();

    CompilationUnit cuWithRoot = trace.parseSource(source, Optional.of(Path.of(".")));
    assertThat(cuWithRoot).isNotNull();
  }

  @Test
  @DisplayName("should format breakpoints in ANSI text mode")
  void shouldListBreakpointsInAnsiMode() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            System.out.println("Hello");
          }
        }
        """;

    String output = executeCommand(App.ListBreakpoints::new, testProgram).get();
    assertThat(output).contains("b");
    assertThat(output).contains("System.out.println(\"Hello\");");
  }

  /** Ensure that JSON breakpoints are output correctly. */
  @Test
  public void testJsonBreakpointsCorrect() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            System.out.println("Hello world!");
          }
        }
        """;

    String output = executeCommand(App.ListBreakpoints::new, testProgram, "--json").get();

    String expectedOutput =
        """
        [
          {
            "validBreakpoint": true,
            "lineNumber": 1,
            "lineContent": "public class Main {"
          },
          {
            "validBreakpoint": false,
            "lineNumber": 2,
            "lineContent": "  public static void main(String[] args) {"
          },
          {
            "validBreakpoint": true,
            "lineNumber": 3,
            "lineContent": "    System.out.println(\\"Hello world!\\");"
          },
          {
            "validBreakpoint": true,
            "lineNumber": 4,
            "lineContent": "  }"
          },
          {
            "validBreakpoint": false,
            "lineNumber": 5,
            "lineContent": "}"
          }
        ]
        """;

    JSONAssert.assertEquals(expectedOutput, output, JSONCompareMode.STRICT_ORDER);
  }

  /** Ensure that the traced program's standard output is captured. */
  @Test
  public void testProgramStdoutCaptured() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            System.out.println("Hello world!");
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram).get();

    JSONAssert.assertEquals(
        "{\"trace\":[{\"stdout\":\"Hello world!\\n\"}]}", output, JSONCompareMode.STRICT_ORDER);
  }

  /** Ensure that method variables are detected during tracing and included in the serialization. */
  @Test
  public void testMethodVariablesDetected() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            int a = 0;
            byte b = 0;
            short c = 0;
            long d = 0;
            float e = 0;
            double f = 0;
            boolean g = false;
            char h = '\\0';
            String i = "";
            Object j = new Object();

            // use variables so they aren't optimized out
            System.out.printf("%s%s%s%s%s%s%s%s%s%s", a, b, c, d, e, f, g, h, i, j);
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "-v").get();

    String expectedOutput =
        """
        {
          "trace": [
            {
              "stack_to_render": [
                {
                  "ordered_varnames": [
                    "args",
                    "a",
                    "b",
                    "c",
                    "d",
                    "e",
                    "f",
                    "g",
                    "h",
                    "i",
                    "j"
                  ]
                }
              ]
            }
          ]
        }
        """;

    JSONAssert.assertEquals(expectedOutput, output, JSONCompareMode.STRICT_ORDER);
  }

  @Test
  @DisplayName("should trace with explicit breakpoints and accumulate options")
  void shouldTraceWithBreakpointsAndAccumulate() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            int x = 10;
            x = 20;
          }
        }
        """;

    // Trace single snapshot at breakpoint 4
    String outputSingle =
        executeCommand(
                App.Trace::new,
                testProgram,
                "-b=4",
                "--remove-main-args",
                "--inline-strings",
                "--remove-method-this")
            .get();
    assertThat(outputSingle).contains("\"4\"");

    // Trace accumulated snapshots at breakpoint 4
    String outputAccumulated =
        executeCommand(App.Trace::new, testProgram, "-b=4", "--accumulate-breakpoints").get();
    assertThat(outputAccumulated).contains("\"4\"");
  }

  @Test
  @DisplayName("should trace multi-file package code via input file")
  void shouldTraceMultiFilePackageCode(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
    Path pkgDir = Files.createDirectories(tempDir.resolve("my/app"));
    Path helperPath = pkgDir.resolve("Helper.java");
    Path driverPath = pkgDir.resolve("Driver.java");

    Files.writeString(
        helperPath,
        "package my.app; public class Helper { public static int compute() { return 99; } }");

    String driverCode =
        """
        package my.app;
        public class Driver {
            public static void main(String[] args) {
                int res = Helper.compute();
                System.out.println(res);
            }
        }
        """;
    Files.writeString(driverPath, driverCode);

    App.Trace traceApp = new App.Trace();
    AtomicInteger exitCodeTrace = new AtomicInteger(-1);
    traceApp.exitHandler = exitCodeTrace::set;
    traceApp.verbose = true;

    CommandLine cmdTrace = new CommandLine(traceApp);
    PrintStream originalOut = System.out;
    ByteArrayOutputStream baosTrace = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(baosTrace));
      int exitCode = cmdTrace.execute("-i", driverPath.toString());
      assertThat(exitCodeTrace.get()).isEqualTo(-1);
      assertThat(exitCode).isEqualTo(0);
      assertThat(baosTrace.toString()).contains("\"res\":99");
    } finally {
      System.setOut(originalOut);
    }

    // Also test list-breakpoints on multi-file package
    App.ListBreakpoints bpsApp = new App.ListBreakpoints();
    AtomicInteger exitCodeBps = new AtomicInteger(-1);
    bpsApp.exitHandler = exitCodeBps::set;
    bpsApp.verbose = true;

    CommandLine cmdBps = new CommandLine(bpsApp);
    ByteArrayOutputStream baosBps = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(baosBps));
      int bpsExitCode = cmdBps.execute("-i", driverPath.toString(), "-j");
      assertThat(exitCodeBps.get()).isEqualTo(-1);
      assertThat(bpsExitCode).isEqualTo(0);
      assertThat(baosBps.toString()).contains("\"lineNumber\":4");
    } finally {
      System.setOut(originalOut);
    }
  }

  @Test
  @DisplayName("should trace all breakpoints chronologically without explicit -b flags")
  void shouldTraceAllBreakpointsWithoutBFlags() {
    String testProgram =
        """
        public class LoopMain {
          public static void main(String[] args) {
            int count = 0;
            for (int i = 0; i < 2; i++) {
              count += 1;
            }
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "-a").get();
    assertThat(output).contains("\"trace\":[");
    // Verify that multiple chronological steps exist in the trace array
    assertThat(output).contains("\"count\":0");
    assertThat(output).contains("\"count\":1");
    assertThat(output).contains("\"count\":2");
  }

  @Test
  @DisplayName("should trace all specified breakpoints chronologically with -b flags")
  void shouldTraceAllBreakpointsWithBFlags() {
    String testProgram =
        """
        public class MultiBpMain {
          public static void main(String[] args) {
            int a = 10;
            int b = 20;
            int c = a + b;
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "-b=3", "-b=4", "--all-breakpoints").get();
    assertThat(output).contains("\"trace\":[");
    assertThat(output).contains("\"a\":10");
  }

  @Test
  @DisplayName("should trace multi-file stream provided via stdin chronologically")
  void shouldTraceMultiFileStdinStream() {
    String multiFileStream =
        """
        // --- cs1302/calc/MathOps.java ---
        package cs1302.calc;
        public class MathOps {
            public static int add(int a, int b) {
                return a + b;
            }
        }
        // --- cs1302/calc/Driver.java ---
        package cs1302.calc;
        public class Driver {
            public static void main(String[] args) {
                int res = MathOps.add(3, 4);
                System.out.println(res);
            }
        }
        """;

    String output = executeCommand(App.Trace::new, multiFileStream, "-a").get();
    assertThat(output).contains("\"trace\":[");
    assertThat(output).contains("\"res\":7");
    assertThat(output).contains("// --- cs1302/calc/MathOps.java ---");
    assertThat(output).contains("\"file\":\"cs1302/calc/MathOps.java\"");
    assertThat(output).contains("\"file\":\"cs1302/calc/Driver.java\"");
  }

  @Test
  @DisplayName("should list breakpoints for multi-file stream provided via stdin in json format")
  void shouldListBreakpointsMultiFileStdinStream() {
    String multiFileStream =
        """
        // --- cs1302/calc/MathOps.java ---
        package cs1302.calc;
        public class MathOps {
            public static int add(int a, int b) {
                return a + b;
            }
        }
        // --- cs1302/calc/Driver.java ---
        package cs1302.calc;
        public class Driver {
            public static void main(String[] args) {
                int res = MathOps.add(3, 4);
            }
        }
        """;

    String output = executeCommand(App.ListBreakpoints::new, multiFileStream, "-j").get();
    assertThat(output).contains("\"lineNumber\":");
    assertThat(output).contains("\"validBreakpoint\":");
    assertThat(output).contains("\"file\":\"cs1302/calc/MathOps.java\"");
    assertThat(output).contains("\"file\":\"cs1302/calc/Driver.java\"");
  }

  @Test
  @DisplayName("should list breakpoints for multi-file stream provided via stdin in text format")
  void shouldListBreakpointsMultiFileStdinStreamText() {
    String multiFileStream =
        """
        // --- cs1302/calc/MathOps.java ---
        package cs1302.calc;
        public class MathOps {
            public static int add(int a, int b) {
                return a + b;
            }
        }
        // --- cs1302/calc/Driver.java ---
        package cs1302.calc;
        public class Driver {
            public static void main(String[] args) {
                int res = MathOps.add(3, 4);
            }
        }
        """;

    String output = executeCommand(App.ListBreakpoints::new, multiFileStream).get();
    assertThat(output).contains("// --- cs1302/calc/MathOps.java ---");
    assertThat(output).contains("// --- cs1302/calc/Driver.java ---");
    assertThat(output).contains("b");
  }

  @Test
  @DisplayName("should trace with --format=modern for single snapshot")
  void shouldTraceModernSingleSnapshot() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            int x = 42;
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "-f=modern").get();
    assertThat(output).contains("\"format\": \"modern\"");
    assertThat(output).contains("\"steps\":");
    assertThat(output).contains("\"name\": \"x\"");
    assertThat(output).contains("\"value\": 42");
  }

  @Test
  @DisplayName("should trace with --format=modern and -a for chronological execution")
  void shouldTraceModernChronological() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            int x = 10;
            int y = 20;
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "--format=modern", "-a").get();
    assertThat(output).contains("\"format\": \"modern\"");
    assertThat(output).contains("\"steps\":");
    assertThat(output).contains("\"step\": 1");
  }

  @Test
  @DisplayName("should trace with --format=modern and explicit breakpoints")
  void shouldTraceModernBreakpoints() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            int x = 10;
            x = 20;
          }
        }
        """;

    String outputSingle =
        executeCommand(App.Trace::new, testProgram, "-f", "modern", "-b=4").get();
    assertThat(outputSingle).contains("\"format\": \"modern\"");
    assertThat(outputSingle).contains("\"breakpoints\":");
    assertThat(outputSingle).contains("\"4\":");

    String outputAccum =
        executeCommand(
                App.Trace::new,
                testProgram,
                "-f=modern",
                "-b=4",
                "--accumulate-breakpoints")
            .get();
    assertThat(outputAccum).contains("\"format\": \"modern\"");
    assertThat(outputAccum).contains("\"breakpoints\":");
  }

  @Test
  @DisplayName("should trace with --type-style=simple")
  void shouldTraceWithTypeStyleSimple() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            String msg = "hello";
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "--type-style=simple").get();
    assertThat(output).contains("\"type\":\"String\"");
    assertThat(output).doesNotContain("\"type\":\"java.lang.String\"");
  }

  @Test
  @DisplayName("should trace with --type-style=fqn")
  void shouldTraceWithTypeStyleFqn() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            String msg = "hello";
          }
        }
        """;

    String output = executeCommand(App.Trace::new, testProgram, "--type-style=fqn").get();
    assertThat(output).contains("\"type\":\"java.lang.String\"");
  }

  @Test
  @DisplayName("should trace modern format with --type-style=simple")
  void shouldTraceModernWithTypeStyleSimple() {
    String testProgram =
        """
        public class Main {
          public static void main(String[] args) {
            String msg = "hello";
          }
        }
        """;

    String output =
        executeCommand(App.Trace::new, testProgram, "-f=modern", "--type-style=simple").get();
    assertThat(output).contains("\"type\": \"String\"");
    assertThat(output).doesNotContain("\"type\": \"java.lang.String\"");
  }
}
