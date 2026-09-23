package cloud.jengu.dbo.fhir.validate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One FHIR date, dateTime or instant, at the moment its span opens.
 *
 * <p>A date is written at the precision the author had: 2020, 2020-01, a full
 * day, a full instant. Each names a span, and reading one as a moment is how
 * 2020 becomes the first of January — so the lower bound is taken on purpose
 * and the key is fixed-width UTC, which makes lexicographic order
 * chronological.
 *
 * <p>The same rule as {@code dbo.date_key}, and it has to be the same
 * character for character: the two envelopes are compared over everything the
 * version publishes, and a key that differed would be a document findable
 * from one side and not the other.
 */
final class Dates {

    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private Dates() {
    }

    /** The key, or null where the text is not a date this recognises. */
    static String key(String value) {
        if (value == null) {
            return null;
        }
        if (value.matches("[0-9]{4}")) {
            return value + "-01-01T00:00:00.000Z";
        }
        if (value.matches("[0-9]{4}-[0-9]{2}")) {
            return value + "-01T00:00:00.000Z";
        }
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return value + "T00:00:00.000Z";
        }
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*")) {
            try {
                return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC)
                        .format(UTC);
            } catch (RuntimeException notAnOffset) {
                try {
                    return Instant.parse(value).atZone(ZoneOffset.UTC).format(UTC);
                } catch (RuntimeException notAnInstant) {
                    return null;
                }
            }
        }
        return null;
    }
}
