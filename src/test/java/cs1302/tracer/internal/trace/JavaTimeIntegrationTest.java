package cs1302.tracer.internal.trace;

import static org.assertj.core.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cs1302.tracer.api.*;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JavaTimeIntegrationTest {
    private static final String SOURCE = """
            import java.time.*;
            public class Main {
                static Object[] values;
                static class Holder { Duration duration; Holder(Duration d) { duration = d; } }
                public static void main(String[] args) {
                    LocalDate date = LocalDate.of(2024, 2, 29);
                    LocalTime time = LocalTime.of(23, 59, 58, 123456789);
                    LocalDateTime combined = LocalDateTime.of(date, time);
                    Duration duration = Duration.ofNanos(-1);
                    Duration alias = duration;
                    Duration equal = Duration.ofSeconds(-1, 999999999);
                    Holder holder = new Holder(duration);
                    values = new Object[] { date, time, combined, duration, alias, equal, holder,
                        Duration.ZERO, Duration.ofSeconds(12), Duration.ofSeconds(-12),
                        Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE, 999999999),
                        LocalDate.of(-123, 1, 2), Instant.ofEpochSecond(-1, 123),
                        Period.of(1, -2, 3), Year.of(-44), YearMonth.of(2000, 2), MonthDay.of(2, 29),
                        OffsetTime.of(time, ZoneOffset.ofHoursMinutesSeconds(1, 2, 3)),
                        OffsetDateTime.of(combined, ZoneOffset.UTC),
                        ZonedDateTime.of(2024, 11, 3, 1, 30, 0, 0, ZoneId.of("America/New_York"))
                            .withLaterOffsetAtOverlap(), Month.FEBRUARY, DayOfWeek.THURSDAY,
                        java.util.List.of(duration), Clock.systemUTC() };
                    System.out.println(values.length);
                }
            }
            """;

    @ParameterizedTest
    @EnumSource(TraceFormat.class)
    void coreAndAdditionalTypesRetainValuesAndIdentity(TraceFormat format) {
        var outcome = Tracer.trace(TraceRequest.builder(SOURCE).format(format).build());
        assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
        JsonObject heap = lastHeap(JsonParser.parseString(outcome.traceJson()).getAsJsonObject(), format);
        assertValues(heap, format);
    }

    private static void assertValues(JsonObject heap, TraceFormat format) {
        assertThat(objects(heap, format, "java.time.LocalDate")).anySatisfy(fields ->
                assertNumbers(fields, Map.of("year", 2024, "month", 2, "day", 29)));
        assertThat(objects(heap, format, "java.time.LocalDate")).anySatisfy(fields ->
                assertNumbers(fields, Map.of("year", -123, "month", 1, "day", 2)));
        assertThat(objects(heap, format, "java.time.LocalTime")).anySatisfy(fields ->
                assertNumbers(fields, Map.of("hour", 23, "minute", 59, "second", 58, "nano", 123456789)));
        for (long[] expected : new long[][] { {-1, 999999999}, {0, 0}, {12, 0}, {-12, 0}, {0, 1},
                {Long.MAX_VALUE, 999999999} }) {
            assertThat(objects(heap, format, "java.time.Duration")).anySatisfy(fields ->
                    assertNumbers(fields, Map.of("seconds", expected[0], "nanos", expected[1])));
        }
        assertThat(objects(heap, format, "java.time.Instant")).singleElement().satisfies(fields ->
                assertNumbers(fields, Map.of("seconds", -1, "nanos", 123)));
        assertThat(objects(heap, format, "java.time.Period")).singleElement().satisfies(fields ->
                assertNumbers(fields, Map.of("years", 1, "months", -2, "days", 3)));
        assertThat(objects(heap, format, "java.time.Year")).singleElement()
                .satisfies(fields -> assertThat(fields).containsOnlyKeys("year"));
        assertThat(objects(heap, format, "java.time.YearMonth")).singleElement().satisfies(fields ->
                assertNumbers(fields, Map.of("year", 2000, "month", 2)));
        assertThat(objects(heap, format, "java.time.MonthDay")).singleElement().satisfies(fields ->
                assertNumbers(fields, Map.of("month", 2, "day", 29)));
        assertThat(objects(heap, format, "java.time.ZoneOffset")).anySatisfy(fields ->
                assertNumbers(fields, Map.of("totalSeconds", 3723)));
        assertThat(objects(heap, format, "java.time.ZoneOffset")).anySatisfy(fields ->
                assertNumbers(fields, Map.of("totalSeconds", -18000)));
        var combined = objects(heap, format, "java.time.LocalDateTime");
        assertThat(combined).anySatisfy(fields -> {
            assertNumbers(fields(heap.get(ref(fields.get("date"), format)), format),
                    Map.of("year", 2024, "month", 2, "day", 29));
            assertNumbers(fields(heap.get(ref(fields.get("time"), format)), format),
                    Map.of("hour", 23, "minute", 59, "second", 58, "nano", 123456789));
        });
        for (String type : List.of("java.time.OffsetTime", "java.time.OffsetDateTime", "java.time.ZonedDateTime")) {
            assertThat(objects(heap, format, type)).singleElement().satisfies(fields -> {
                assertThat(heap.has(ref(fields.get("offset"), format))).isTrue();
                assertThat(heap.has(ref(fields.get(type.endsWith("OffsetTime") ? "time" : "dateTime"), format)))
                        .isTrue();
            });
        }
        assertThat(objects(heap, format, "java.time.ZoneRegion")).singleElement()
                .satisfies(fields -> assertThat(fields).containsOnlyKeys("id"));
        assertThat(heap.toString()).contains("America/New_York", "FEBRUARY", "THURSDAY");
        // Equal durations remain distinct heap identities; aliasing does not add a third object.
        assertThat(objects(heap, format, "java.time.Duration").stream()
                .filter(fields -> fields.get("seconds").getAsLong() == -1)).hasSize(2);
        JsonArray values = heap.entrySet().stream().map(Map.Entry::getValue).filter(value ->
                format == TraceFormat.MODERN
                    ? value.getAsJsonObject().get("kind").getAsString().equals("array")
                        && value.getAsJsonObject().getAsJsonArray("elements").size() == 25
                    : value.getAsJsonArray().get(0).getAsString().equals("LIST")
                        && value.getAsJsonArray().size() == 26)
                .map(value -> format == TraceFormat.MODERN ? value.getAsJsonObject().getAsJsonArray("elements")
                        : value.getAsJsonArray()).findFirst().orElseThrow();
        int start = format == TraceFormat.MODERN ? 0 : 1;
        assertThat(ref(values.get(start + 3), format)).isEqualTo(ref(values.get(start + 4), format))
                .isNotEqualTo(ref(values.get(start + 5), format));
        var components = fields(heap.get(ref(values.get(start + 2), format)), format);
        assertThat(ref(components.get("date"), format)).isEqualTo(ref(values.get(start), format));
        assertThat(ref(components.get("time"), format)).isEqualTo(ref(values.get(start + 1), format));
        var holder = objects(heap, format, "Main$Holder").getFirst();
        assertThat(ref(holder.get("duration"), format)).isEqualTo(ref(values.get(start + 3), format));
        assertNumbers(fields(heap.get(ref(holder.get("duration"), format)), format),
                Map.of("seconds", -1, "nanos", 999999999));
        assertThat(objects(heap, format, "java.time.Clock$SystemClock")).singleElement().satisfies(fields -> assertThat(fields).isEmpty());
    }

    @Test
    void consecutiveBatchJobsKeepBothFormatsAndValuesIndependent() throws Exception {
        StringBuilder input = new StringBuilder();
        int year = -44;
        for (String format : List.of("modern", "pytutor", "modern", "pytutor")) {
            JsonObject job = new JsonObject();
            job.addProperty("source", SOURCE.replace("Year.of(-44)", "Year.of(" + year-- + ")"));
            job.addProperty("format", format);
            input.append(job).append('\n');
        }
        StringWriter output = new StringWriter();
        try (BatchTracer batch = new BatchTracer(1, 10, 1, false)) {
            batch.processStream(new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)), output);
        }
        var responses = output.toString().lines().toList();
        assertThat(responses).hasSize(4);
        for (int i = 0; i < responses.size(); i++) {
            var result = JsonParser.parseString(responses.get(i)).getAsJsonObject().getAsJsonObject("result");
            assertThat(result.get("complete").getAsBoolean()).as(result.toString()).isTrue();
            TraceFormat format = i % 2 == 0 ? TraceFormat.MODERN : TraceFormat.PYTUTOR;
            var heap = lastHeap(result.getAsJsonObject("trace"), format);
            assertValues(heap, format);
            assertNumbers(objects(heap, format, "java.time.Year").getFirst(), Map.of("year", -44 - i));
        }
    }

    @Test
    void fieldsAndMultithreadKeepRawZoneOffsetStorage() {
        String source = """
                public class Main {
                    static java.time.ZoneOffset offset;
                    static java.time.Duration duration;
                    public static void main(String[] args) {
                        offset = java.time.ZoneOffset.ofHours(3);
                        duration = java.time.Duration.ofNanos(-1);
                        System.out.println(offset);
                    }
                }
                """;
        for (boolean multithread : List.of(false, true)) {
            var outcome = Tracer.trace(TraceRequest.builder(source).format(TraceFormat.MODERN)
                    .inspection(multithread ? InspectionPolicy.TRUSTED : InspectionPolicy.FIELDS)
                    .multithread(multithread).build());
            assertThat(outcome.complete()).as(outcome.diagnostics().toString()).isTrue();
            var payload = JsonParser.parseString(outcome.traceJson()).getAsJsonObject();
            assertThat(payload.getAsJsonArray("steps")).anySatisfy(step -> {
                var heap = step.getAsJsonObject().getAsJsonObject("heap");
                assertThat(objects(heap, TraceFormat.MODERN, "java.time.ZoneOffset")).singleElement()
                        .satisfies(fields -> assertThat(fields).containsKeys("id", "totalSeconds"));
                assertThat(objects(heap, TraceFormat.MODERN, "java.time.Duration")).singleElement()
                        .satisfies(fields -> assertNumbers(fields, Map.of("seconds", -1, "nanos", 999999999)));
            });
        }
    }

    static JsonObject lastHeap(JsonObject trace, TraceFormat format) {
        JsonArray steps = trace.getAsJsonArray(format == TraceFormat.MODERN ? "steps" : "trace");
        return steps.get(steps.size() - 1).getAsJsonObject().getAsJsonObject("heap");
    }

    static List<Map<String, JsonElement>> objects(JsonObject heap, TraceFormat format, String name) {
        return heap.entrySet().stream().map(Map.Entry::getValue).filter(value -> {
            if (format == TraceFormat.MODERN) {
                var object = value.getAsJsonObject();
                return object.get("kind").getAsString().equals("object")
                        && object.get("type").getAsString().equals(name);
            }
            var object = value.getAsJsonArray();
            return object.get(0).getAsString().equals("INSTANCE") && object.get(1).getAsString().equals(name);
        }).map(value -> fields(value, format)).toList();
    }

    static Map<String, JsonElement> fields(JsonElement value, TraceFormat format) {
        Map<String, JsonElement> result = new LinkedHashMap<>();
        if (format == TraceFormat.MODERN) {
            for (var field : value.getAsJsonObject().getAsJsonArray("fields")) {
                var object = field.getAsJsonObject();
                result.put(object.get("name").getAsString(), object.get("value"));
            }
        } else {
            var object = value.getAsJsonArray();
            for (int i = 2; i < object.size(); i++) {
                var field = object.get(i).getAsJsonArray();
                result.put(field.get(0).getAsString(), field.get(1));
            }
        }
        return result;
    }

    static String ref(JsonElement value, TraceFormat format) {
        return format == TraceFormat.MODERN ? value.getAsJsonObject().get("ref").getAsString()
                : value.getAsJsonArray().get(1).getAsString();
    }

    private static void assertNumbers(Map<String, JsonElement> fields, Map<String, ? extends Number> expected) {
        assertThat(fields.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((key, value) -> assertThat(fields.get(key).getAsLong()).as(key).isEqualTo(value.longValue()));
    }
}
