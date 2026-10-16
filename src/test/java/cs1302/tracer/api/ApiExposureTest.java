package cs1302.tracer.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.Type;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.ClassWriter;
import org.apiguardian.api.API;
import java.lang.reflect.Modifier;

class ApiExposureTest {
    @Test
    void publicTypesDeclareTheirStability() throws Exception {
        Path classes = Path.of(Tracer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(classes.resolve("cs1302/tracer"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                name = name.substring(0, name.length() - 6).replace('/', '.');
                Class<?> type = Class.forName(name, false, Tracer.class.getClassLoader());
                if (!Modifier.isPublic(type.getModifiers()) && !Modifier.isProtected(type.getModifiers())) {
                    continue;
                }
                API annotation = type.getDeclaredAnnotation(API.class);
                assertThat(annotation).as("API status of %s", name).isNotNull();
                API.Status expected = name.startsWith("cs1302.tracer.internal.")
                        ? API.Status.INTERNAL : API.Status.STABLE;
                assertThat(annotation.status()).as(name).isEqualTo(expected);
                assertThat(annotation.since()).as(name).isEqualTo("4.0.0");
            }
        }
    }

    @Test
    void consumerCompilesWithTracerAndApiMetadataOnly(@TempDir Path directory) throws Exception {
        Path consumer = directory.resolve("Consumer.java");
        Files.writeString(consumer, """
                import cs1302.tracer.api.*;
                import java.io.Writer;
                class Consumer {
                    void use(String source, Writer writer) throws Exception {
                        TraceRequest request = new TraceRequest(source);
                        TraceOutcome outcome = Tracer.trace(request);
                        outcome.writeJson(writer);
                        BreakpointDiscovery discovery = Tracer.discoverBreakpoints(source);
                        for (BreakpointLocation location : discovery.breakpoints()) {
                            System.out.println(location.sourcePath() + ":" + location.line());
                        }
                        try (BatchTracer batch = new BatchTracer(1, 100, 16, false)) {
                            batch.processStream(System.in, writer);
                        }
                    }
                }
                """);
        String classes = Path.of(Tracer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String metadata = Path.of(API.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-Xlint:all", "-Werror", "-classpath", classes + java.io.File.pathSeparator + metadata,
                "-d", directory.toString(), consumer.toString())).isZero();
    }

    @Test
    void publicDeclarationsOnlyReferenceSupportedTypes() throws Exception {
        Path classes = Path.of(Tracer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(classes.resolve("cs1302/tracer"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                if (name.startsWith("cs1302/tracer/internal/")) {
                    continue;
                }
                assertThat(name).satisfiesAnyOf(
                        n -> assertThat(n).startsWith("cs1302/tracer/api/"),
                        n -> assertThat(n).isEqualTo("cs1302/tracer/App.class"),
                        n -> assertThat(n).isEqualTo("cs1302/tracer/package-info.class"));
                new ClassReader(Files.readAllBytes(file)).accept(new Surface(name, violations),
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void exposureCheckRejectsGenericAndAnnotationLeaks() {
        ClassWriter fixture = new ClassWriter(0);
        fixture.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/LeakingApi", null, "java/lang/Object", null);
        fixture.visitMethod(Opcodes.ACC_PUBLIC, "values", "()Ljava/util/List;",
                "()Ljava/util/List<Lcom/github/javaparser/ast/CompilationUnit;>;", null).visitEnd();
        fixture.visitAnnotation("Lcom/google/gson/annotations/SerializedName;", false).visitEnd();
        fixture.visitEnd();
        List<String> violations = new ArrayList<>();
        new ClassReader(fixture.toByteArray()).accept(new Surface("probe", violations), ClassReader.SKIP_CODE);
        assertThat(violations).anyMatch(s -> s.contains("com/github/javaparser/ast/CompilationUnit"))
                .anyMatch(s -> s.contains("com/google/gson/annotations/SerializedName"));
    }

    @Test
    void apiMetadataExceptionDoesNotAllowApiGuardianInSignatures() {
        ClassWriter fixture = new ClassWriter(0);
        fixture.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "probe/MetadataApi", null, "java/lang/Object", null);
        AnnotationVisitor annotation = fixture.visitAnnotation("Lorg/apiguardian/api/API;", true);
        annotation.visitEnum("status", "Lorg/apiguardian/api/API$Status;", "STABLE");
        annotation.visit("since", "4.0.0");
        annotation.visitEnd();
        fixture.visitMethod(Opcodes.ACC_PUBLIC, "status", "()Lorg/apiguardian/api/API$Status;", null, null).visitEnd();
        fixture.visitEnd();
        List<String> violations = new ArrayList<>();
        new ClassReader(fixture.toByteArray()).accept(new Surface("probe", violations), ClassReader.SKIP_CODE);
        assertThat(violations).containsExactly("probe exposes org/apiguardian/api/API$Status");
    }

    private static final class Surface extends ClassVisitor {
        private final String owner;
        private final List<String> violations;

        Surface(String owner, List<String> violations) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.violations = violations;
        }

        private void type(String name) {
            if (name != null && !name.startsWith("java/") && !name.startsWith("cs1302/tracer/api/")) {
                violations.add(owner + " exposes " + name);
            }
        }

        private void signature(String text) {
            if (text != null) {
                var matcher = Pattern.compile("L([^;<:]+)").matcher(text);
                while (matcher.find()) {
                    type(matcher.group(1));
                }
            }
        }

        private AnnotationVisitor annotation(String descriptor) {
            boolean apiMetadata = "Lorg/apiguardian/api/API;".equals(descriptor);
            if (!apiMetadata) {
                signature(descriptor);
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof Type type) {
                        Surface.this.signature(type.getDescriptor());
                    }
                }
                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    if (!(apiMetadata && "status".equals(name)
                            && "Lorg/apiguardian/api/API$Status;".equals(descriptor))) {
                        Surface.this.signature(descriptor);
                    }
                }
                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return Surface.this.annotation(descriptor);
                }
                @Override
                public AnnotationVisitor visitArray(String name) {
                    return Surface.this.annotation(null);
                }
            };
        }

        private boolean visible(int access) {
            return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
            type(parent);
            signature(signature);
            for (String iface : interfaces) {
                type(iface);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            signature(descriptor);
            signature(signature);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return Surface.this.annotation(descriptor);
                }
                @Override
                public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String desc, boolean visible) {
                    return Surface.this.annotation(desc);
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (!visible(access)) {
                return null;
            }
            signature(descriptor);
            signature(signature);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return Surface.this.annotation(descriptor);
                }
                @Override
                public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String desc, boolean visible) {
                    return Surface.this.annotation(desc);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            if (!visible(access)) {
                return null;
            }
            signature(descriptor);
            signature(signature);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    type(exception);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return Surface.this.annotation(descriptor);
                }
                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                    return Surface.this.annotation(descriptor);
                }
                @Override
                public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String desc, boolean visible) {
                    return Surface.this.annotation(desc);
                }
            };
        }
    }
}
