package cs1302.tracer.trace;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class BreakpointSelectionTest {
    @Test void normalizesPathsDeduplicatesAndMatchesExactLocations() {
        var selected = new BreakpointSelection(List.of("./p/Helper.java:4", "p\\Helper.java:4",
                "q/Helper.java:4", "q/Helper.java:7"));
        assertThat(selected.qualified()).isTrue();
        assertThat(selected.lines()).containsExactly(4, 7);
        assertThat(selected.matches("p\\Helper.java", 4)).isTrue();
        assertThat(selected.matches("q/Helper.java", 4)).isTrue();
        assertThat(selected.matches("Helper.java", 4)).isFalse();
        assertThat(selected.matches("p/Helper.java", 7)).isFalse();
        selected.validate(Map.of("p/Helper.java", Set.of(4), "q/Helper.java", Set.of(4, 7)));
        assertThatThrownBy(() -> selected.validate(Map.of("p/Helper.java", Set.of(4))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No executable breakpoint");
        assertThatThrownBy(() -> selected.validate(Map.of("p/Helper.java", Set.of(5))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new BreakpointSelection(List.of()).qualified()).isFalse();
    }

    @Test void rejectsNonRelativePathsAndInvalidLines() {
        for (String selector : List.of("Helper.java", ":4", "Helper.java:", "Helper.java:no",
                "Helper.java:0", "Helper.java:-1", "/Helper.java:4", "C:\\Helper.java:4",
                "../Helper.java:4", "p/../Helper.java:4", "Helper.java:999999999999")) {
            assertThatThrownBy(() -> new BreakpointSelection(List.of(selector)))
                    .as(selector).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
