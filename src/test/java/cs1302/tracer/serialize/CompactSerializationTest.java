package cs1302.tracer.serialize;

import static org.assertj.core.api.Assertions.*;
import cs1302.tracer.trace.*;
import cs1302.tracer.model.TypeStyle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompactSerializationTest {
    @Test void viewsDoNotRetainGeneratedStepsAndRejectOutOfRangeIndices() {
        var calls = new AtomicInteger();
        var view = new StepView<>(1, ignored -> calls.incrementAndGet());
        assertThat(view.size()).isEqualTo(1);
        assertThat(calls.get()).isZero();
        assertThat(view.get(0)).isEqualTo(1);
        assertThat(view.get(0)).isEqualTo(2);
        assertThatThrownBy(() -> view.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> view.get(1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void compactModelsMatchEagerModelsIncludingMalformedUtf8AndMultipleFiles() {
        var states = List.of(
                new ExecutionSnapshot(List.of(), List.of(), Map.of(), new byte[] {(byte) 0xc3},
                        new byte[0], Optional.of("Main.java")),
                new ExecutionSnapshot(List.of(), List.of(), Map.of(), "é😀".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "err".getBytes(), Optional.of("Helper.java")));
        var storage = new OutputStorage();
        var captured = states.stream().map(storage::capture).toList();
        var modern = new ModernTraceSerializer(false, false, false, TypeStyle.FQN);
        var pytutor = new PyTutorSerializer(false, false, false, TypeStyle.FQN);
        var gson = PyTutorSerializer.getGson(false);
        assertThat(gson.toJson(modern.createCapturedTrace("code", "stdin", captured)))
                .isEqualTo(gson.toJson(modern.createTrace("code", "stdin", states)));
        assertThat(gson.toJson(pytutor.createCapturedTrace("code", null, captured)))
                .isEqualTo(gson.toJson(pytutor.createTrace("code", null, states)));
        assertThat(gson.toJson(modern.createCapturedBreakpoints("code", "", Map.of(1, captured), true)))
                .isEqualTo(gson.toJson(modern.createBreakpointsTrace("code", "", Map.of(1, states))));
        assertThat(gson.toJson(modern.createCapturedBreakpoints("code", "", Map.of(1, captured), false)))
                .isEqualTo(gson.toJson(modern.createBreakpointsTrace("code", "", Map.of(1, states.getLast()))));
    }

    @Test void failedLazyConversionDeletesSpoolWithoutPublishingPartialJson() throws Exception {
        Set<Path> before = spools();
        var bytes = new java.io.ByteArrayOutputStream();
        var old = System.out;
        try {
            System.setOut(new java.io.PrintStream(bytes));
            assertThatThrownBy(() -> {
                try (var output = new JsonOutput()) {
                    output.write(PyTutorSerializer.getGson(false), new StepView<>(2, index -> {
                        if (index == 1) throw new IllegalStateException("bad step");
                        return "first";
                    }));
                    output.publish();
                }
            }).isInstanceOf(IllegalStateException.class);
        } finally {
            System.setOut(old);
        }
        assertThat(bytes.toString()).isEmpty();
        assertThat(spools()).isEqualTo(before);
    }

    private Set<Path> spools() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("tracer-json-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
