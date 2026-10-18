package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.BatchTracer;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.SourcePreparation;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Binary signatures provide generic evidence without adding library source to a trace. */
class BinaryGenericMetadataTest {

    @TempDir Path root;
    Path library;

    private static final String SOURCE = """
            import dependency.View;
            import dependency.Leaf;
            import java.util.Comparator;
            public class Main {
                public static void main(String[] args) {
                    View<String, Integer> value = new Leaf<String, Integer>(Comparator.naturalOrder());
                    System.out.println(value == null);
                }
            }
            """;

    @BeforeEach
    void compileIndependentBinaryHierarchy() throws Exception {
        Map<String, String> sources = Map.of(
                "View", "public interface View<A, B> {}",
                "Single", "public interface Single<A> {}",
                "Node", "public class Node<E> { public E item; public Node<E> next; }",
                "Base", "public abstract class Base<P, Q> implements View<Q, P> {"
                        + " public Node<P> head; public P item; public P[] values;"
                        + " public java.util.List<? extends P> views; public java.util.List<?> unknown; public int n; }",
                "Middle", "public abstract class Middle<M, N> extends Base<M, N> {}",
                "Leaf", "public class Leaf<X, Y> extends Middle<Y, X> {"
                        + " public java.util.Comparator<Y> comparator; public X item;"
                        + " public Leaf(java.util.Comparator<Y> comparator) { this.comparator = comparator; }}",
                "Partial", "public class Partial<T, U> implements Single<T> {"
                        + " public T known; public U unknown; public U[] unknownArray; }",
                "Fixed", "public class Fixed extends Base<String, Integer> {"
                        + " public java.util.Comparator<String> comparator; }",
                "Outer", "public class Outer<T> { public class Inner { public Node<T> head; }"
                        + " public class Inherited extends dependency.Base<T, Integer> {}"
                        + " public static class Base<E> { public E item; }}",
                "NestedLeaf", "public class NestedLeaf<F> extends Outer.Base<F> {}");
        Path input = Files.createDirectories(root.resolve("source/dependency"));
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path source = input.resolve(entry.getKey() + ".java");
            Files.writeString(source, "package dependency; " + entry.getValue());
            arguments.add(source.toString());
        }
        // Deliberately omit -g: declaration Signature attributes suffice, including null fields.
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .isZero();
        library = root.resolve("binary-only.jar");
        archiveLibrary(classes);
    }

    private void archiveLibrary(Path classes) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(library));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    @Test
    void binaryDeclarationsComposeRenamedReorderedParametersAndDeclaringOwners() throws Exception {
        try (var prepared = SourcePreparation.prepare(SOURCE, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(library).build())) {
            AstTypeResolver resolver = new AstTypeResolver(prepared.units());
            String leaf = "dependency.Leaf<java.lang.String, java.lang.Integer>";
            assertThat(resolver.reconcileRuntimeType("dependency.Leaf",
                    "dependency.View<java.lang.String, java.lang.Integer>")).isEqualTo(leaf);
            assertThat(resolver.fieldType(leaf, "dependency.Base", "head"))
                    .contains("dependency.Node<java.lang.Integer>");
            assertThat(resolver.fieldType(leaf, "dependency.Leaf", "comparator"))
                    .contains("java.util.Comparator<java.lang.Integer>");
            assertThat(resolver.fieldType(leaf, "dependency.Base", "item")).contains("java.lang.Integer");
            assertThat(resolver.fieldType(leaf, "dependency.Leaf", "item")).contains("java.lang.String");
            assertThat(resolver.fieldType(leaf, "missing.Owner", "head")).isEmpty();
            assertThat(resolver.fieldType(leaf, "dependency.Leaf", "missing")).isEmpty();
            assertThat(resolver.fieldType("dependency.Leaf", "dependency.Base", "head")).isEmpty();
            assertThat(resolver.fieldType("dependency.Leaf", "dependency.Leaf", "comparator")).isEmpty();
            assertThat(resolver.fieldType("dependency.Fixed", "dependency.Base", "head"))
                    .contains("dependency.Node<java.lang.String>");
            assertThat(resolver.fieldType("dependency.Fixed", "dependency.Fixed", "comparator"))
                    .contains("java.util.Comparator<java.lang.String>");
            assertThat(resolver.fieldType("dependency.Outer$Inner", "dependency.Outer$Inner", "head")).isEmpty();
            assertThat(resolver.fieldType("dependency.Outer$Inherited", "dependency.Base", "head")).isEmpty();
            assertThat(resolver.fieldType("dependency.Outer$Inherited", "dependency.Base", "item")).isEmpty();
            assertThat(resolver.getClassGenericInfo("dependency.Outer.Base")).isPresent();
            assertThat(resolver.fieldType("dependency.NestedLeaf<java.lang.String>",
                    "dependency.Outer$Base", "item")).contains("java.lang.String");
            assertThat(resolver.fieldType("dependency.NestedLeaf<java.lang.String>",
                    "dependency.Outer.Base", "item")).contains("java.lang.String");
            assertThat(resolver.reconcileRuntimeType("dependency.Outer$Base",
                    "dependency.Outer.Base<java.lang.String>"))
                    .isEqualTo("dependency.Outer$Base<java.lang.String>");
            assertThat(resolver.reconcileRuntimeType("dependency.Leaf", "dependency.View"))
                    .isEqualTo("dependency.Leaf");
            assertThat(resolver.resolveSubclassType("dependency.Partial", "dependency.Single<java.lang.String>"))
                    .isEqualTo("dependency.Partial<java.lang.String, ?>");
            for (String unknown : List.of("?", "? extends java.lang.Number", "? super java.lang.Integer")) {
                String partial = "dependency.Partial<java.lang.String, " + unknown + ">";
                assertThat(resolver.fieldType(partial, "dependency.Partial", "known")).contains("java.lang.String");
                assertThat(resolver.fieldType(partial, "dependency.Partial", "unknown")).isEmpty();
                assertThat(resolver.fieldType(partial, "dependency.Partial", "unknownArray")).isEmpty();
            }
            assertThat(resolver.resolveSubclassType("dependency.Leaf", "missing.View<java.lang.String>"))
                    .isEqualTo("dependency.Leaf");
            assertThat(resolver.resolveSubclassType("dependency.Leaf",
                    "dependency.View<java.lang.String, java.lang.Integer, java.lang.Long>"))
                    .isEqualTo("dependency.Leaf");
        }
        AstTypeResolver unavailable = new AstTypeResolver(StaticJavaParser.parse("class Main {}"));
        assertThat(unavailable.reconcileRuntimeType("dependency.Leaf", "dependency.View<String, Integer>"))
                .isEqualTo("dependency.Leaf");
        assertThat(unavailable.fieldType("dependency.Leaf", "dependency.Base", "head")).isEmpty();
    }

    @Test
    void qualifiedBinaryOwnersNeverBorrowPackageSuffixMatches() throws Exception {
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-d", root.resolve("classes").toString()));
        for (String name : List.of("a.Box", "vendor.a.Box")) {
            int separator = name.lastIndexOf('.');
            String contents = "package " + name.substring(0, separator)
                    + "; public class Box<T> { public " + (name.startsWith("vendor.") ? "Integer" : "T")
                    + " item; }";
            Path source = root.resolve("collisions").resolve(name.replace('.', '/') + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, contents);
            arguments.add(source.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .isZero();
        archiveLibrary(root.resolve("classes"));
        for (List<String> order : List.of(List.of("a.Box", "vendor.a.Box"), List.of("vendor.a.Box", "a.Box"))) {
            try (var prepared = SourcePreparation.prepare(SOURCE, Optional.empty(),
                    SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(library).build())) {
                AstTypeResolver resolver = new AstTypeResolver(prepared.units());
                for (String name : order) {
                    assertThat(resolver.fieldType(name + "<java.lang.String>", name, "item"))
                            .contains(name.startsWith("vendor.") ? "java.lang.Integer" : "java.lang.String");
                }
            }
        }
    }

    @Test
    void binaryFieldTypesPreserveDollarIdentitiesInsideConcreteAndStructuredDeclarations() throws Exception {
        Map<String, String> sources = Map.of(
                "p$foo/Item", "package p$foo; public class Item {}",
                "p/foo/Item", "package p.foo; public class Item {}",
                "literal/Item$Inner", "package literal; public class Item$Inner {}",
                "nested/Outer", "package nested; public class Outer<T> {"
                        + " public static class Item$Inner {} public class Member<U> {}"
                        + " public class Middle { public class Deep<V> {} }}",
                "fieldcase/Holder", """
                        package fieldcase;
                        public class Holder {
                            public p$foo.Item missing;
                            public p$foo.Item concrete = new p$foo.Item();
                            public p$foo.Item[] items;
                            public java.util.List<p$foo.Item[]> arrays;
                            public java.util.List<? extends p$foo.Item> upper;
                            public java.util.List<? super p$foo.Item> lower;
                            public java.util.Map<p$foo.Item, p.foo.Item> distinct;
                            public literal.Item$Inner literal;
                            public nested.Outer.Item$Inner nested;
                            public nested.Outer<p$foo.Item>.Member<p.foo.Item> owner;
                            public nested.Outer<p$foo.Item>.Middle.Deep<p.foo.Item> deep;
                        }
                        """);
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-g:none", "-d",
                root.resolve("classes").toString()));
        for (var entry : sources.entrySet()) {
            Path source = root.resolve("field-source/" + entry.getKey() + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            arguments.add(source.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .isZero();
        archiveLibrary(root.resolve("classes"));
        try (var files = Files.walk(root.resolve("field-source"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        }
        String source = """
                import fieldcase.Holder;
                public class Main {
                    public static void main(String[] args) {
                        Holder value = new Holder();
                        System.out.println(value.missing == null);
                    }
                }
                """;
        Map<String, String> expected = Map.of(
                "missing", "p$foo.Item", "concrete", "p$foo.Item", "items", "p$foo.Item[]",
                "arrays", "java.util.List<p$foo.Item[]>", "upper", "java.util.List<? extends p$foo.Item>",
                "lower", "java.util.List<? super p$foo.Item>",
                "distinct", "java.util.Map<p$foo.Item, p.foo.Item>", "literal", "literal.Item$Inner",
                "nested", "nested.Outer.Item$Inner",
                "owner", "nested.Outer<p$foo.Item>.Member<p.foo.Item>");
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(library).build())) {
            var resolver = new AstTypeResolver(prepared.units());
            var evidence = prepared.units().getFirst().getData(AstTypeResolver.BINARY_TYPES)
                    .declarationSignatures("fieldcase.Holder").orElseThrow();
            assertThat(evidence.fields().get("missing")).isEqualTo("Lp$foo/Item;");
            assertThat(BinaryTypeSignatures.field(evidence.fields().get("missing"),
                    prepared.units().getFirst().getData(AstTypeResolver.TYPE_SOLVER)))
                    .contains(new BinaryTypeSignatures.View("p$foo.Item", java.util.Set.of()));
            assertThat(resolver.getClassGenericInfo("fieldcase.Holder")).isPresent();
            assertThat(BinaryTypeSignatures.field("Lliteral/Item<TT;>.Inner;",
                    prepared.units().getFirst().getData(AstTypeResolver.TYPE_SOLVER))).isEmpty();
            assertThat(BinaryTypeSignatures.field("Lnested/Outer$Item<TT;>.Inner;",
                    prepared.units().getFirst().getData(AstTypeResolver.TYPE_SOLVER))).isEmpty();
            assertThat(resolver.fieldType("fieldcase.Holder", "fieldcase.Holder", "missing"))
                    .contains("p$foo.Item");
            for (var field : expected.entrySet()) {
                assertThat(resolver.fieldType("fieldcase.Holder", "fieldcase.Holder", field.getKey()))
                        .as(field.getKey()).contains(field.getValue());
            }
            // Javassist collapses the nongeneric middle owner; unavailable exact structure must retain JDI erasure.
            assertThat(resolver.fieldType("fieldcase.Holder", "fieldcase.Holder", "deep")).isEmpty();
        }
        for (TraceFormat format : TraceFormat.values()) {
            var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true)
                    .typeStyle(TypeStyle.FQN).build(), TraceClassPath.builder().addPath(library).build());
            assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
            JsonObject trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
            JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
            JsonObject last = steps.get(steps.size() - 1).getAsJsonObject();
            assertThat(last.getAsJsonObject("heap").entrySet()).anySatisfy(entry -> {
                Map<String, String> actual = new java.util.HashMap<>();
                if (format == TraceFormat.MODERN) {
                    JsonObject object = entry.getValue().getAsJsonObject();
                    assertThat(object.get("type").getAsString()).isEqualTo("fieldcase.Holder");
                    for (JsonElement field : object.getAsJsonArray("fields")) {
                        JsonObject value = field.getAsJsonObject();
                        actual.put(value.get("name").getAsString(), value.get("type").getAsString());
                    }
                } else {
                    JsonArray object = entry.getValue().getAsJsonArray();
                    assertThat(object.get(0).getAsString()).isEqualTo("INSTANCE");
                    assertThat(object.get(1).getAsString()).isEqualTo("fieldcase.Holder");
                    JsonArray types = last.getAsJsonObject("heap_attrs").getAsJsonObject(entry.getKey())
                            .getAsJsonArray("type");
                    for (int index = 2; index < object.size(); index++) {
                        actual.put(object.get(index).getAsJsonArray().get(0).getAsString(),
                                types.get(index - 2).getAsString());
                    }
                }
                assertThat(actual).containsAllEntriesOf(expected);
                assertThat(actual).containsEntry("deep", "nested.Outer$Middle$Deep");
            });
            assertThat(outcome.traceJson()).doesNotContain("Holder.java", "public class Item");
        }
    }

    @Test
    void hierarchyMatchingRetainsOnlyConsistentStructuralEvidence() {
        AstTypeResolver resolver = new AstTypeResolver(StaticJavaParser.parse("""
                interface One<A> {}
                interface Pair<A, B> {}
                interface Left<A> extends One<A> {}
                interface Right<A> extends One<A> {}
                class Diamond<T> implements Left<T>, Right<T> {}
                class ArrayValue<T> implements One<T[]> {}
                class Nested<T> implements One<java.util.List<T>> {}
                class Repeated<T> implements Pair<T, T> {}
                class FixedArgument<T> implements Pair<String, T> {}
                class Unbound<T> implements One<String> {}
                class Incomplete<T> extends missing.Parent<T> {}
                class Holder<V> { java.util.List<V> head; }
                class RawFixed<T> extends Holder<String> {}
                class Outer<T> {
                    class Inner { java.util.List<T> field; }
                    class Inherited extends Holder<T> {}
                }
                """));
        assertThat(resolver.resolveSubclassType("ArrayValue", "One<String[]>"))
                .isEqualTo("ArrayValue<String>");
        assertThat(resolver.resolveSubclassType("ArrayValue", "One<String>"))
                .isEqualTo("ArrayValue");
        assertThat(resolver.resolveSubclassType("Nested", "One<java.util.List<String>>"))
                .isEqualTo("Nested<String>");
        assertThat(resolver.resolveSubclassType("Repeated", "Pair<String, String>"))
                .isEqualTo("Repeated<String>");
        assertThat(resolver.resolveSubclassType("Repeated", "Pair<String, Integer>"))
                .isEqualTo("Repeated");
        assertThat(resolver.resolveSubclassType("FixedArgument", "Pair<String, Integer>"))
                .isEqualTo("FixedArgument<Integer>");
        assertThat(resolver.resolveSubclassType("FixedArgument", "Pair<Integer, Integer>"))
                .isEqualTo("FixedArgument");
        assertThat(resolver.resolveSubclassType("Unbound", "One<String>"))
                .isEqualTo("Unbound");
        assertThat(resolver.resolveSubclassType("Incomplete", "One<String>"))
                .isEqualTo("Incomplete");
        assertThat(resolver.resolveSubclassType("Diamond", "One<String>"))
                .isEqualTo("Diamond<String>");
        assertThat(resolver.resolveSubclassType("Diamond", "Missing<String>"))
                .isEqualTo("Diamond");
        assertThat(resolver.fieldType("Outer.Inner", "Outer.Inner", "field")).isEmpty();
        assertThat(resolver.fieldType("Outer.Inherited", "Holder", "head")).isEmpty();
        assertThat(resolver.fieldType("RawFixed", "Holder", "head")).contains("java.util.List<String>");
    }

    @Test
    void bothSerializersCaptureBinaryFieldTypesAndOnlySubmittedSource() {
        for (TraceFormat format : TraceFormat.values()) {
            var request = TraceRequest.builder(SOURCE).format(format).chronological(true)
                    .typeStyle(TypeStyle.FQN).build();
            var outcome = Tracer.trace(request, TraceClassPath.builder().addPath(library).build());
            assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
            assertThat(outcome.stdout()).isEqualTo("false\n");
            assertCapturedTypes(JsonParser.parseString(outcome.traceJson()), format,
                    "dependency.Leaf<java.lang.String, java.lang.Integer>", "java.lang.Integer");
            assertThat(outcome.traceJson()).doesNotContain("public abstract class Base", "Leaf.java", "Base.java");
        }
        var threaded = Tracer.trace(TraceRequest.builder(SOURCE).format(TraceFormat.MODERN)
                .multithread(true).typeStyle(TypeStyle.FQN).build(),
                TraceClassPath.builder().addPath(library).build());
        assertThat(threaded.complete()).as(threaded.diagnostics().toString()).isTrue();
        assertCapturedTypes(JsonParser.parseString(threaded.traceJson()), TraceFormat.MODERN,
                "dependency.Leaf<java.lang.String, java.lang.Integer>", "java.lang.Integer");
    }

    @Test
    void consecutiveBatchJobsDoNotReuseBinaryParameterBindings() throws Exception {
        StringBuilder jobs = new StringBuilder();
        for (String type : List.of("Integer", "Long")) {
            JsonObject job = new JsonObject();
            job.addProperty("id", type);
            job.addProperty("source", SOURCE.replace("Integer", type));
            job.addProperty("format", "modern");
            job.addProperty("typeStyle", "fqn");
            job.addProperty("allBreakpoints", true);
            JsonArray paths = new JsonArray();
            paths.add(library.toString());
            job.add("classPath", paths);
            jobs.append(job).append('\n');
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream(jobs.toString().getBytes(StandardCharsets.UTF_8)), output);
        }
        List<String> responses = output.toString().lines().toList();
        assertThat(responses).hasSize(2);
        for (int index = 0; index < responses.size(); index++) {
            String argument = index == 0 ? "java.lang.Integer" : "java.lang.Long";
            JsonObject result = JsonParser.parseString(responses.get(index)).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            assertCapturedTypes(result.get("trace"), TraceFormat.MODERN,
                    "dependency.Leaf<java.lang.String, " + argument + ">", argument);
        }
    }

    private static void assertCapturedTypes(JsonElement trace, TraceFormat format, String heading, String itemType) {
        List<JsonObject> objects = new ArrayList<>();
        collectObjects(trace, objects);
        if (format == TraceFormat.MODERN) {
            assertThat(objects).anySatisfy(object -> {
                assertThat(object.has("type") && object.get("type").isJsonPrimitive()).isTrue();
                assertThat(object.get("type").getAsString()).isEqualTo(heading);
                List<String> fields = new ArrayList<>();
                for (JsonElement field : object.getAsJsonArray("fields")) {
                    JsonObject value = field.getAsJsonObject();
                    fields.add(value.get("name").getAsString() + ":" + value.get("type").getAsString());
                }
                assertThat(fields).contains("head:dependency.Node<" + itemType + ">",
                        "comparator:java.util.Comparator<" + itemType + ">",
                        "item:" + itemType, "item:java.lang.String");
            });
        } else {
            assertThat(objects).anySatisfy(step -> {
                assertThat(step.has("heap_attrs")).isTrue();
                JsonObject heap = step.getAsJsonObject("heap");
                assertThat(heap.entrySet()).anySatisfy(entry -> {
                    JsonArray object = entry.getValue().getAsJsonArray();
                    assertThat(object.get(0).getAsString()).isEqualTo("INSTANCE");
                    assertThat(object.get(1).getAsString()).isEqualTo(heading);
                    JsonArray types = step.getAsJsonObject("heap_attrs").getAsJsonObject(entry.getKey())
                            .getAsJsonArray("type");
                    List<String> fields = new ArrayList<>();
                    for (int index = 2; index < object.size(); index++) {
                        fields.add(object.get(index).getAsJsonArray().get(0).getAsString() + ":"
                                + types.get(index - 2).getAsString());
                    }
                    assertThat(fields).contains("head:dependency.Node<" + itemType + ">",
                            "comparator:java.util.Comparator<" + itemType + ">",
                            "item:" + itemType, "item:java.lang.String");
                });
            });
        }
    }

    private static void collectObjects(JsonElement element, List<JsonObject> objects) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            objects.add(object);
            object.entrySet().forEach(entry -> collectObjects(entry.getValue(), objects));
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectObjects(child, objects));
        }
    }

} // BinaryGenericMetadataTest
