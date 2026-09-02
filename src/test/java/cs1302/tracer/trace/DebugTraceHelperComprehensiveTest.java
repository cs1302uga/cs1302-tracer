package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import cs1302.tracer.App;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.CompilationHelper.CompilationResult;
import cs1302.tracer.LicenseHelper;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DebugTraceHelper Comprehensive JDI Tracing")
public class DebugTraceHelperComprehensiveTest {

  @Test
  @DisplayName("LicenseHelper constructor coverage")
  void testLicenseHelperConstructor() {
    LicenseHelper helper = new LicenseHelper();
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
}
