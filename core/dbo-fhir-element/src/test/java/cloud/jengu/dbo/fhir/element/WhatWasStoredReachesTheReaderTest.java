package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reader receives what was stored, and the store's own ancestor slots
 * (REQ-DBO-CORE-READER-RECEIVES-THE-DECLARED-FORM).
 *
 * <p>The obvious way to put an id into a document is to read it into a model
 * and write it back, and it loses data: a model writes what the version's
 * StructureDefinitions describe, so an element the version does not define is
 * dropped on the way out. Nothing is lost in storage — the payload is kept as it
 * arrived — but a reader is handed less than was written, silently, which is
 * worse than a refusal.
 *
 * <p>That is also why this face declares no normalized truth form
 * (REQ-DBO-VER-NORMALISING-LOSES-NOTHING): it would not pass that requirement's
 * own round trip, and the test below is that round trip.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatWasStoredReachesTheReaderTest {

    private final ElementVersion version = ElementVersion.of("r4");

    private String read(String stored) {
        return new String(ElementAncestors.rendered(version.context(),
                stored.getBytes(StandardCharsets.UTF_8), "the-id", 3), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an element this version has never heard of survives the way out")
    void aLaterVersionsElementSurvives() {
        // instantiatesCanonical is not in R4's Patient. The model drops it; a
        // store must not, because what was stored is what was promised back.
        String served = read("""
                {"resourceType":"Patient","name":[{"family":"Aiakas"}],
                 "instantiatesCanonical":"http://acme.test/StructureDefinition/x"}""");

        assertTrue(served.contains("instantiatesCanonical"),
                "a stored element was served away: " + served);
        assertTrue(served.contains("\"id\":\"the-id\"") && served.contains("\"versionId\":\"3\""),
                served);
    }

    @Test
    @DisplayName("and so do an unknown extension, a primitive's extension and a narrative")
    void whatTheAuthorWroteSurvives() {
        String served = read("""
                {"resourceType":"Patient",
                 "extension":[{"url":"https://acme.test/ext/eye-colour","valueString":"green"}],
                 "birthDate":"1980-01-01",
                 "_birthDate":{"id":"bd1","extension":[
                     {"url":"https://acme.test/ext/precision","valueCode":"day"}]},
                 "text":{"status":"generated","div":"<div xmlns=\\"http://www.w3.org/1999/xhtml\\">Hi</div>"},
                 "name":[{"family":"Aiakas"}]}""");

        assertTrue(served.contains("eye-colour"), served);
        assertTrue(served.contains("\"_birthDate\"") && served.contains("bd1"), served);
        assertTrue(served.contains("xhtml"), served);
    }

    @Test
    @DisplayName("the ancestors are replaced where they already are, never added beside")
    void theAncestorsAreReplacedNotDuplicated() {
        String served = read("""
                {"resourceType":"Patient","id":"whatever-the-client-sent",
                 "meta":{"versionId":"99","profile":["http://acme.test/p"],
                         "tag":[{"system":"s","code":"c"}]},
                 "name":[{"family":"Aiakas"}]}""");

        assertEquals(1, served.split("\"id\":", -1).length - 1,
                "a document with two ids is not a document: " + served);
        assertTrue(served.contains("\"id\":\"the-id\"") && !served.contains("whatever-the-client"),
                served);
        assertTrue(served.contains("\"versionId\":\"3\"") && !served.contains("\"99\""), served);
        assertTrue(served.contains("acme.test/p") && served.contains("\"code\":\"c\""),
                "the rest of meta is the author's and stays: " + served);
    }

    @Test
    @DisplayName("field order is the author's, and a number keeps the precision it was written at")
    void orderAndPrecisionAreTheAuthors() {
        String served = read("""
                {"resourceType":"Observation","status":"final",
                 "valueQuantity":{"value":1.50,"unit":"g/dL"},
                 "code":{"coding":[{"system":"http://loinc.org","code":"718-7"}]}}""");

        assertTrue(served.indexOf("\"status\"") < served.indexOf("\"valueQuantity\""),
                "the order a document was written in is part of what was written: " + served);
        assertTrue(served.contains("1.50"),
                "a reported precision is a fact, not formatting: " + served);
    }
}
