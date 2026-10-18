package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Binary-only lambda factories retain functional headings without inventing source bodies. */
class BinaryLambdaHeadingTest {

    @TempDir Path root;
    Path library;

    @BeforeEach
    void compileIndependentFactoryWithoutDebugTables() throws Exception {
        Path source = root.resolve("source/dependency/Factory.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package dependency;
                public final class Factory {
                    public static <T> java.util.Comparator<T> zero() { return (a, b) -> 0; }
                    public static p$foo.Fn<String> dollarPackage() { return value -> value; }
                    public static p.foo.Fn<String> dottedPackage() { return value -> value; }
                    public static literal.Fn$Inner<String> dollarType() { return value -> value; }
                    public static nested.Outer.Inner<String> nestedType() { return value -> value; }
                    public static nested.Outer.Fn$Inner<String> nestedDollarType() { return value -> value; }
                    public static java.util.Comparator<String> captured(String marker) {
                        return (a, b) -> marker.length();
                    }
                    public static java.util.Comparator<String> ordinary() { return new Concrete(); }
                    public interface First { void run(); }
                    public interface Second { void run(); }
                    public static First intersection() { return (First & Second & java.io.Serializable) () -> {}; }
                    private static final class Concrete implements java.util.Comparator<String> {
                        public int compare(String a, String b) { return 0; }
                    }
                }
                """);
        Path classes = Files.createDirectories(root.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-g:none", "-d", classes.toString(),
                source.toString()));
        for (var entry : Map.of(
                "p$foo/Fn", "package p$foo; public interface Fn<T> { T apply(T value); }",
                "p/foo/Fn", "package p.foo; public interface Fn<T> { T apply(T value); }",
                "literal/Fn$Inner", "package literal; public interface Fn$Inner<T> { T apply(T value); }",
                "Default$Fn", "public interface Default$Fn<T> { T apply(T value); }",
                "nested/Outer", "package nested; public class Outer {"
                        + " public interface Inner<T> { T apply(T value); }"
                        + " public interface Fn$Inner<T> { T apply(T value); }}",
                "p$foo/Box", "package p$foo; public class Box<T> { public T item; }",
                "p/foo/Box", "package p.foo; public class Box<T> { public Integer item; }").entrySet()) {
            Path input = root.resolve("source/" + entry.getKey() + ".java");
            Files.createDirectories(input.getParent());
            Files.writeString(input, entry.getValue());
            arguments.add(input.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .isZero();
        library = root.resolve("factory.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(library));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        try (var files = Files.walk(root.resolve("source"))) {
            for (Path input : files.filter(Files::isRegularFile).toList()) {
                Files.delete(input);
            }
        }
    }

    @Test
    void binaryFunctionalIdentitiesPreserveLiteralDollarsAndOnlyConvertRealNesting() throws Exception {
        String source = """
                import dependency.Factory;
                public class Main {
                    public static void main(String[] args) {
                        p$foo.Fn<String> dollarPackage = Factory.dollarPackage();
                        p.foo.Fn<String> dottedPackage = Factory.dottedPackage();
                        literal.Fn$Inner<String> dollarType = Factory.dollarType();
                        nested.Outer.Inner<String> nestedType = Factory.nestedType();
                        nested.Outer.Fn$Inner<String> nestedDollarType = Factory.nestedDollarType();
                        System.out.println("done");
                    }
                }
                """;
        for (TraceFormat format : TraceFormat.values()) {
            for (TypeStyle style : TypeStyle.values()) {
                List<String> names = headings(trace(source, format, style), format);
                assertThat(names).contains(style == TypeStyle.FQN ? new String[] {
                        "p$foo.Fn<java.lang.String>λ", "p.foo.Fn<java.lang.String>λ",
                        "literal.Fn$Inner<java.lang.String>λ", "nested.Outer.Inner<java.lang.String>λ",
                        "nested.Outer.Fn$Inner<java.lang.String>λ"} : new String[] {
                        "Fn<String>λ", "Fn$Inner<String>λ", "Inner<String>λ"});
                assertThat(names).noneMatch(name -> name.contains("literal.Fn.Inner")
                        || name.contains("Outer.Fn.Inner"));
            }
        }
        try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(library).build())) {
            var resolver = new AstTypeResolver(prepared.units());
            assertThat(resolver.functionalInterfaceName("p$foo.Fn", "p$foo.Fn")).contains("p$foo.Fn");
            assertThat(resolver.functionalInterfaceName("p$foo.Fn", "p.foo.Fn")).isEmpty();
            assertThat(resolver.functionalInterfaceName("literal.Fn$Inner", "literal.Fn.Inner")).isEmpty();
            assertThat(resolver.functionalInterfaceName("nested.Outer$Inner", "nested.Outer.Inner"))
                    .contains("nested.Outer.Inner");
            assertThat(resolver.functionalInterfaceName("nested.Outer$Inner", "nested.Outer$Inner"))
                    .contains("nested.Outer.Inner");
            assertThat(resolver.functionalInterfaceName("nested.Outer$Fn$Inner", "nested.Outer.Fn$Inner"))
                    .contains("nested.Outer.Fn$Inner");
            assertThat(resolver.functionalInterfaceName("nested.Outer$Fn$Inner", "nested.Outer.Fn.Inner"))
                    .isEmpty();
            assertThat(resolver.functionalInterfaceName("Default$Fn", "Default$Fn")).contains("Default$Fn");
            assertThat(resolver.functionalInterfaceName("Default$Fn", "Default.Fn")).isEmpty();
            for (List<String> order : List.of(List.of("nested.Outer$Inner", "nested.Outer.Inner<java.lang.String>"),
                    List.of("nested.Outer.Inner<java.lang.String>", "nested.Outer$Inner"))) {
                var metadata = new ReifiedTypeMetadata(resolver);
                var implemented = JdiValueContractTest.mirror(com.sun.jdi.InterfaceType.class,
                        Map.of("name", "nested.Outer$Inner"));
                var runtime = JdiValueContractTest.mirror(com.sun.jdi.ClassType.class,
                        Map.of("name", "dependency.Factory$$Lambda/0x123abc", "allInterfaces", List.of(implemented)));
                var lambda = JdiValueContractTest.mirror(com.sun.jdi.ObjectReference.class,
                        Map.of("uniqueID", 91L, "referenceType", runtime));
                for (String declared : order) {
                    metadata.refineDeclared(lambda, declared);
                }
                assertThat(metadata.functionalView(91)).isEqualTo("nested.Outer.Inner<java.lang.String>");
            }
        }
    }

    @Test
    void binaryDeclarationCacheNeverAliasesLiteralDollarPackagesToDottedPackages() throws Exception {
        for (List<String> order : List.of(List.of("p$foo.Box", "p.foo.Box"),
                List.of("p.foo.Box", "p$foo.Box"))) {
            try (var prepared = SourcePreparation.prepare("public class Main { public static void main(String[] a) {} }",
                    Optional.empty(),
                    SourcePreparation.Discovery.SUBMITTED_ONLY,
                    TraceClassPath.builder().addPath(library).build())) {
                var resolver = new AstTypeResolver(prepared.units());
                for (String name : order) {
                    assertThat(resolver.fieldType(name + "<java.lang.String>", name, "item"))
                            .contains(name.contains("$") ? "java.lang.String" : "java.lang.Integer");
                }
            }
        }
    }

    @Test
    void lateFieldEvidenceWorksInEitherAliasOrderAndPreservesOrdinaryObjectsAndSourceLambdas() {
        for (TraceFormat format : TraceFormat.values()) {
            for (TypeStyle style : TypeStyle.values()) {
                for (String aliases : List.of(
                        "Object erased = Factory.<String>zero(); Holder holder = new Holder((Comparator<String>) erased);",
                        "Holder holder = new Holder(Factory.<String>zero()); Object erased = holder.comparator;")) {
                    String source = """
                            import dependency.Factory;
                            import java.util.Comparator;
                            public class Main {
                                static class Holder {
                                    Comparator<String> comparator;
                                    Holder(Comparator<String> value) { comparator = value; }
                                }
                                @SuppressWarnings("unchecked")
                                public static void main(String[] args) {
                                    %s
                                    Comparator<String> ordinary = Factory.ordinary();
                                    Comparator<String> source = (a, b) -> a.length() - b.length();
                                    System.out.println(erased == holder.comparator);
                                }
                            }
                            """.formatted(aliases);
                    JsonObject trace = trace(source, format, style);
                    List<String> headings = headings(trace, format);
                    assertThat(headings).contains(style == TypeStyle.FQN
                            ? "java.util.Comparator<java.lang.String>λ" : "Comparator<String>λ");
                    assertThat(headings).anyMatch(name -> name.endsWith("Factory$Concrete"));
                    assertThat(trace.toString()).contains(format == TraceFormat.MODERN ? "\"kind\":\"lambda\""
                            : "\"JAVA_LAMBDA\"");
                    assertThat(trace.toString()).contains("a.length() - b.length()");
                    assertThat(trace.toString()).doesNotContain("Factory.java", "public static <T>");
                }
            }
        }
    }

    @Test
    void rawWildcardAndConflictingViewsStayHonestAndIntersectionViewsRemainOpaque() {
        for (TraceFormat format : TraceFormat.values()) {
            for (String declarations : List.of(
                    "Comparator raw = Factory.zero();",
                    "Comparator<? super String> wildcard = Factory.<String>zero();",
                    "Comparator<String> typed = Factory.zero(); Comparator<Integer> conflict = (Comparator<Integer>) (Object) typed;",
                    "Factory.First first = Factory.intersection(); Factory.Second second = (Factory.Second) first;")) {
                String source = """
                        import dependency.Factory;
                        import java.util.Comparator;
                        public class Main {
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            public static void main(String[] args) {
                                %s
                                System.out.println("done");
                            }
                        }
                        """.formatted(declarations);
                List<String> headings = headings(trace(source, format, TypeStyle.FQN), format);
                if (declarations.contains("Factory.First")) {
                    assertThat(headings).anyMatch(name -> name.startsWith("dependency.Factory$$Lambda"));
                    assertThat(headings).noneMatch(name -> name.endsWith("λ"));
                } else {
                    assertThat(headings).contains(declarations.contains("wildcard")
                            ? "java.util.Comparator<? super java.lang.String>λ" : "java.util.Comparatorλ");
                }
            }
        }
    }

    @Test
    void functionalPresentationPreservesCapturedInstanceFields() {
        String source = """
                import dependency.Factory;
                import java.util.Comparator;
                public class Main {
                    public static void main(String[] args) {
                        Comparator<String> value = Factory.captured("retained");
                        System.out.println(value != null);
                    }
                }
                """;
        for (TraceFormat format : TraceFormat.values()) {
            JsonObject trace = trace(source, format, TypeStyle.FQN);
            JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
            JsonObject heap = steps.get(steps.size() - 1).getAsJsonObject().getAsJsonObject("heap");
            assertThat(headings(trace, format)).contains("java.util.Comparator<java.lang.String>λ");
            if (format == TraceFormat.MODERN) {
                assertThat(heap.entrySet()).anySatisfy(entry -> {
                    JsonObject object = entry.getValue().getAsJsonObject();
                    assertThat(object.get("type").getAsString())
                            .isEqualTo("java.util.Comparator<java.lang.String>λ");
                    assertThat(object.getAsJsonArray("fields")).hasSize(1);
                    assertThat(object.getAsJsonArray("fields").get(0).getAsJsonObject().get("type").getAsString())
                            .isEqualTo("java.lang.String");
                });
            } else {
                assertThat(heap.entrySet()).anySatisfy(entry -> {
                    JsonArray object = entry.getValue().getAsJsonArray();
                    assertThat(object.get(0).getAsString()).isEqualTo("INSTANCE");
                    assertThat(object.get(1).getAsString()).isEqualTo("java.util.Comparator<java.lang.String>λ");
                    assertThat(object).hasSize(3);
                });
            }
        }
    }

    @Test
    void reusedBatchJobsKeepFunctionalArgumentsScopedToEachJobInBothFormats() throws Exception {
        StringBuilder jobs = new StringBuilder();
        List<String> expected = new ArrayList<>();
        List<TraceFormat> formats = new ArrayList<>();
        for (TraceFormat format : TraceFormat.values()) {
            for (String type : List.of("String", "Integer")) {
                String source = """
                        import dependency.Factory;
                        import java.util.Comparator;
                        public class Main {
                            public static void main(String[] args) {
                                Comparator<%s> value = Factory.zero();
                                System.out.println(value != null);
                            }
                        }
                        """.formatted(type);
                JsonObject job = new JsonObject();
                job.addProperty("id", format + type);
                job.addProperty("source", source);
                job.addProperty("format", format == TraceFormat.MODERN ? "modern" : "pytutor");
                job.addProperty("typeStyle", "fqn");
                job.addProperty("allBreakpoints", true);
                JsonArray paths = new JsonArray();
                paths.add(library.toString());
                job.add("classPath", paths);
                jobs.append(job).append('\n');
                expected.add("java.util.Comparator<java.lang." + type + ">λ");
                formats.add(format);
            }
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream(jobs.toString().getBytes(StandardCharsets.UTF_8)), output);
        }
        List<String> responses = output.toString().lines().toList();
        assertThat(responses).hasSize(expected.size());
        for (int index = 0; index < responses.size(); index++) {
            JsonObject result = JsonParser.parseString(responses.get(index)).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            assertThat(headings(result.getAsJsonObject("trace"), formats.get(index))).contains(expected.get(index));
        }
    }

    private JsonObject trace(String source, TraceFormat format, TypeStyle style) {
        var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true)
                .typeStyle(style).build(), TraceClassPath.builder().addPath(library).build());
        assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
        return JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
    }

    private static List<String> headings(JsonObject trace, TraceFormat format) {
        JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
        JsonObject heap = steps.get(steps.size() - 1).getAsJsonObject().getAsJsonObject("heap");
        List<String> headings = new ArrayList<>();
        for (var entry : heap.entrySet()) {
            JsonElement value = entry.getValue();
            if (format == TraceFormat.MODERN) {
                JsonObject object = value.getAsJsonObject();
                if (object.get("kind").getAsString().equals("object")) {
                    headings.add(object.get("type").getAsString());
                }
            } else if (value.getAsJsonArray().get(0).getAsString().equals("INSTANCE")) {
                headings.add(value.getAsJsonArray().get(1).getAsString());
            }
        }
        return headings;
    }
}
