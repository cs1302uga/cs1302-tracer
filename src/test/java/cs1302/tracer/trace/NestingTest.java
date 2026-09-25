package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;
import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.NestingException;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceSession;
import cs1302.tracer.serialize.ModernTraceSerializer;
import cs1302.tracer.serialize.PyTutorSerializer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NestingTest {
    @Test
    void exercisesBoundariesInDisposableSmallStackJvm(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp)
            throws Exception {
        var log = temp.resolve("probe.log");
        Process child = new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xss512k", "-cp", System.getProperty("java.class.path"), NestingTest.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(child.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).withFailMessage(java.nio.file.Files.readString(log)).isZero();
        } finally {
            child.destroyForcibly();
        }
    }

    public static void main(String[] args) {
        var tests = new NestingTest();
        tests.acceptsBoundaryAndRejectsExtremeDepthBeforeConversionOrAccounting();
        tests.distinguishesIdentityCyclesFromSharedContainers();
        tests.rejectsTypeFragmentsBeforeParsingAndBoundedAstBeforePrinting();
        tests.rejectsGenericAstBeforeRecursiveLibraryPrinting();
        new AstTypeResolverTest().resolvesDeepFiniteTypeTrees();
        String boundary = "List<".repeat(64) + "T" + ">".repeat(64);
        assertThat(AstTypeResolver.substituteType(boundary, Map.of("T", "String")))
                .isEqualTo("List<".repeat(64) + "String" + ">".repeat(64));
        assertThat(AstTypeResolver.resolveAstTypeWithParams(
                StaticJavaParser.parseType(boundary), List.of("T"))).isEqualTo(boundary);
        String arrays = "T" + "[]".repeat(382);
        assertThat(AstTypeResolver.substituteType(arrays, Map.of("T", "int")))
                .isEqualTo("int" + "[]".repeat(382));
    }

    static TraceValue nested(int depth, int kind) {
        TraceValue value = new TraceValue.Primitive.Integer(7);
        for (int i = 1; i < depth; i++) {
            value = switch (kind) {
                case 0 -> new TraceValue.List("List", List.of(value));
                case 1 -> new TraceValue.Collection("Set", List.of(value));
                case 2 -> new TraceValue.Object("Node", List.of(field(value)));
                default -> {
                    Map<TraceValue, TraceValue> entries = new IdentityHashMap<>();
                    entries.put(new TraceValue.String("key"), value);
                    yield new TraceValue.Map(entries);
                }
            };
        }
        return value;
    }

    static ExecutionSnapshot.Field field(TraceValue value) {
        return new ExecutionSnapshot.Field(false, "Node", "child", value);
    }

    static ExecutionSnapshot snapshot(TraceValue value) {
        return new ExecutionSnapshot(List.of(), List.of(), Map.of(1L, value),
                new byte[0], new byte[0]);
    }

    @Test
    void acceptsBoundaryAndRejectsExtremeDepthBeforeConversionOrAccounting() {
        for (int kind = 0; kind < 4; kind++) {
            for (int depth : List.of(ValueTraversal.MAX_DEPTH - 1, ValueTraversal.MAX_DEPTH)) {
                var snapshot = snapshot(nested(depth, kind));
                var tutor = new PyTutorSerializer(false, false, false);
                assertThat(tutor.serialize("public class Example {}", snapshot, false)).contains("7");
                var modern = new ModernTraceSerializer(false, false, false);
                assertThat(ModernTraceSerializer.getGson().toJson(modern.createTrace("public class Example {}", snapshot)))
                        .contains("heap");
                try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
                    session.beginSnapshot();
                    session.commit(snapshot);
                    assertThat(session.snapshots()).hasSize(1);
                }
            }
            for (int depth : List.of(ValueTraversal.MAX_DEPTH + 1, 10000)) {
                var invalid = snapshot(nested(depth, kind));
                assertThatThrownBy(() -> new PyTutorSerializer(false, false, false)
                        .createTraceStep(invalid)).isInstanceOf(NestingException.class)
                        .hasMessage("value_nesting_limit");
                assertThatThrownBy(() -> new ModernTraceSerializer(false, false, false)
                        .createTrace("public class Example {}", invalid)).hasMessage("value_nesting_limit");
                try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
                    session.commit(snapshot(new TraceValue.Null()));
                    session.beginSnapshot();
                    assertThatThrownBy(() -> session.commit(invalid))
                            .isInstanceOf(TraceSession.Stopped.class).hasMessage("value_nesting_limit");
                    assertThat(session.snapshots()).hasSize(1);
                    var result = session.result("modern", null, null);
                    assertThat(result.stopReason()).isEqualTo("value_nesting_limit");
                    assertThat(result.counters().get("droppedSnapshots")).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void distinguishesIdentityCyclesFromSharedContainers() {
        var children = new ArrayList<TraceValue>();
        var value = new TraceValue.List("List", children);
        children.add(value);
        assertThatThrownBy(() -> ValueTraversal.validate(snapshot(value)))
                .hasMessage("inline_value_cycle");
        children.clear();
        children.add(null);
        var shared = new TraceValue.List("List", List.of(value, value));
        assertThat(new PyTutorSerializer(false, false, false).serialize("public class Example {}", snapshot(shared), false))
                .contains("null");
        Map<TraceValue, TraceValue> entries = new IdentityHashMap<>();
        var map = new TraceValue.Map(entries);
        entries.put(map, new TraceValue.Null());
        assertThatThrownBy(() -> ValueTraversal.validate(snapshot(map)))
                .hasMessage("inline_value_cycle");
        var fields = new ArrayList<ExecutionSnapshot.Field>();
        var object = new TraceValue.Object("Node", fields);
        fields.add(field(object));
        assertThatThrownBy(() -> ValueTraversal.validate(snapshot(object)))
                .hasMessage("inline_value_cycle");
    }

    @Test
    void inlinesLibrarySuppliedStringsWithoutFollowingReferenceChains() {
        var snapshot = new ExecutionSnapshot(List.of(), List.of(field(new TraceValue.String("literal"))),
                Map.of(), new byte[0], new byte[0]);
        assertThat(new PyTutorSerializer(false, true, false).createTraceStep(snapshot).globals())
                .containsEntry("child", "literal");
    }

    @Test
    void boundsSharedGraphsWithoutExpandingEveryPath() {
        TraceValue value = new TraceValue.Null();
        for (int i = 1; i < ValueTraversal.MAX_DEPTH; i++) {
            value = new TraceValue.List("List", List.of(value, value));
        }
        var visited = new java.util.concurrent.atomic.AtomicInteger();
        ValueTraversal.visit(value, ignored -> visited.incrementAndGet());
        assertThat(visited).hasValue(ValueTraversal.MAX_DEPTH);
        TraceValue shared = nested(30, 0);
        TraceValue deep = new TraceValue.List("List", List.of(
                new TraceValue.List("List", List.of(shared))));
        var invalid = new TraceValue.List("List", List.of(shared, deep));
        assertThatThrownBy(() -> ValueTraversal.validate(snapshot(invalid)))
                .hasMessage("value_nesting_limit");
    }

    @Test
    void checksStaticsAndAllThreadLocals() {
        TraceValue bad = nested(100, 0);
        var frame = new ExecutionSnapshot.StackSnapshot("main", 1, List.of(field(bad)), Optional.empty());
        for (var invalid : List.of(
                new ExecutionSnapshot(List.of(), List.of(field(bad)), Map.of(), new byte[0], new byte[0]),
                new ExecutionSnapshot(List.of(frame), List.of(), Map.of(), new byte[0], new byte[0]),
                new ExecutionSnapshot(List.of(), List.of(), Map.of(), OutputSlice.empty(), OutputSlice.empty(),
                        Optional.empty(), "", 0,
                        List.of(new ExecutionSnapshot.ThreadSnapshot(1, "worker", "RUNNABLE", List.of(frame))),
                        1L, "step_line"))) {
            assertThatThrownBy(() -> ValueTraversal.validate(invalid)).hasMessage("value_nesting_limit");
        }
    }

    @Test
    void rejectsGenericAstBeforeRecursiveLibraryPrinting() {
        Type type = new com.github.javaparser.ast.type.ClassOrInterfaceType(null, "T");
        for (int i = 0; i < 382; i++) {
            type = new com.github.javaparser.ast.type.ClassOrInterfaceType(null, "L").setTypeArguments(type);
        }
        Type invalid = type;
        assertThatThrownBy(() -> AstTypeResolver.resolveAstTypeWithParams(invalid, List.of("T")))
                .isInstanceOf(NestingException.class).hasMessage("type_nesting_limit");
        assertThatThrownBy(() -> AstTypeResolver.resolveAstType(invalid))
                .isInstanceOf(NestingException.class).hasMessage("type_nesting_limit");
        // A replacement can make a valid input exceed the combined AST bound.
        String input = "L<".repeat(40) + "T" + ">".repeat(40);
        String replacement = "L<".repeat(40) + "String" + ">".repeat(40);
        assertThatThrownBy(() -> AstTypeResolver.substituteType(input, Map.of("T", replacement)))
                .isInstanceOf(NestingException.class).hasMessage("type_nesting_limit");
    }

    @Test
    void rejectsTypeFragmentsBeforeParsingAndBoundedAstBeforePrinting() {
        String huge = "List<".repeat(10000) + "T" + ">".repeat(10000);
        assertThatThrownBy(() -> AstTypeResolver.substituteType(huge, Map.of("T", "String")))
                .hasMessage("type_nesting_limit");
        assertThatThrownBy(() -> AstTypeResolver.substituteType("T", Map.of("T", huge)))
                .hasMessage("type_nesting_limit");
        assertThatThrownBy(() -> TypeTraversal.validateText("a.".repeat(385) + "T"))
                .hasMessage("type_nesting_limit");
        Type type = StaticJavaParser.parseType("int");
        for (int i = 0; i < 1000; i++) {
            type = new ArrayType(type);
        }
        Type invalid = type;
        assertThatThrownBy(() -> AstTypeResolver.resolveAstTypeWithParams(invalid, List.of()))
                .hasMessage("type_nesting_limit");
        assertThatThrownBy(() -> AstTypeResolver.resolveAstType(invalid))
                .hasMessage("type_nesting_limit");
        assertThatThrownBy(() -> AstTypeResolver.substituteType("T" + "[]".repeat(400), Map.of("T", "int")))
                .hasMessage("type_nesting_limit");
        TypeTraversal.validateText("({[T]})>a.b");
        TypeTraversal.validateText("<".repeat(64) + "T" + ">".repeat(64));
        try (var session = new TraceSession(TraceLimits.unlimited(), InspectionPolicy.FIELDS, true)) {
            session.phase("source");
            assertThat(session.result("modern", null, new NestingException("type_nesting_limit"))
                    .stopReason()).isEqualTo("type_nesting_limit");
        }
    }
}
