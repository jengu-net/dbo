package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.auth.ZoneModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zone's subject system is a field of a record, and it is read as one.
 *
 * <p>It used to be pulled out of the payload's text with a pattern, which
 * answered two questions wrongly and neither loudly. Both are here because
 * neither would have announced itself: a zone resolving subjects in the wrong
 * system does not fail, it matches the wrong people, and a zone resolving them
 * in a system whose name is a JSON document matches nobody at all — which
 * reads exactly like a zone nobody has registered with yet.
 */
class AZoneReadsItsSubjectSystemRatherThanMatchingItTest {

    private static final String REAL = "https://eesti.ee/isikukood";

    @Test
    @DisplayName("the system is the field named system, not the last thing in the document "
            + "that looks like one")
    void theFieldRatherThanTheLastThingShapedLikeIt() {
        // A NESTED field of the same name, which is the only way a document
        // can hold the text twice: inside valid JSON a value cannot contain
        // an unescaped quote, so a second "system" is always another field
        // rather than somebody's prose. A domain that records what it
        // superseded is the obvious one to grow.
        byte[] payload = ("{\"use\":\"person-primary\",\"system\":\"" + REAL + "\","
                + "\"status\":\"active\",\"supersedes\":{\"system\":\"https://old/\"}}")
                .getBytes(StandardCharsets.UTF_8);

        assertEquals(Optional.of(REAL), ZoneModel.identifierDomainSystem(payload),
                "the domain answered with something else in the document that looked like a "
                        + "system, so this zone would resolve its subjects in a namespace "
                        + "nobody declared — and would not fail, it would match the wrong "
                        + "people");
    }

    @Test
    @DisplayName("and a domain that names no system answers with nothing, rather than with "
            + "the whole document")
    void aDomainThatNamesNoSystemIsNotTheWholeDocument() {
        byte[] payload = "{\"use\":\"person-primary\",\"status\":\"active\"}"
                .getBytes(StandardCharsets.UTF_8);

        Optional<String> system = ZoneModel.identifierDomainSystem(payload);

        assertTrue(system.isEmpty(),
                "a record naming no system answered '" + system.orElse("")
                        + "'. A replacement that matches nothing returns what it was given, "
                        + "so the answer was the payload itself — and the zone went on to "
                        + "resolve subjects in a system whose name is a JSON object, which "
                        + "matches nobody and looks like a zone nobody has registered with");
    }

    @Test
    @DisplayName("and a blank one is no answer either, because a namespace of nothing is not "
            + "a namespace")
    void aBlankSystemIsNotASystem() {
        byte[] payload = "{\"use\":\"person-primary\",\"system\":\"\",\"status\":\"active\"}"
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(ZoneModel.identifierDomainSystem(payload).isEmpty(),
                "an empty system was handed back as if it were one, so the fall-back to the "
                        + "configured system never happened and every subject resolves under "
                        + "the empty namespace");
    }

    @Test
    @DisplayName("what the zone writes is what the zone reads")
    void theWriterAndTheReaderAgree() {
        // The pair that matters most and is easiest not to check: these two
        // methods sit beside each other and are the only account of this
        // record's shape.
        assertEquals(Optional.of(REAL), ZoneModel.identifierDomainSystem(
                        ZoneModel.identifierDomainPayload(ZoneModel.USE_PERSON_PRIMARY, REAL)),
                "the domain cannot read back what it just wrote");
    }
}
