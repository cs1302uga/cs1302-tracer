package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StreamDrainer Tests")
public class StreamDrainerTest {

  @Test
  @DisplayName("Constructor throws on null input stream")
  void testNullInputStream() {
    assertThatThrownBy(() -> new StreamDrainer(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Drains existing byte array stream completely")
  void testDrainByteArrayStream() throws Exception {
    byte[] data = "Hello, world!".getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream bais = new ByteArrayInputStream(data);

    try (StreamDrainer drainer = new StreamDrainer(bais)) {
      drainer.sync();
      drainer.waitForEof(200);

      assertThat(drainer.isEof()).isTrue();
      assertThat(drainer.size()).isEqualTo(data.length);
      assertThat(new String(drainer.getBytes(), StandardCharsets.UTF_8))
          .isEqualTo("Hello, world!");
    }
  }

  @Test
  @DisplayName("Sync captures data written dynamically across multiple bursts")
  void testPipedStreamDynamicWrites() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      // Step 1: Write first burst
      pos.write("Step1".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.sync(100, 5);

      assertThat(new String(drainer.getBytes(), StandardCharsets.UTF_8)).isEqualTo("Step1");

      // Step 2: Write second burst
      pos.write("Step2".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.sync(100, 5);

      assertThat(new String(drainer.getBytes(), StandardCharsets.UTF_8)).isEqualTo("Step1Step2");

      // Close output stream to trigger EOF
      pos.close();
      drainer.waitForEof(200);
      assertThat(drainer.isEof()).isTrue();
    }
  }

  @Test
  @DisplayName("Sync returns immediately if no new bytes arrive and stream is quiet")
  void testSyncQuietStream() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      long start = System.currentTimeMillis();
      drainer.sync(200, 10);
      long elapsed = System.currentTimeMillis() - start;

      // Should finish quickly because no bytes are in the pipe
      assertThat(elapsed).isLessThan(100);
      assertThat(drainer.size()).isZero();
      pos.close();
    }
  }
}
