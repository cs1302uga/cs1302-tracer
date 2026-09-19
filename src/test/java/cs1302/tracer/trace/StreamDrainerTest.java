package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cs1302.tracer.execution.InspectionPolicy;
import cs1302.tracer.execution.TraceLimits;
import cs1302.tracer.execution.TraceSession;
import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class StreamDrainerTest {

  @Test
  void testStartsEmpty() {
    try (StreamDrainer drainer = new StreamDrainer(new ByteArrayInputStream(new byte[0]))) {
      assertThat(drainer.size()).isZero();
      assertThat(drainer.isEof()).isFalse();
      assertThat(drainer.getBytes()).isEmpty();
    }
  }

  @Test
  void testRejectsNullSource() {
    assertThatThrownBy(() -> new StreamDrainer(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testSubrangeAndSearchOperations() throws Exception {
    byte[] data = "hello world, hello test".getBytes(StandardCharsets.UTF_8);
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      pos.write(data);
      pos.close();
      drainer.sync();

      assertThat(drainer.startsWith(0, 5, "hello".getBytes(StandardCharsets.UTF_8))).isTrue();
      assertThat(drainer.startsWith(0, 5, null)).isTrue();
      assertThat(drainer.startsWith(0, 5, new byte[0])).isTrue();
      assertThat(drainer.startsWith(0, 5, "world".getBytes(StandardCharsets.UTF_8))).isFalse();
      assertThat(drainer.startsWith(0, 2, "hello".getBytes(StandardCharsets.UTF_8))).isFalse();

      assertThat(drainer.indexOf(0, 10, (byte) 'w', 0)).isEqualTo(6);
      assertThat(drainer.indexOf(0, 5, (byte) 'w', 0)).isEqualTo(-1);

      assertThat(drainer.getString(6, 5, StandardCharsets.UTF_8)).isEqualTo("world");
      assertThat(drainer.getString(-1, 5, StandardCharsets.UTF_8)).isEqualTo("hello");
      assertThat(drainer.getString(100, 5, StandardCharsets.UTF_8)).isEmpty();

      StringBuilder sb = new StringBuilder();
      drainer.forEachByte(0, 5, b -> sb.append((char) b));
      assertThat(sb.toString()).isEqualTo("hello");

      assertThat(drainer.byteAt(0)).isEqualTo((byte) 'h');
      assertThat(drainer.getBytes(6, 5)).isEqualTo("world".getBytes(StandardCharsets.UTF_8));
      assertThat(drainer.getBytes(-1, 5)).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
      assertThat(drainer.getBytes(100, 5)).isEmpty();
    }
  }

  @Test
  void testSyncTimeoutAndEof() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      drainer.sync(10, 2);
      pos.write("delayed".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.sync(50, 5);
      assertThat(drainer.size()).isEqualTo(7);

      pos.close();
      drainer.waitForEof(1000);
      assertThat(drainer.isEof()).isTrue();
    } finally {
      pos.close();
    }
  }

  @Test
  @DisplayName("Reset clears accumulated buffer")
  void testResetBuffer() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      pos.write("abc".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.sync(100, 5);
      assertThat(drainer.size()).isEqualTo(3);

      drainer.reset();
      assertThat(drainer.size()).isZero();

      pos.write("def".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      Thread.sleep(10);
      drainer.sync(100, 5);
      assertThat(drainer.size()).isEqualTo(3);
      assertThat(new String(drainer.getBytes(), StandardCharsets.UTF_8)).isEqualTo("def");
      pos.close();
    }
  }

  @Test
  @DisplayName("Dynamic session attachment enforces output limit")
  void testAttachSessionEnforcesLimit() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    TraceLimits limits = new TraceLimits(100, 5000, 5, 100, 100, 10000, 10000, 10);
    try (TraceSession session = new TraceSession(limits, InspectionPolicy.TRUSTED, true);
         StreamDrainer drainer = new StreamDrainer(pis)) {
      drainer.attachSession(session);

      pos.write("1234567890".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.sync(100, 5);

      assertThat(drainer.size()).isEqualTo(5);
      assertThat(session.isStopped()).isTrue();
      assertThat(session.stopReason()).isEqualTo("output_limit");

      drainer.detachSession();
      pos.close();
    }
  }

  @Test
  @DisplayName("syncUntil waits for expected bytes, handles eof, timeouts, and interruption")
  void testSyncUntil() throws Exception {
    PipedOutputStream pos = new PipedOutputStream();
    PipedInputStream pis = new PipedInputStream(pos);

    try (StreamDrainer drainer = new StreamDrainer(pis)) {
      // 1. Waits until threshold reached
      pos.write("hello".getBytes(StandardCharsets.UTF_8));
      pos.flush();
      drainer.syncUntil(5, 500);
      assertThat(drainer.size()).isEqualTo(5);

      // 2. Timeout when expected bytes not reached
      drainer.syncUntil(20, 10);
      assertThat(drainer.size()).isEqualTo(5);

      // 3. Active unstopped session continues until threshold reached
      TraceLimits limits = TraceLimits.unlimited();
      try (TraceSession session = new TraceSession(limits, InspectionPolicy.TRUSTED, true)) {
        drainer.attachSession(session);
        pos.write("world".getBytes(StandardCharsets.UTF_8));
        pos.flush();
        drainer.syncUntil(10, 500);
        assertThat(drainer.size()).isEqualTo(10);

        // 4. Stopped session breaks loop
        session.stop("test_stop");
        drainer.syncUntil(30, 200);
        drainer.detachSession();
      }

      // 5. Interrupted thread breaks loop and restores interrupt status
      Thread.currentThread().interrupt();
      drainer.syncUntil(40, 200);
      assertThat(Thread.interrupted()).isTrue();

      // 6. Returns immediately if EOF reached
      pos.close();
      drainer.waitForEof(500);
      assertThat(drainer.isEof()).isTrue();
      drainer.syncUntil(100, 100);
    }

    // 7. Returns immediately if closed before EOF
    StreamDrainer closedDrainer = new StreamDrainer(new ByteArrayInputStream(new byte[10]));
    closedDrainer.close();
    closedDrainer.syncUntil(100, 100);

    // 8. While loop exits when EOF reached concurrently
    PipedOutputStream pos2 = new PipedOutputStream();
    PipedInputStream pis2 = new PipedInputStream(pos2);
    try (StreamDrainer drainer2 = new StreamDrainer(pis2)) {
      new Thread(() -> {
        try {
          Thread.sleep(15);
          pos2.close();
        } catch (Exception ignored) {
        }
      }).start();
      drainer2.syncUntil(100, 500);
    }

    // 9. While loop exits when closed concurrently
    PipedOutputStream pos3 = new PipedOutputStream();
    PipedInputStream pis3 = new PipedInputStream(pos3);
    StreamDrainer drainer3 = new StreamDrainer(pis3);
    new Thread(() -> {
      try {
        Thread.sleep(15);
        drainer3.close();
      } catch (Exception ignored) {
      }
    }).start();
    drainer3.syncUntil(100, 500);
  }
}
