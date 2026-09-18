package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutputSliceTest {

    @Test
    @DisplayName("empty slice behavior")
    void testEmptySlice() {
        OutputSlice empty = OutputSlice.empty();
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.length()).isEqualTo(0);
        assertThat(empty.toByteArray()).isEmpty();
        assertThat(empty.asUtf8String()).isEmpty();
        assertThat(empty.asString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(empty.subSlice(0, 5)).isSameAs(empty);
        assertThat(empty.subSlice(0)).isSameAs(empty);
        assertThat(empty.contentEquals(OutputSlice.from(new byte[0]))).isTrue();
        assertThat(empty.contentEquals(OutputSlice.from(new byte[] {1}))).isFalse();
        assertThat(OutputSlice.from((byte[]) null)).isSameAs(empty);
        assertThat(OutputSlice.from(new byte[0])).isSameAs(empty);
        assertThat(OutputSlice.from((byte[]) null, 0, 5)).isSameAs(empty);
        assertThat(OutputSlice.from(new byte[] {1, 2}, 0, 0)).isSameAs(empty);
        assertThat(OutputSlice.from(new byte[] {1, 2}, 5, 2)).isSameAs(empty);
        assertThat(OutputSlice.from((StreamDrainer) null, 0, 5)).isSameAs(empty);
    } // testEmptySlice

    @Test
    @DisplayName("direct byte slice operations and slicing")
    void testDirectByteSlice() {
        byte[] bytes = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        OutputSlice slice = OutputSlice.from(bytes);

        assertThat(slice.isEmpty()).isFalse();
        assertThat(slice.length()).isEqualTo(bytes.length);
        assertThat(slice.toByteArray()).isEqualTo(bytes);
        assertThat(slice.asUtf8String()).isEqualTo("Hello, World!");
        assertThat(slice.toString()).isEqualTo("Hello, World!");

        OutputSlice sub = slice.subSlice(7, 5);
        assertThat(sub.asUtf8String()).isEqualTo("World");
        assertThat(sub.length()).isEqualTo(5);

        OutputSlice clampedSub = slice.subSlice(7, 100);
        assertThat(clampedSub.asUtf8String()).isEqualTo("World!");

        OutputSlice zeroSub = slice.subSlice(0, 0);
        assertThat(zeroSub.isEmpty()).isTrue();

        OutputSlice oobSub = slice.subSlice(50, 10);
        assertThat(oobSub.isEmpty()).isTrue();

        OutputSlice negSub = slice.subSlice(-5, 5);
        assertThat(negSub.asUtf8String()).isEqualTo("Hello");

        OutputSlice singleArgSub = slice.subSlice(7);
        assertThat(singleArgSub.asUtf8String()).isEqualTo("World!");

        OutputSlice singleArgNeg = slice.subSlice(-2);
        assertThat(singleArgNeg).isSameAs(slice);

        OutputSlice singleArgOob = slice.subSlice(100);
        assertThat(singleArgOob.isEmpty()).isTrue();

        OutputSlice fromSubrange = OutputSlice.from(bytes, 0, 5);
        assertThat(fromSubrange.asUtf8String()).isEqualTo("Hello");

        OutputSlice fromSubrangeClamped = OutputSlice.from(bytes, -2, 5);
        assertThat(fromSubrangeClamped.asUtf8String()).isEqualTo("Hello");
    } // testDirectByteSlice

    @Test
    @DisplayName("drainer backed slice operations")
    void testDrainerBackedSlice() throws Exception {
        byte[] data = "StreamDrainer Output Test".getBytes(StandardCharsets.UTF_8);
        try (StreamDrainer drainer = new StreamDrainer(new ByteArrayInputStream(data))) {
            drainer.waitForEof(1000);
            OutputSlice slice = drainer.snapshotOutput();
            assertThat(slice.length()).isEqualTo(data.length);
            assertThat(slice.asUtf8String()).isEqualTo("StreamDrainer Output Test");
            assertThat(slice.toByteArray()).isEqualTo(data);

            OutputSlice sub = slice.subSlice(14, 6);
            assertThat(sub.asUtf8String()).isEqualTo("Output");

            OutputSlice direct = OutputSlice.from("Output".getBytes(StandardCharsets.UTF_8));
            assertThat(sub.contentEquals(direct)).isTrue();
            assertThat(direct.contentEquals(sub)).isTrue();
            assertThat(sub.contentEquals(OutputSlice.from("Outxyz".getBytes(StandardCharsets.UTF_8)))).isFalse();

            assertThat(drainer.getBytes(0, 0)).isEmpty();
            assertThat(drainer.getBytes(500, 10)).isEmpty();
            assertThat(drainer.getString(0, 0, StandardCharsets.UTF_8)).isEmpty();
            assertThat(drainer.getString(500, 10, StandardCharsets.UTF_8)).isEmpty();

            OutputSlice zeroLenSlice = OutputSlice.from(drainer, 0, 0);
            assertThat(zeroLenSlice.isEmpty()).isTrue();
        } // try
    } // testDrainerBackedSlice

    @Test
    @DisplayName("content equality compares bytes accurately without allocations")
    void testContentEquals() {
        OutputSlice s1 = OutputSlice.from("Test".getBytes(StandardCharsets.UTF_8));
        OutputSlice s2 = OutputSlice.from("Test".getBytes(StandardCharsets.UTF_8));
        OutputSlice s3 = OutputSlice.from("Testing".getBytes(StandardCharsets.UTF_8)).subSlice(0, 4);
        OutputSlice diff = OutputSlice.from("Best".getBytes(StandardCharsets.UTF_8));

        assertThat(s1.contentEquals(s1)).isTrue();
        assertThat(s1.contentEquals(s2)).isTrue();
        assertThat(s1.contentEquals(s3)).isTrue();
        assertThat(s3.contentEquals(s1)).isTrue();
        assertThat(s1.contentEquals(diff)).isFalse();
        assertThat(s1.contentEquals(null)).isFalse();

        OutputSlice diffLen = OutputSlice.from("Longer text".getBytes(StandardCharsets.UTF_8));
        assertThat(s1.contentEquals(diffLen)).isFalse();

        assertThat(s1.equals(s1)).isTrue();
        assertThat(s1.equals(s2)).isTrue();
        assertThat(s1.equals(diff)).isFalse();
        assertThat(s1.equals(null)).isFalse();
        assertThat(s1.equals("Not a slice")).isFalse();
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    } // testContentEquals

    @Test
    @DisplayName("sanitizeDebuggeeStderrSlice cleans banners or preserves clean output")
    void testSanitizeSlice() {
        assertThat(DebugTraceHelper.sanitizeDebuggeeStderrSlice(null).isEmpty()).isTrue();
        assertThat(DebugTraceHelper.sanitizeDebuggeeStderrSlice(OutputSlice.empty()).isEmpty()).isTrue();

        OutputSlice clean = OutputSlice.from("Clean err\n".getBytes(StandardCharsets.UTF_8));
        assertThat(DebugTraceHelper.sanitizeDebuggeeStderrSlice(clean)).isSameAs(clean);

        String banner = "Picked up JAVA_TOOL_OPTIONS: -Dtest=true\nActual Error\n";
        OutputSlice withBanner = OutputSlice.from(banner.getBytes(StandardCharsets.UTF_8));
        OutputSlice sanitized = DebugTraceHelper.sanitizeDebuggeeStderrSlice(withBanner);
        assertThat(sanitized.asUtf8String()).isEqualTo("Actual Error\n");
    } // testSanitizeSlice

    @Test
    @DisplayName("execution snapshot with slices retains backwards compatibility")
    void testExecutionSnapshotWithSlices() {
        OutputSlice stdout = OutputSlice.from("hello".getBytes(StandardCharsets.UTF_8));
        OutputSlice stderr = OutputSlice.from("err".getBytes(StandardCharsets.UTF_8));
        ExecutionSnapshot snapshot = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(),
                stdout, stderr, Optional.of("Main.java"), "in", 2);

        assertThat(snapshot.stdout()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.stderr()).isEqualTo("err".getBytes(StandardCharsets.UTF_8));
        assertThat(snapshot.stdoutSlice()).isSameAs(stdout);
        assertThat(snapshot.stderrSlice()).isSameAs(stderr);
        assertThat(snapshot.stdoutLength()).isEqualTo(5);
        assertThat(snapshot.stderrLength()).isEqualTo(3);
        assertThat(snapshot.sourcePath()).contains("Main.java");
        assertThat(snapshot.stdinConsumed()).isEqualTo("in");
        assertThat(snapshot.stdinOffset()).isEqualTo(2);

        ExecutionSnapshot rawCtor = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(),
                "out".getBytes(StandardCharsets.UTF_8),
                "err".getBytes(StandardCharsets.UTF_8));
        assertThat(rawCtor.stdoutLength()).isEqualTo(3);
        assertThat(rawCtor.stderrLength()).isEqualTo(3);
        assertThat(rawCtor.sourcePath()).isEmpty();
        assertThat(rawCtor.stdinConsumed()).isEmpty();
        assertThat(rawCtor.stdinOffset()).isEqualTo(0);

        ExecutionSnapshot rawPathCtor = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(),
                "out".getBytes(StandardCharsets.UTF_8),
                "err".getBytes(StandardCharsets.UTF_8),
                Optional.of("Other.java"));
        assertThat(rawPathCtor.sourcePath()).contains("Other.java");

        OutputSlice offsetSlice = OutputSlice.from("0123456789".getBytes(StandardCharsets.UTF_8), 2, 4);
        assertThat(offsetSlice.toByteArray()).isEqualTo("2345".getBytes(StandardCharsets.UTF_8));

        ExecutionSnapshot nullSnapshot = new ExecutionSnapshot(
                List.of(), List.of(), Map.of(),
                (OutputSlice) null, (OutputSlice) null, null, null, 0);
        assertThat(nullSnapshot.stdoutSlice().isEmpty()).isTrue();
        assertThat(nullSnapshot.stderrSlice().isEmpty()).isTrue();
        assertThat(nullSnapshot.sourcePath()).isEmpty();
        assertThat(nullSnapshot.stdinConsumed()).isEmpty();
    } // testExecutionSnapshotWithSlices
} // OutputSliceTest
