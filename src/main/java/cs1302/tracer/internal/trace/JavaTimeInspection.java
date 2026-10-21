package cs1302.tracer.internal.trace;

import com.sun.jdi.Field;
import com.sun.jdi.ReferenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Selects stored value components of recognized JDK time objects without invoking guest methods.
 * Layouts are validated before heap inspection reads values or admits descendants. Unknown layouts
 * retain the ordinary opaque platform-object fallback. This helper retains no guest or job state.
 */
final class JavaTimeInspection {

    /** Prevents instantiation. */
    private JavaTimeInspection() {} // JavaTimeInspection

    /**
     * Selects a complete supported layout in presentation order.
     *
     * @param type Exact guest runtime type.
     * @return Selected instance fields, or empty for an unsupported type or layout.
     */
    static Optional<List<Field>> fields(ReferenceType type) {
        List<String> layout = switch (type.name()) {
        case "java.time.LocalDate" -> List.of("year:I", "month:S", "day:S");
        case "java.time.LocalTime" -> List.of("hour:B", "minute:B", "second:B", "nano:I");
        case "java.time.LocalDateTime" -> List.of("date:Ljava/time/LocalDate;", "time:Ljava/time/LocalTime;");
        case "java.time.Duration", "java.time.Instant" -> List.of("seconds:J", "nanos:I");
        case "java.time.Period" -> List.of("years:I", "months:I", "days:I");
        case "java.time.Year" -> List.of("year:I");
        case "java.time.YearMonth" -> List.of("year:I", "month:I");
        case "java.time.MonthDay" -> List.of("month:I", "day:I");
        case "java.time.OffsetTime" -> List.of("time:Ljava/time/LocalTime;", "offset:Ljava/time/ZoneOffset;");
        case "java.time.OffsetDateTime" ->
            List.of("dateTime:Ljava/time/LocalDateTime;", "offset:Ljava/time/ZoneOffset;");
        case "java.time.ZonedDateTime" -> List.of("dateTime:Ljava/time/LocalDateTime;",
                "offset:Ljava/time/ZoneOffset;", "zone:Ljava/time/ZoneId;");
        case "java.time.ZoneOffset" -> List.of("totalSeconds:I");
        case "java.time.ZoneRegion" -> List.of("id:Ljava/lang/String;");
        default -> List.of();
        }; // switch
        if (layout.isEmpty()) {
            return Optional.empty();
        } // if
        List<Field> selected = new ArrayList<>(layout.size());
        for (String component : layout) {
            int separator = component.indexOf(':');
            Field field = type.fieldByName(component.substring(0, separator));
            if (field == null || field.isStatic() || !field.signature().equals(component.substring(separator + 1))) {
                return Optional.empty();
            } // if
            selected.add(field);
        } // for
        return Optional.of(List.copyOf(selected));
    } // fields

} // JavaTimeInspection
