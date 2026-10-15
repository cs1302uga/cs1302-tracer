package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static cs1302.tracer.trace.JdiValueContractTest.mirror;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Location;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import cs1302.tracer.App;
import cs1302.tracer.CompilationHelper;
import cs1302.tracer.model.TypeStyle;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Compiler-backed checks of the current resolver and isolated structural prototype. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class GenericInferenceAssessmentTest {
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();

    @TestFactory
    Stream<DynamicTest> corpus() {
        return GenericInferenceCorpus.cases().stream()
                .map(example -> DynamicTest.dynamicTest(example.name(), () -> assess(example)));
    }

    private void assess(GenericInferenceCorpus.Case example) throws Exception {
        String source = example.source();
        String oracle = oracle(source);
        if (example.oracleType().contains("capture#")) {
            assertThat(oracle).startsWith(example.oracleType());
        } else {
            assertThat(oracle).isEqualTo(example.oracleType());
        }
        var unit = App.parseSource(source, Optional.empty());
        var resolver = new AstTypeResolver(unit).invocationTypes();
        var call = unit.findAll(MethodCallExpr.class).stream().filter(c -> c.getNameAsString().equals("helper"))
                .findFirst().orElseThrow();
        var declaration = unit.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals("helper")).findFirst().orElseThrow().resolve();
        String descriptor = "(";
        for (int i = 0; i < declaration.getNumberOfParams(); i++) {
            descriptor += declaration.getParam(i).getType().erasure().toDescriptor();
        }
        descriptor += ")Ljava/lang/Object;";
        Method callee = method("helper", descriptor);
        var caller = mirror(StackFrame.class, Map.of("location", mirror(Location.class, Map.of("method",
                method("main", "([Ljava/lang/String;)V"), "lineNumber", call.getBegin().orElseThrow().line))));
        var current = resolver.invocationBindings(caller, callee, Map.of());
        var prototype = GenericInferencePrototype.infer(call);
        assertThat(current.getOrDefault("T", "")).isEqualTo(currentExpected(example));
        assertThat(prototype.getOrDefault("T", "")).isEqualTo(example.prototype());
        // Oracle equality is required for EVERY specialization, including existing controls.
        for (var bindings : List.of(current, prototype)) {
            if (bindings.containsKey("T")) {
                assertThat(bindings.get("T")).isEqualTo(oracle);
            }
        }
        // Warm each path before a bounded, descriptive (not pass/fail) cost sample.
        for (int i = 0; i < 10; i++) {
            resolver.invocationBindings(caller, callee, Map.of());
            GenericInferencePrototype.infer(call);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            resolver.invocationBindings(caller, callee, Map.of());
        }
        long currentNanos = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            GenericInferencePrototype.infer(call);
        }
        long prototypeNanos = System.nanoTime() - start;
        long traceStart = System.nanoTime();
        verifyCurrentTrace(example);
        RESULTS.add(Map.of("case", example.name(), "javac", oracle, "current", current, "prototype", prototype,
                "current100Nanos", currentNanos, "prototype100Nanos", prototypeNanos, "currentCompileAndTraceNanos",
                System.nanoTime() - traceStart));
    }

    private static String currentExpected(GenericInferenceCorpus.Case example) {
        return switch (example.name()) {
        case "literal", "creation", "non generic call" -> example.oracleType();
        default -> example.baseline();
        };
    }

    private static Method method(String name, String descriptor) {
        return mirror(Method.class, Map.of("name", name, "signature", descriptor, "declaringType",
                mirror(ReferenceType.class, Map.of("name", "Main"))));
    }

    /** Public compiler APIs only; analysis does not execute the fixture or emit class files. */
    static String oracle(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Main.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return source;
            }
        };
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, List.of(file));
            var units = task.parse();
            task.analyze();
            assertThat(diagnostics.getDiagnostics()).noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
            var trees = Trees.instance(task);
            var types = new ArrayList<String>();
            for (var unit : units) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String name = node.getMethodSelect().toString();
                        if (name.equals("helper") || name.endsWith(".helper")) {
                            types.add(trees.getTypeMirror(getCurrentPath()).toString());
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }.scan(unit, null);
            }
            assertThat(types).hasSize(1);
            return types.getFirst();
        }
    }

    private static void verifyCurrentTrace(GenericInferenceCorpus.Case example) throws Exception {
        String source = example.source();
        int line = source.substring(0, source.indexOf("// capture")).split("\n", -1).length;
        try (var session = new cs1302.tracer.execution.TraceSession(
                cs1302.tracer.execution.TraceLimits.instructorDefaults(),
                cs1302.tracer.execution.InspectionPolicy.FIELDS, true);
                var compiled = CompilationHelper.compile(source)) {
            assertThat(session).isNotNull();
            var snapshots = DebugTraceHelper
                    .trace(compiled, List.of(line), App.parseSource(source, Optional.empty()), "").get(line);
            assertThat(snapshots).hasSize(1);
            var snapshot = snapshots.getFirst();
            String binding = currentExpected(example);
            String expected = binding.isEmpty() ? "T" : binding;
            verifyFormats(source, snapshot, expected);
            if (!example.prototype().isEmpty()) {
                // Serializer-only check: synthetic relabeling is NOT resolver integration.
                var stack = snapshot.stack().stream()
                        .map(frame -> new ExecutionSnapshot.StackSnapshot(frame.methodName(), frame.methodLine(), frame
                                .visibleVariables().stream()
                                .map(field -> frame.methodName().equals("helper") && field.identifier().equals("local")
                                        ? new ExecutionSnapshot.Field(field.isFinal(), example.prototype(),
                                                field.identifier(), field.value())
                                        : field)
                                .toList(), frame.thisObject(), frame.sourcePath()))
                        .toList();
                var synthetic = new ExecutionSnapshot(stack, snapshot.statics(), snapshot.heap(),
                        snapshot.stdoutSlice(), snapshot.stderrSlice(), snapshot.sourcePath(), snapshot.stdinConsumed(),
                        snapshot.stdinOffset());
                verifyFormats(source, synthetic, example.prototype());
            }
        }
    }

    static void verifyFormats(String source, ExecutionSnapshot snapshot, String expected) {
        for (TypeStyle style : TypeStyle.values()) {
            var modern = new ModernTraceSerializer(false, false, false, style).createTrace(source, snapshot);
            var frame = modern.steps().getFirst().callStack().stream().filter(f -> f.methodName().equals("helper"))
                    .findFirst().orElseThrow();
            assertThat(frame.locals()).filteredOn(v -> v.name().equals("local")).extracting(v -> v.type())
                    .containsExactly(style.format(expected));
            var pytutor = new PyTutorSerializer(false, false, false, style).createTrace(source, snapshot);
            var frames = PyTutorSerializer.getGson().toJsonTree(pytutor).getAsJsonObject().getAsJsonArray("trace")
                    .get(0).getAsJsonObject().getAsJsonArray("stack_to_render");
            boolean found = false;
            for (var element : frames) {
                var f = element.getAsJsonObject();
                if (f.get("func_name").getAsString().startsWith("helper:")) {
                    found = true;
                    assertThat(f.getAsJsonObject("locals_attrs").getAsJsonObject("local").get("type").getAsString())
                            .isEqualTo(style.format(expected));
                }
            }
            assertThat(found).isTrue();
        }
    }

    @Test
    void boundsAndCallerTypeVariablesRemainOutsideThePrototype() throws Exception {
        String bounded = GenericInferenceCorpus.cases().get(1).source().replace("static <T>",
                "static <T extends Number>");
        assertThat(oracle(bounded)).isEqualTo("java.lang.Number");
        assertThat(prototype(bounded)).isEmpty();
        String forwarded = """
                class Main<T> {
                    T field;
                    static <U> U helper(U item) { return item; }
                    <T> void forward(T item) { helper(this.field); }
                }
                """;
        assertThat(oracle(forwarded)).isEqualTo("T");
        assertThat(prototype(forwarded)).isEmpty();
        String several = GenericInferenceCorpus.cases().get(11).source().replace("static <T>", "static <T, U>")
                .replace("T a, T b", "T a, U b");
        assertThat(oracle(several)).isEqualTo("java.lang.String");
        assertThat(prototype(several)).isEmpty();
    }

    @Test
    void separateJobsAndThreadsDoNotSharePrototypeBindings() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int i = 0; i < 12; i++) {
                int index = i % 2;
                tasks.add(() -> {
                    var example = GenericInferenceCorpus.cases().get(index);
                    var unit = App.parseSource(example.source(), Optional.empty());
                    var call = unit.findAll(MethodCallExpr.class).stream()
                            .filter(c -> c.getNameAsString().equals("helper")).findFirst().orElseThrow();
                    for (int repeat = 0; repeat < 40; repeat++) {
                        assertThat(GenericInferencePrototype.infer(call))
                                .containsExactlyEntriesOf(Map.of("T", example.oracleType()));
                    }
                    return null;
                });
            }
            for (var future : pool.invokeAll(tasks, 30, TimeUnit.SECONDS)) {
                future.get();
            }
        }
    }

    private static Map<String, String> prototype(String source) {
        var unit = App.parseSource(source, Optional.empty());
        return GenericInferencePrototype.infer(unit.findAll(MethodCallExpr.class).stream()
                .filter(c -> c.getNameAsString().equals("helper")).findFirst().orElseThrow());
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Path path = Path.of("target", "generic-inference-current.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                        .toJson(Map.of("java", System.getProperty("java.version"), "baselineCommit",
                                "fd2eb80af42909e9ea01a4d53f75da1924be556f", "completedCases", RESULTS)));
    }
}
