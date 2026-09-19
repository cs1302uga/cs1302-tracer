package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.App;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.LicenseHelper;
import cs1302.tracer.execution.TraceSession;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DebugTraceHelper Comprehensive JDI Tracing")
public class DebugTraceHelperComprehensiveTest {

  @Test
  @DisplayName("LicenseHelper constructor coverage")
  void testLicenseHelperConstructor() throws Exception {
    java.lang.reflect.Constructor<LicenseHelper> constructor =
        LicenseHelper.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    LicenseHelper helper = constructor.newInstance();
    assertThat(helper).isNotNull();
  }

  @Test
  @DisplayName("DebugTraceHelper constructor coverage")
  void testDebugTraceHelperConstructor() throws Exception {
    java.lang.reflect.Constructor<DebugTraceHelper> constructor =
        DebugTraceHelper.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    DebugTraceHelper instance = constructor.newInstance();
    assertThat(instance).isNotNull();
  }

  @Test
  @DisplayName("should trace rich guest program with all primitive arrays, lambdas, instance methods, and maps")
  void shouldTraceComplexProgram() throws Exception {
    String source =
        """
        package test;
        import java.util.*;
        import java.util.function.*;

        public class ComplexGuest {
            public static int STATIC_INT = 100;
            public static String STATIC_STR = "static_hello";
            public static Object STATIC_NULL = null;
            public static Supplier<String> STATIC_LAMBDA = () -> "from_static_lambda";
            public static Consumer<String> STATIC_VOID_LAMBDA = msg -> { System.out.println(msg); };

            static class Node {
                int val;
                Node next;
                String label;
                Node(int val, Node next, String label) {
                    this.val = val;
                    this.next = next;
                    this.label = label;
                }

                public int calculate(int multiplier) {
                    String nullLocalInInstanceMethod = null;
                    int result = this.val * multiplier;
                    return result;
                }
            }

            public static void main(String[] args) {
                // Null local variable
                String nullLocalVar = null;
                Object nullObjVar = null;

                // All primitive arrays
                boolean[] boolArr = new boolean[] { true, false };
                byte[] byteArr = new byte[] { 1, 2 };
                char[] charArr = new char[] { 'x', 'y' };
                short[] shortArr = new short[] { 3, 4 };
                int[] intArr = new int[] { 10, 20 };
                long[] longArr = new long[] { 100L, 200L };
                float[] floatArr = new float[] { 1.5f, 2.5f };
                double[] doubleArr = new double[] { 3.5, 4.5 };
                String[] objArr = new String[] { "a", "b" };

                // Boxed primitives
                Boolean boxedBool = Boolean.TRUE;
                Byte boxedByte = (byte) 1;
                Character boxedChar = 'c';
                Short boxedShort = (short) 2;
                Integer boxedInt = 3;
                Long boxedLong = 4L;
                Float boxedFloat = 5.0f;
                Double boxedDouble = 6.0;

                // Builtin types
                Date now = new Date();
                Random rnd = new Random(42);

                // Collections and Maps
                List<String> list = new ArrayList<>();
                list.add("one");
                list.add("two");

                Set<Integer> set = new HashSet<>();
                set.add(1);
                set.add(2);

                Map<String, Integer> map = new HashMap<>();
                map.put("key1", 10);
                map.put("key2", 20);

                Node chain = new Node(5, new Node(10, null, null), "root");
                int nodeCalc = chain.calculate(3);

                // Various lambda kinds
                Supplier<Integer> lambda0 = () -> 42;
                Function<Integer, String> lambda1 = (Integer x) -> String.valueOf(x);
                Consumer<String> lambda2 = (String s) -> { System.out.println(s); };
                BiFunction<Integer, String, Boolean> lambda3 = (a, b) -> a > b.length();
                Runnable lambda4 = () -> { System.out.println("run"); };
                Function<Integer, Integer> reassignLambda = x -> x * 2;
                reassignLambda = x -> x * x;

                System.out.println("Standard out message");
                System.err.println("Standard err message");

                int recResult = recursiveFactorial(3);
                helperMethod(chain, list);
            }

            public static int recursiveFactorial(int n) {
                if (n <= 1) {
                    return 1;
                }
                return n * recursiveFactorial(n - 1);
            }

            public static int helperMethod(Node node, List<String> list) {
                int sum = node.val;
                return sum + list.size();
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var combinedTypeSolver =
          new com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver();
      combinedTypeSolver.add(
          new com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver());
      combinedTypeSolver.add(
          new com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver(
              cr.classPath()));
      var symbolSolver =
          new com.github.javaparser.symbolsolver.JavaSymbolSolver(combinedTypeSolver);
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setSymbolResolver(symbolSolver)
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      // Test valid breakpoint discovery
      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      assertThat(validBreakpoints).isNotEmpty();

      // Test trace at main return
      ExecutionSnapshot endSnapshot = DebugTraceHelper.trace(cr, cu);
      assertThat(endSnapshot).isNotNull();
      assertThat(endSnapshot.stack()).isNotEmpty();
      assertThat(endSnapshot.statics()).isNotEmpty();
      assertThat(new String(endSnapshot.stdout())).contains("Standard out message");
      assertThat(new String(endSnapshot.stderr())).contains("Standard err message");

      // Test trace at explicit breakpoints (including breakpoint inside instance method)
      Map<Integer, List<ExecutionSnapshot>> bpSnapshots =
          DebugTraceHelper.trace(cr, validBreakpoints, cu);
      assertThat(bpSnapshots).isNotEmpty();

      // Test traceChronological with all breakpoints and main exit
      List<ExecutionSnapshot> chronologicalSnapshotsWithMain =
          DebugTraceHelper.traceChronological(cr, validBreakpoints, cu, true);
      assertThat(chronologicalSnapshotsWithMain).isNotEmpty();
      assertThat(chronologicalSnapshotsWithMain.size()).isGreaterThanOrEqualTo(validBreakpoints.size());

      // Test traceChronological with null breakpoints and without main exit
      List<ExecutionSnapshot> chronologicalSnapshotsNullBps =
          DebugTraceHelper.traceChronological(cr, null, cu, false);
      assertThat(chronologicalSnapshotsNullBps).isEmpty();
    }
  }

  @Test
  @DisplayName("should capture unbuffered stdout and stderr accurately at each step")
  void shouldCaptureStdoutAndStderrAccuratelyAtEachStep() throws Exception {
    String source =
        """
        public class StepIoTest {
            public static void main(String[] args) {
                System.out.print("A");
                System.out.print("B");
                System.err.print("E1");
                System.out.println("C");
                System.err.println("E2");
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      List<Integer> sortedBreakpoints = validBreakpoints.stream().sorted().toList();

      List<ExecutionSnapshot> chronological =
          DebugTraceHelper.traceChronological(cr, sortedBreakpoints, cu, true);

      // There should be at least 6 snapshots (5 breakpoints + 1 main exit)
      assertThat(chronological.size()).isGreaterThanOrEqualTo(6);

      // Step 0: before line 3 executes (System.out.print("A"))
      assertThat(new String(chronological.get(0).stdout())).isEmpty();
      assertThat(new String(chronological.get(0).stderr())).isEmpty();

      // Step 1: after line 3 executed, before line 4 (System.out.print("B"))
      assertThat(new String(chronological.get(1).stdout())).isEqualTo("A");
      assertThat(new String(chronological.get(1).stderr())).isEmpty();

      // Step 2: after line 4 executed, before line 5 (System.err.print("E1"))
      assertThat(new String(chronological.get(2).stdout())).isEqualTo("AB");
      assertThat(new String(chronological.get(2).stderr())).isEmpty();

      // Step 3: after line 5 executed, before line 6 (System.out.println("C"))
      assertThat(new String(chronological.get(3).stdout())).isEqualTo("AB");
      assertThat(new String(chronological.get(3).stderr())).isEqualTo("E1");

      // Step 4: after line 6 executed, before line 7 (System.err.println("E2"))
      assertThat(new String(chronological.get(4).stdout())).isEqualTo("ABC\n");
      assertThat(new String(chronological.get(4).stderr())).isEqualTo("E1");

      // Final step: main exit
      ExecutionSnapshot finalSnapshot = chronological.get(chronological.size() - 1);
      assertThat(new String(finalSnapshot.stdout())).isEqualTo("ABC\n");
      assertThat(new String(finalSnapshot.stderr())).isEqualTo("E1E2\n");
    }
  }

  @Test
  @DisplayName("should capture large output bursts in loops without truncation or race conditions")
  void shouldCaptureLargeOutputBurstsAndLoopsAccurately() throws Exception {
    String source =
        """
        public class LoopIoTest {
            public static void main(String[] args) {
                for (int i = 0; i < 20; i++) {
                    System.out.print("item" + i + " ");
                }
                System.out.println("done");
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      List<ExecutionSnapshot> chronological =
          DebugTraceHelper.traceChronological(cr, validBreakpoints, cu, true);

      assertThat(chronological).isNotEmpty();
      ExecutionSnapshot finalSnapshot = chronological.get(chronological.size() - 1);
      String out = new String(finalSnapshot.stdout());
      for (int i = 0; i < 20; i++) {
        assertThat(out).contains("item" + i + " ");
      }
      assertThat(out).contains("done\n");
    }
  }

  @Test
  @DisplayName("should capture uncaught exception standard error in chronological trace")
  void shouldCaptureUncaughtExceptionStderrChronological() throws Exception {
    String source =
        """
        public class CrashTest {
            public static void main(String[] args) {
                System.out.println("Beginning execution");
                int val = divide(10, 0);
                System.out.println("Unreachable: " + val);
            }
            public static int divide(int a, int b) {
                return a / b;
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      List<ExecutionSnapshot> chronological =
          DebugTraceHelper.traceChronological(cr, validBreakpoints, cu, true);

      assertThat(chronological).isNotEmpty();
      ExecutionSnapshot finalSnapshot = chronological.get(chronological.size() - 1);
      String err = new String(finalSnapshot.stderr());
      assertThat(err).contains("java.lang.ArithmeticException: / by zero");
      assertThat(err).contains("CrashTest.divide");
      assertThat(err).contains("CrashTest.main");

      String out = new String(finalSnapshot.stdout());
      assertThat(out).contains("Beginning execution\n");
      assertThat(out).doesNotContain("Unreachable");
    }
  }

  @Test
  @DisplayName("should capture uncaught exception standard error in single snapshot trace")
  void shouldCaptureUncaughtExceptionStderrSingleSnapshot() throws Exception {
    String source =
        """
        public class CrashSingleTest {
            public static void main(String[] args) {
                int val = 10 / 0;
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      ExecutionSnapshot snapshot = DebugTraceHelper.trace(cr, cu);
      assertThat(snapshot).isNotNull();
      String err = new String(snapshot.stderr());
      assertThat(err).contains("java.lang.ArithmeticException: / by zero");
      assertThat(err).contains("CrashSingleTest.main");
    }
  }

  @Test
  @DisplayName("should trace java.awt.Color objects with hex values and transparency")
  void shouldTraceColorObjects() throws Exception {
    String source =
        """
        import java.awt.Color;

        public class ColorTest {
            public static void main(String[] args) {
                Color opaque = Color.RED;
                Color translucent = new Color(0, 255, 0, 128);
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      ExecutionSnapshot snapshot = DebugTraceHelper.trace(cr, cu);
      assertThat(snapshot).isNotNull();
      assertThat(snapshot.heap()).isNotEmpty();

      boolean foundOpaque = false;
      boolean foundTranslucent = false;
      for (TraceValue tv : snapshot.heap().values()) {
        if (tv instanceof TraceValue.Color c) {
          if ("#FF0000".equals(c.hex())) {
            foundOpaque = true;
          } else if ("#00FF0080".equals(c.hex())) {
            foundTranslucent = true;
          } // if
        } // if
      } // for
      assertThat(foundOpaque).isTrue();
      assertThat(foundTranslucent).isTrue();
    }
  }

  @Test
  @DisplayName("sanitizeDebuggeeStderr removes JVM tool options banners")
  void testSanitizeDebuggeeStderr() {
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(null)).isNull();
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(new byte[0])).isEmpty();

    byte[] clean = "Normal error output\n".getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(clean)).isEqualTo(clean);

    byte[] withToolOptions =
        "Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=true\nActual error\n"
            .getBytes(StandardCharsets.UTF_8);
    byte[] expected = "Actual error\n".getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(withToolOptions)).isEqualTo(expected);

