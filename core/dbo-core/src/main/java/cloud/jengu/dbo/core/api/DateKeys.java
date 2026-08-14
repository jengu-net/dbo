package cloud.jengu.dbo.core.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /** Lenient FHIR-ish date input: instant, offset date-time, or plain date (UTC start of day). */
    public static String ofSearchValue(String value) {
        if (value.contains("T")) {
            try {
                return of(Instant.parse(value));
            } catch (Exception e) {
                return of(OffsetDateTime.parse(value).toInstant());
            }
        }
        return of(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
