package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.DateKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A date search value names a span, at whatever precision it was written (#53).
 *
 * <p>FHIR says {@code 2020}, {@code 2020-01}, {@code 2020-01-01} and a full
 * instant are all dates. Read as instants they are not: {@code 2020} does not
 * parse at all, and {@code 2020-01-01} means midnight exactly — so a search for
 * the day matches nothing that happened during it, which is the failure that
 * looks most like working.
 */
class ADateIsTheSpanItNamesTest {

    @Test
    @DisplayName("a year, a month and a day are each the whole of themselves")
    void everyPrecisionIsASpan() {
        assertEquals(new DateKeys.Window(
                        Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2021-01-01T00:00:00Z")),
                DateKeys.window("2020"));
        assertEquals(new DateKeys.Window(
                        Instant.parse("2020-02-01T00:00:00Z"), Instant.parse("2020-03-01T00:00:00Z")),
                DateKeys.window("2020-02"),
                "a month is as long as that month is — February, and a leap year at that");
        assertEquals(new DateKeys.Window(
                        Instant.parse("2020-02-29T00:00:00Z"), Instant.parse("2020-03-01T00:00:00Z")),
                DateKeys.window("2020-02-29"));
    }

    @Test
    @DisplayName("and a stated instant is its own span, so the spans tile")
    void anInstantIsItsOwnSpan() {
        DateKeys.Window window = DateKeys.window("2020-02-29T10:00:00Z");

        assertEquals(Instant.parse("2020-02-29T10:00:00Z"), window.from());
        assertTrue(window.until().isAfter(window.from()),
                "a half-open window nothing can fall into matches nothing");
        assertEquals(DateKeys.window("2020-02-29").until(), DateKeys.window("2020-03-01").from(),
                "the day after starts where this one stops: a moment belongs to exactly one");
    }

    @Test
    @DisplayName("an offset is honoured rather than assumed to be UTC")
    void anOffsetIsHonoured() {
        assertEquals(Instant.parse("2020-02-29T08:00:00Z"),
                DateKeys.window("2020-02-29T10:00:00+02:00").from());
    }

    @Test
    @DisplayName("and a date that is not one is the caller's mistake, not the server's")
    void nonsenseIsTheCallersMistake() {
        // DateTimeParseException is not an IllegalArgumentException, so it
        // misses the router's 400 mapping and answers 500 — which told a caller
        // the server broke when their date did.
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> DateKeys.window("2020-13-45"));

        assertTrue(refusal.getMessage().contains("2020-13-45"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("yyyy"),
                "a refusal must say what would have worked: " + refusal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DateKeys.window("last tuesday"));
    }
}