    byte[] onlyToolOptions =
        "Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=true\n"
            .getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(onlyToolOptions)).isEmpty();

    byte[] withJavaOptions =
        "Picked up _JAVA_OPTIONS: -Dsome.prop=1\nAnother error"
            .getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(withJavaOptions))
        .isEqualTo("Another error".getBytes(StandardCharsets.UTF_8));

    byte[] multipleBanners =
        ("Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=true\n"
            + "Picked up _JAVA_OPTIONS: -Xmx512m\n"
            + "Message\n")
            .getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(multipleBanners))
        .isEqualTo("Message\n".getBytes(StandardCharsets.UTF_8));

    byte[] bannerWithoutNewline =
        "Picked up JAVA_TOOL_OPTIONS: -Djava.awt.headless=true"
            .getBytes(StandardCharsets.UTF_8);
    assertThat(DebugTraceHelper.sanitizeDebuggeeStderr(bannerWithoutNewline)).isEmpty();
  }

  @Test
  @DisplayName("isRedundantSnapshot verifies identity, null, and all execution snapshot attributes")
  void testIsRedundantSnapshot() {
    ExecutionSnapshot.StackSnapshot frame1 =
        new ExecutionSnapshot.StackSnapshot(
            "main", 10, List.of(), Optional.empty(), Optional.of("A.java"));
    ExecutionSnapshot s1 =
        new ExecutionSnapshot(
            List.of(frame1),
            List.of(),
            Map.of(),
            new byte[] {65},
            new byte[] {66},
            Optional.of("A.java"),
            "input",
            5);

    // Identity check
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, s1)).isTrue();

    // Null checks
    assertThat(DebugTraceHelper.isRedundantSnapshot(null, s1)).isFalse();
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, null)).isFalse();
    assertThat(DebugTraceHelper.isRedundantSnapshot(null, null)).isTrue();

    // Equal snapshot with distinct byte array instances
    ExecutionSnapshot s2 =
        new ExecutionSnapshot(
            List.of(frame1),
            List.of(),
            Map.of(),
            new byte[] {65},
            new byte[] {66},
            Optional.of("A.java"),
            "input",
            5);
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, s2)).isTrue();

    // Differing sourcePath
    ExecutionSnapshot diffSource =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            s1.heap(),
            s1.stdout(),
            s1.stderr(),
            Optional.of("B.java"),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffSource)).isFalse();

    // Differing stack
    ExecutionSnapshot.StackSnapshot frame2 =
        new ExecutionSnapshot.StackSnapshot(
            "main", 11, List.of(), Optional.empty(), Optional.of("A.java"));
    ExecutionSnapshot diffStack =
        new ExecutionSnapshot(
            List.of(frame2),
            s1.statics(),
            s1.heap(),
            s1.stdout(),
            s1.stderr(),
            s1.sourcePath(),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStack)).isFalse();

    // Differing statics
    ExecutionSnapshot.Field field =
        new ExecutionSnapshot.Field(false, "int", "x", new TraceValue.Primitive.Integer(1));
    ExecutionSnapshot diffStatics =
        new ExecutionSnapshot(
            s1.stack(),
            List.of(field),
            s1.heap(),
            s1.stdout(),
            s1.stderr(),
            s1.sourcePath(),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStatics)).isFalse();

    // Differing heap
    ExecutionSnapshot diffHeap =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            Map.of(1L, new TraceValue.Primitive.Integer(1)),
            s1.stdout(),
            s1.stderr(),
            s1.sourcePath(),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffHeap)).isFalse();

    // Differing stdout
    ExecutionSnapshot diffStdout =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            s1.heap(),
            new byte[] {99},
            s1.stderr(),
            s1.sourcePath(),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStdout)).isFalse();

    // Differing stderr
    ExecutionSnapshot diffStderr =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            s1.heap(),
            s1.stdout(),
            new byte[] {99},
            s1.sourcePath(),
            s1.stdinConsumed(),
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStderr)).isFalse();

    // Differing stdinConsumed
    ExecutionSnapshot diffStdinConsumed =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            s1.heap(),
            s1.stdout(),
            s1.stderr(),
            s1.sourcePath(),
            "different",
            s1.stdinOffset());
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStdinConsumed)).isFalse();

    // Differing stdinOffset
    ExecutionSnapshot diffStdinOffset =
        new ExecutionSnapshot(
            s1.stack(),
            s1.statics(),
            s1.heap(),
            s1.stdout(),
            s1.stderr(),
            s1.sourcePath(),
            s1.stdinConsumed(),
            10);
    assertThat(DebugTraceHelper.isRedundantSnapshot(s1, diffStdinOffset)).isFalse();
  }

  @Test
  @DisplayName("deduplicates snapshot when breakpoint is placed on return or closing line of main")
  void testDeduplicateMainClosingLineBreakpoint() throws Exception {
    String source =
        """
        package test;

        public class ClosingLineDriver {
            public static void main(String[] args) {
                int x = 10;
                System.out.println(x);
            } // main
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validLines = DebugTraceHelper.getValidBreakpointLines(cr);
      int maxLine = validLines.stream().mapToInt(Integer::intValue).max().orElseThrow();

      // Case 1: Breakpoint explicitly set on closing brace line of main
      List<ExecutionSnapshot> closingOnlySnapshots =
          DebugTraceHelper.traceChronological(cr, List.of(maxLine), List.of(cu), true, "");
      long countAtMaxLine =
          closingOnlySnapshots.stream()
              .filter(s -> !s.stack().isEmpty() && s.stack().getLast().methodLine() == maxLine)
              .count();
      assertThat(countAtMaxLine).isEqualTo(1);

      // Case 2: Breakpoints set on all valid lines (including closing brace)
      List<ExecutionSnapshot> allSnapshots =
          DebugTraceHelper.traceChronological(cr, validLines, List.of(cu), true, "");
      long countAllAtMaxLine =
          allSnapshots.stream()
              .filter(s -> !s.stack().isEmpty() && s.stack().getLast().methodLine() == maxLine)
              .count();
      assertThat(countAllAtMaxLine).isEqualTo(1);

      // Case 3: Breakpoint set on intermediate line only (line 5)
      // The method exit snapshot at maxLine should be preserved since line 5 != maxLine
      List<ExecutionSnapshot> intermediateSnapshots =
          DebugTraceHelper.traceChronological(cr, List.of(5), List.of(cu), true, "");
      assertThat(intermediateSnapshots).hasSize(2);
      assertThat(intermediateSnapshots.get(0).stack().getLast().methodLine()).isEqualTo(5);
      assertThat(intermediateSnapshots.get(1).stack().getLast().methodLine()).isEqualTo(maxLine);
    }
  }

  @Test
  @DisplayName("should trace enums with standard and specialized class body constants")
  void shouldTraceEnumsWithStandardAndSpecializedBodies() throws Exception {
    String source =
        """
        package test;

        public class EnumTestGuest {
            enum Mode {
                STANDARD,
                SPECIAL {
                    @Override
                    public String toString() {
                        return "special";
                    }
                }
            }

            public static void main(String[] args) {
                Mode m1 = Mode.STANDARD;
                Mode m2 = Mode.SPECIAL;
                System.out.println(m1 + " " + m2);
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source)) {
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      List<ExecutionSnapshot> chronological =
          DebugTraceHelper.traceChronological(cr, validBreakpoints, cu, true);

      assertThat(chronological).isNotEmpty();
      ExecutionSnapshot finalSnapshot = chronological.get(chronological.size() - 1);
      List<TraceValue.Object> enumObjects =
          finalSnapshot.heap().values().stream()
              .filter(tv -> tv instanceof TraceValue.Object)
              .map(tv -> (TraceValue.Object) tv)
              .filter(obj -> obj.enumConstant().isPresent())
              .toList();
      assertThat(enumObjects).extracting(TraceValue.Object::classFqn)
          .contains("test.EnumTestGuest$Mode");
      assertThat(enumObjects).extracting(obj -> obj.enumConstant().get())
          .contains("STANDARD", "SPECIAL");
      assertThat(enumObjects).isNotEmpty();
      for (TraceValue.Object enumObj : enumObjects) {
        ExecutionSnapshot.Field hashField = enumObj.fields().stream()
            .filter(f -> "hash".equals(f.identifier()))
            .findFirst()
            .orElse(null);
        assertThat(hashField).isNotNull();
        assertThat(hashField.value()).isInstanceOf(TraceValue.Primitive.Integer.class);
        assertThat(((TraceValue.Primitive.Integer) hashField.value()).value()).isNotEqualTo(0);
      }
    }
  }

  @Test
  @DisplayName("should not evaluate enum hash when disabled")
  void shouldNotEvaluateEnumHashWhenDisabled() throws Exception {
    String source =
        """
        package test;

        public class EnumHashDisabledGuest {
            enum Color { RED, GREEN }

            public static void main(String[] args) {
                Color c = Color.RED;
                System.out.println(c);
            }
        }
        """;

    try (CompilationResult cr = CompilationHelper.compile(source);
         AutoCloseable scope = TraceSession.withEvalEnumHash(false)) {
      assertThat(scope).isNotNull();
      var config =
          new com.github.javaparser.ParserConfiguration()
              .setLanguageLevel(
                  com.github.javaparser.ParserConfiguration.LanguageLevel.CURRENT);
      CompilationUnit cu =
          new com.github.javaparser.JavaParser(config).parse(source).getResult().get();

      Collection<Integer> validBreakpoints = DebugTraceHelper.getValidBreakpointLines(cr);
      List<ExecutionSnapshot> chronological =
          DebugTraceHelper.traceChronological(cr, validBreakpoints, cu, true);

      assertThat(chronological).isNotEmpty();
      ExecutionSnapshot finalSnapshot = chronological.get(chronological.size() - 1);
      List<TraceValue.Object> enumObjects =
          finalSnapshot.heap().values().stream()
              .filter(tv -> tv instanceof TraceValue.Object)
              .map(tv -> (TraceValue.Object) tv)
              .filter(obj -> obj.enumConstant().isPresent())
              .toList();
      assertThat(enumObjects).isNotEmpty();
      for (TraceValue.Object enumObj : enumObjects) {
        ExecutionSnapshot.Field hashField = enumObj.fields().stream()
            .filter(f -> "hash".equals(f.identifier()))
            .findFirst()
            .orElse(null);
        assertThat(hashField).isNotNull();
        assertThat(hashField.value()).isInstanceOf(TraceValue.Primitive.Integer.class);
        assertThat(((TraceValue.Primitive.Integer) hashField.value()).value()).isEqualTo(0);
      }
    }
  }
  @Test
  @DisplayName("isGuestHarnessOrReflect correctly identifies harness and reflection classes")
  void testIsGuestHarnessOrReflect() {
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("cs1302.tracer.guest.GuestHarness")).isTrue();
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("cs1302.tracer.guest.GuestHarness$Inner")).isTrue();
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("jdk.internal.reflect.NativeMethodAccessorImpl")).isTrue();
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("java.lang.reflect.Method")).isTrue();
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("cs1302.tracer.guest.OtherClass")).isFalse();
    assertThat(DebugTraceHelper.isGuestHarnessOrReflect("Student")).isFalse();
  }
}
