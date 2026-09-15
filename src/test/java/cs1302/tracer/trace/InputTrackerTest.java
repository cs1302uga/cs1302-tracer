package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("InputTracker Unit Tests")
public class InputTrackerTest {

    @Test
    @DisplayName("should initialize correctly with null, empty, and populated stdin")
    void testInitialization() {
        InputTracker nullTracker = new InputTracker(null);
        assertThat(nullTracker.stdin()).isEmpty();
        assertThat(nullTracker.consumed()).isEmpty();
        assertThat(nullTracker.unconsumed()).isEmpty();
        assertThat(nullTracker.offset()).isEqualTo(0);
        assertThat(nullTracker.isExhausted()).isTrue();

        InputTracker emptyTracker = new InputTracker("");
        assertThat(emptyTracker.consumed()).isEmpty();
        assertThat(emptyTracker.offset()).isEqualTo(0);
        assertThat(emptyTracker.isExhausted()).isTrue();

        InputTracker tracker = new InputTracker("hello world");
        assertThat(tracker.stdin()).isEqualTo("hello world");
        assertThat(tracker.consumed()).isEmpty();
        assertThat(tracker.unconsumed()).isEqualTo("hello world");
        assertThat(tracker.offset()).isEqualTo(0);
        assertThat(tracker.isExhausted()).isFalse();
    } // testInitialization

    @Test
    @DisplayName("should consume tokens monotonically including leading delimiters")
    void testConsumeToken() {
        InputTracker tracker = new InputTracker("  hello   1302   98.5\n");
        tracker.consumeToken("hello");
        assertThat(tracker.offset()).isEqualTo(7);
        assertThat(tracker.consumed()).isEqualTo("  hello");
        assertThat(tracker.unconsumed()).isEqualTo("   1302   98.5\n");

        tracker.consumeToken("1302");
        assertThat(tracker.offset()).isEqualTo(14);
        assertThat(tracker.consumed()).isEqualTo("  hello   1302");

        tracker.consumeToken("98.5");
        assertThat(tracker.offset()).isEqualTo(21);
        assertThat(tracker.consumed()).isEqualTo("  hello   1302   98.5");
        assertThat(tracker.isExhausted()).isFalse();

        // No-ops
        tracker.consumeToken("");
        tracker.consumeToken(null);
        tracker.consumeToken("notFound");
        assertThat(tracker.offset()).isEqualTo(21);
    } // testConsumeToken

    @Test
    @DisplayName("should consume identical tokens sequentially")
    void testConsumeIdenticalTokens() {
        InputTracker tracker = new InputTracker("word word word");
        tracker.consumeToken("word");
        assertThat(tracker.offset()).isEqualTo(4);
        assertThat(tracker.consumed()).isEqualTo("word");

        tracker.consumeToken("word");
        assertThat(tracker.offset()).isEqualTo(9);
        assertThat(tracker.consumed()).isEqualTo("word word");

        tracker.consumeToken("word");
        assertThat(tracker.offset()).isEqualTo(14);
        assertThat(tracker.consumed()).isEqualTo("word word word");
        assertThat(tracker.isExhausted()).isTrue();
    } // testConsumeIdenticalTokens

    @Test
    @DisplayName("should consume lines including line terminators (LF, CRLF, CR)")
    void testConsumeLine() {
        InputTracker tracker = new InputTracker("line1\nline2\r\nline3\rline4");

        tracker.consumeLine("line1");
        assertThat(tracker.consumed()).isEqualTo("line1\n");
        assertThat(tracker.offset()).isEqualTo(6);

        tracker.consumeLine("line2");
        assertThat(tracker.consumed()).isEqualTo("line1\nline2\r\n");
        assertThat(tracker.offset()).isEqualTo(13);

        tracker.consumeLine("line3");
        assertThat(tracker.consumed()).isEqualTo("line1\nline2\r\nline3\r");
        assertThat(tracker.offset()).isEqualTo(19);

        tracker.consumeLine("line4");
        assertThat(tracker.consumed()).isEqualTo("line1\nline2\r\nline3\rline4");
        assertThat(tracker.offset()).isEqualTo(24);
        assertThat(tracker.isExhausted()).isTrue();

        // Boundary no-ops
        tracker.consumeLine("line5");
        tracker.consumeLine(null);
        assertThat(tracker.offset()).isEqualTo(24);
    } // testConsumeLine

    @Test
    @DisplayName("should consume raw bytes correctly")
    void testConsumeBytes() {
        InputTracker tracker = new InputTracker("abcdef");
        tracker.consumeBytes(2);
        assertThat(tracker.consumed()).isEqualTo("ab");
        assertThat(tracker.offset()).isEqualTo(2);

        tracker.consumeBytes(0);
        tracker.consumeBytes(-1);
        assertThat(tracker.offset()).isEqualTo(2);

        tracker.consumeBytes(10);
        assertThat(tracker.consumed()).isEqualTo("abcdef");
        assertThat(tracker.offset()).isEqualTo(6);
        assertThat(tracker.isExhausted()).isTrue();
    } // testConsumeBytes

} // InputTrackerTest
