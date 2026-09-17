package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamDrainerFailureTest {
    private static class ControlledInput extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public int read() throws IOException {
            entered.countDown();
            try { release.await(); } catch (InterruptedException ex) { throw new IOException(ex); }
            return -1;
        }
        @Override public void close() throws IOException { throw new IOException("close failure"); }
    }

    @Test
    void preservesInterruptAcrossSyncEofWaitAndClose() throws Exception {
        var input = new ControlledInput();
        var drainer = new StreamDrainer(input);
        try {
            assertThat(input.entered.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
            drainer.sync();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            drainer.waitForEof(1000);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            drainer.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            drainer.sync();
        } finally {
            Thread.interrupted();
            input.release.countDown();
            drainer.waitForEof(1000);
            drainer.close();
        }
    }

    @Test
    void interruptionDuringQuietPeriodDoesNotLoseInterrupt() throws Exception {
        var input = new ControlledInput();
        var calls = new AtomicInteger();
        var drainer = new StreamDrainer(input) {
            @Override public int size() {
                if (calls.incrementAndGet() == 1) return 0;
                Thread.currentThread().interrupt();
                return 1;
            }
        };
        try {
            assertThat(input.entered.await(1, TimeUnit.SECONDS)).isTrue();
            drainer.sync(1000, 1000);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            input.release.countDown();
            drainer.waitForEof(1000);
            drainer.close();
        }
    }

    @Test
    void zeroWaitReturnsAndCloseStopsAfterAnInFlightRead() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        InputStream source = new InputStream() {
            @Override public int read() { throw new AssertionError("bulk read expected"); }
            @Override public int read(byte[] bytes) throws IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
                bytes[0] = 65;
                return 1;
            }
            @Override public void close() { release.countDown(); }
        };
        var drainer = new StreamDrainer(source);
        try {
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            drainer.sync(0, 0);
            assertThat(drainer.size()).isZero();
            drainer.close();
            drainer.waitForEof(1000);
            assertThat(drainer.getBytes()).containsExactly((byte) 65);
            assertThat(drainer.isEof()).isFalse();
        } finally {
            drainer.close();
        }
    }

    @Test
    void syncObservesEofThatArrivesAfterItsInitialCheck() throws Exception {
        var input = new ControlledInput();
        var calls = new AtomicInteger();
        try (var drainer = new StreamDrainer(input) {
            @Override public int size() {
                if (calls.incrementAndGet() == 1) {
                    input.release.countDown();
                    waitForEof(1000);
                    assertThat(isEof()).isTrue();
                }
                return super.size();
            }
        }) {
            assertThat(input.entered.await(1, TimeUnit.SECONDS)).isTrue();
            drainer.sync(1000, 1000);
            assertThat(drainer.isEof()).isTrue();
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test
    void zeroQuietPeriodStopsPollingAsSoonAsBytesArrive() throws Exception {
        var input = new ControlledInput();
        var calls = new AtomicInteger();
        var drainer = new StreamDrainer(input) {
            @Override public int size() { return calls.getAndIncrement() == 0 ? 0 : 1; }
        };
        try {
            assertThat(input.entered.await(1, TimeUnit.SECONDS)).isTrue();
            drainer.sync(1000, 0);
            assertThat(calls.get()).isEqualTo(2);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            input.release.countDown();
            drainer.waitForEof(1000);
            drainer.close();
        }
    }
}
