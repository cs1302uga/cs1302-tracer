package cs1302.tracer.internal;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_EXTRA_FIELDS;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/** Protect the partial-object, ordered-array comparisons used by CLI tests. */
class JsonComparisonTest {

    @Test
    void acceptsExtraObjectFieldsAndEquivalentNumbers() {
        assertThatJson("{\"trace\":[{\"line\":1.0,\"extra\":true}],\"other\":2}")
            .when(IGNORING_EXTRA_FIELDS).withTolerance(0)
            .isEqualTo("{\"trace\":[{\"line\":1}]}");
    }

    @Test
    void rejectsMissingFieldsWrongValuesAndChangedArrays() {
        String expected = "{\"trace\":[{\"line\":1},{\"line\":2}]}";
        String[] mismatches = {
            "{}",
            "{\"trace\":[{}, {\"line\":2}]}",
            "{\"trace\":[{\"line\":null},{\"line\":2}]}",
            "{\"trace\":[{\"line\":1.01},{\"line\":2}]}",
            "{\"trace\":[{\"line\":\"1\"},{\"line\":2}]}",
            "{\"trace\":[{\"line\":2},{\"line\":1}]}",
            "{\"trace\":[{\"line\":1}]}",
            "{\"trace\":[{\"line\":1},{\"line\":2},{\"line\":3}]}"
        };
        for (String actual : mismatches) {
            assertThatThrownBy(() -> assertThatJson(actual).when(IGNORING_EXTRA_FIELDS)
                .withTolerance(0).isEqualTo(expected))
                .as("reject %s", actual).isInstanceOf(AssertionError.class);
        }
    }

}
