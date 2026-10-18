package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.TraceClassPath;
import cs1302.tracer.api.TraceFormat;
import cs1302.tracer.api.TraceRequest;
import cs1302.tracer.api.Tracer;
import cs1302.tracer.api.TypeStyle;
import cs1302.tracer.internal.SourcePreparation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javassist.bytecode.ClassFile;

/** Distinct legal binary owners never share declaration or hierarchy metadata through a canonical alias. */
class BinaryIdentityCollisionTest {

    @TempDir Path root;
    Path library;

    @BeforeEach
    void compileDistinctOwnersSeparatelyThenArchiveOnlyTheirClassFiles() throws Exception {
        Path nested = compile("nested", Map.of("p/Outer", """
                package p;
                public class Outer {
                    public static class Inner<T> { public String fixed; public T item; }
                }
                """), List.of());
        Path dotted = compile("dotted", Map.of("p/Outer/Inner", """
                package p.Outer;
                public class Inner<T> { public Integer fixed; public T item; }
                """), List.of());
        Path bases = compile("bases", Map.of(
                "p$foo/Base", "package p$foo; public class Base<T> { public T inherited; }",
                "p/foo/Base", "package p.foo; public class Base<T> { public Integer inherited; }"), List.of());
        Path child = compile("children", Map.of(
                "child/Child", "package child; public class Child<T> extends p$foo.Base<T> {}",
                "child/NestedChild", "package child; public class NestedChild<T> extends p.Outer.Inner<T> {}"),
                List.of(bases, nested));
        library = root.resolve("owners.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(library))) {
            for (Path classes : List.of(nested, dotted, bases, child)) {
                try (var files = Files.walk(classes)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                        Files.copy(file, output);
                        output.closeEntry();
                    }
                }
            }
        }
    }

    private Path compile(String group, Map<String, String> sources, List<Path> dependencies) throws Exception {
        Path classes = Files.createDirectories(root.resolve(group + "-classes"));
        Path empty = Files.createDirectories(root.resolve("empty"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "-g:none", "-d", classes.toString(),
                "-classpath", dependencies.isEmpty() ? empty.toString() : String.join(java.io.File.pathSeparator,
                        dependencies.stream().map(Path::toString).toList())));
        List<Path> inputs = new ArrayList<>();
        for (var entry : sources.entrySet()) {
            Path source = root.resolve(group + "-source/" + entry.getKey() + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
            inputs.add(source);
            arguments.add(source.toString());
        }
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)))
                .isZero();
        for (Path input : inputs) {
            Files.delete(input);
        }
        return classes;
    }

    @Test
    void exactBinaryOwnersAndDollarAncestorsStayDistinctInEitherCacheOrder() throws Exception {
        String source = "public class Main { public static void main(String[] args) {} }";
        for (List<String> order : List.of(List.of("p.Outer$Inner", "p.Outer.Inner"),
                List.of("p.Outer.Inner", "p.Outer$Inner"))) {
            try (var prepared = SourcePreparation.prepare(source, Optional.empty(),
                    SourcePreparation.Discovery.SUBMITTED_ONLY, TraceClassPath.builder().addPath(library).build())) {
                var resolver = new AstTypeResolver(prepared.units());
                for (String name : order) {
                    assertThat(resolver.fieldType(name + "<java.lang.String>", name, "fixed"))
                            .as(name).contains(name.contains("$") ? "java.lang.String" : "java.lang.Integer");
                }
                for (String name : order) {
                    assertThat(resolver.getClassGenericInfo(name).orElseThrow().classFqn()).isEqualTo(name);
                }
                assertThat(resolver.fieldType("child.Child<java.lang.String>", "p$foo.Base", "inherited"))
                        .contains("java.lang.String");
                assertThat(resolver.fieldType("child.NestedChild<java.lang.String>", "p.Outer$Inner", "item"))
                        .contains("java.lang.String");
                assertThat(resolver.reconcileRuntimeType("p.Outer$Inner", "p.Outer.Inner<java.lang.Integer>"))
                        .isEqualTo("p.Outer$Inner");
            }
        }
    }

    @Test
    void bothSerializersKeepDistinctOwnerFieldsAndInheritedDollarBindings() {
        for (TraceFormat format : TraceFormat.values()) {
            for (List<String> order : List.of(List.of("p.Outer$Inner", "p.Outer.Inner"),
                    List.of("p.Outer.Inner", "p.Outer$Inner"))) {
                String source = """
                        import child.Child;
                        import child.NestedChild;
                        public class Main {
                            public static void main(String[] args) throws Exception {
                                Object first = Class.forName("%s").getConstructor().newInstance();
                                Object second = Class.forName("%s").getConstructor().newInstance();
                                Child<String> child = new Child<>();
                                NestedChild<String> nestedChild = new NestedChild<>();
                                System.out.println("done");
                            }
                        }
                        """.formatted(order.get(0), order.get(1));
                var outcome = Tracer.trace(TraceRequest.builder(source).format(format).chronological(true)
                        .typeStyle(TypeStyle.FQN).build(), TraceClassPath.builder().addPath(library).build());
                assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
                JsonObject trace = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
                JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
                JsonObject last = steps.get(steps.size() - 1).getAsJsonObject();
                Map<String, Map<String, String>> objects = new HashMap<>();
                for (var entry : last.getAsJsonObject("heap").entrySet()) {
                    Map<String, String> fields = new HashMap<>();
                    if (format == TraceFormat.MODERN) {
                        JsonObject object = entry.getValue().getAsJsonObject();
                        if (!"object".equals(object.get("kind").getAsString())) {
                            continue;
                        }
                        for (var element : object.getAsJsonArray("fields")) {
                            JsonObject field = element.getAsJsonObject();
                            fields.put(field.get("name").getAsString(), field.get("type").getAsString());
                        }
                        objects.put(object.get("type").getAsString(), fields);
                    } else {
                        JsonArray object = entry.getValue().getAsJsonArray();
                        if (!"INSTANCE".equals(object.get(0).getAsString()) || object.size() == 2) {
                            continue;
                        }
                        JsonArray types = last.getAsJsonObject("heap_attrs").getAsJsonObject(entry.getKey())
                                .getAsJsonArray("type");
                        for (int index = 2; index < object.size(); index++) {
                            fields.put(object.get(index).getAsJsonArray().get(0).getAsString(),
                                    types.get(index - 2).getAsString());
                        }
                        objects.put(object.get(1).getAsString(), fields);
                    }
                }
                assertThat(objects.get("p.Outer$Inner")).containsEntry("fixed", "java.lang.String");
                assertThat(objects.get("p.Outer.Inner")).containsEntry("fixed", "java.lang.Integer");
                assertThat(objects.get("child.Child<java.lang.String>"))
                        .containsEntry("inherited", "java.lang.String");
                assertThat(objects.get("child.NestedChild<java.lang.String>"))
                        .containsEntry("item", "java.lang.String");
                assertThat(outcome.traceJson()).doesNotContain("Outer.java", "public class Inner");
            }
        }
    }

    @Test
    void unavailableParentEvidenceDoesNotDiscardTheRemainingDetachedDeclaration() throws Exception {
        Path classes = Files.createDirectories(root.resolve("broken-classes/broken"));
        ClassFile broken = new ClassFile(false, "broken.Child", "broken.Missing");
        broken.setInterfaces(new String[] {"java.io.Serializable"});
        try (var output = new DataOutputStream(Files.newOutputStream(classes.resolve("Child.class")))) {
            broken.write(output);
        }
        try (var prepared = SourcePreparation.prepare(
                "public class Main { public static void main(String[] args) {} }", Optional.empty(),
                SourcePreparation.Discovery.SUBMITTED_ONLY,
                TraceClassPath.builder().addPath(classes.getParent()).build())) {
            var info = new AstTypeResolver(prepared.units()).getClassGenericInfo("broken.Child").orElseThrow();
            assertThat(info.classFqn()).isEqualTo("broken.Child");
            assertThat(info.extendedType()).isEmpty();
            assertThat(info.implementedTypes()).containsExactly("java.io.Serializable");
        }
    }

} // BinaryIdentityCollisionTest
