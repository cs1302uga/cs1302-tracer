package cs1302.tracer;

import cs1302.tracer.App.CommandBase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import picocli.CommandLine;

/** Tests for cs1302-tracer. */
public class AppTest {

  /**
   * Execute a tracer command with a program input and command-line arguments, and get the command's
   * standard output.
   *
   * @param commandSupplier A supplier for the command you want to run.
   * @param testProgram A string containing the Java input you want to give to the command.
   * @param options The command-line arguments you want to pass to the command. Do not include
   *     -i/--input.
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
      CommandLine cmd = new CommandLine(app);

      ArrayList<String> args = new ArrayList<>();
      args.addAll(Arrays.asList(options));
      args.add("--input=" + tempFile.getCanonicalPath());

      // NOTE: actually setting the JVM output stream is the only way to do this as far as I know.
      // picocli's setOut/setErr only set the stdout/stderr that picocli uses internally when
      // printing
      // usage and version information. it does not capture general command output with
      // system.out.println and friends.
      // WARNING: this is thread-unsafe and requires that tests are run
      // sequentially, which is the default for junit. this might also mess with stack trace
      // printing,
      // but I think that is unlikely.
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
            char h = '\0';
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
}
