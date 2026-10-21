import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Demonstrates immutable date/time values and shared references. */
public class Main {

    public static void main(String[] args) {
        LocalDate date = LocalDate.of(2024, 2, 29);
        LocalTime time = LocalTime.of(12, 30, 0, 123456789);
        LocalDateTime meeting = LocalDateTime.of(date, time);
        Duration elapsed = Duration.ofNanos(-1);
        Duration original = elapsed;
        elapsed = elapsed.plusSeconds(2);
        System.out.println(meeting);
        System.out.println(original);
        System.out.println(elapsed);
    } // main

} // Main
