package cloud.jengu.dbo.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Engine contract for DATE envelope keys: FIXED-WIDTH UTC ISO text, so that
 * lexicographic order equals chronological order and indexes stay IMMUTABLE
 * (a text→timestamptz cast is only STABLE). Personalities compile date search
 * values through this; the storage codec writes envelope date values with it.
 */
public final class DateKeys {

    public static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private DateKeys() {}

    public static String of(Instant instant) {
        return FORMAT.format(instant);
    }

    /** The earliest moment a search value can mean — its window's lower bound. */
    public static String ofSearchValue(String value) {
        return of(window(value).from());
    }

    /**
     * What a date search value means, as a half-open window.
     *
     * <p>A FHIR date is written at the precision the author had:
     * {@code 2020}, {@code 2020-01}, {@code 2020-01-01} and a full instant are
     * all valid, and each names a <b>span</b> rather than a moment — a year, a
     * month, a day. Reading one as an instant is how {@code 2020} becomes
     * unparseable and {@code 2020-01-01} comes to mean midnight exactly,
     * matching nothing that happened during the day it names.
     *
     * <p>Half-open so the spans tile without overlapping: a moment belongs to
     * exactly one day, and the day after starts where this one stops.
     */
    public record Window(Instant from, Instant until) {}

    public static Window window(String value) {
        try {
            if (value.contains("T")) {
                Instant at = instant(value);
                // A stated instant is its own span, to the last unit it stated.
                return new Window(at, at.plusMillis(1));
            }
            return switch (value.length()) {
                case 4 -> ofYear(Integer.parseInt(value));
                case 7 -> ofMonth(YearMonth.parse(value));
                default -> ofDay(LocalDate.parse(value));
            };
        } catch (RuntimeException e) {
            // The caller's mistake, and said as one: a DateTimeParseException
            // is not an IllegalArgumentException, so it misses a router's 400
            // and answers 500 — telling a caller the server broke when their
            // date did.
            throw new IllegalArgumentException(
                    "not a FHIR date: '" + value + "' (expected yyyy, yyyy-mm, yyyy-mm-dd "
                            + "or an instant)", e);
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return OffsetDateTime.parse(value).toInstant();
        }
    }

    private static Window ofYear(int year) {
        return new Window(LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static Window ofMonth(YearMonth month) {
        return new Window(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static Window ofDay(LocalDate day) {
        return new Window(day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
