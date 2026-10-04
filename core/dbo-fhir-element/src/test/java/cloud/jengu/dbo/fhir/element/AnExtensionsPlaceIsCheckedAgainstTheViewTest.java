package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extension definition says which elements it may sit on, and a write of
 * one is checked against the version it names — answered by the view the
 * tenant validates against, never by a second core context loaded from the
 * machine's package cache.
 *
 * <p>The library asks for the version by its short name and could not match
 * it to the view's own, so every validator built one: about two seconds of a
 * busy core per extension definition, at every tenant's bring-up, since the
 * view is rebuilt after each definition the face publishes.
 */
class AnExtensionsPlaceIsCheckedAgainstTheViewTest {

    private static List<String> issues(String context) {
        ElementPayloads payloads = ElementVersion.of("r4").payloadsFor(Terms.NONE);
        String json = """
                {"resourceType":"StructureDefinition",
                 "url":"https://test.dbo.dev/StructureDefinition/placed","name":"Placed",
                 "status":"active","kind":"complex-type","abstract":false,"type":"Extension",
                 "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Extension",
                 "derivation":"constraint",
                 "context":[{"type":"element","expression":"%s"}],
                 "differential":{"element":[
                   {"id":"Extension","path":"Extension","max":"1"},
                   {"id":"Extension.url","path":"Extension.url",
                    "fixedUri":"https://test.dbo.dev/StructureDefinition/placed"},
                   {"id":"Extension.value[x]","path":"Extension.value[x]","min":1,
                    "type":[{"code":"instant"}]}]}}""".formatted(context);
        org.hl7.fhir.r5.elementmodel.Element document =
                payloads.read(null, json.getBytes(StandardCharsets.UTF_8));
        return payloads.validate(payloads.typeOf(document), document);
    }

    @Test
    void anExtensionPlacedOnAnElementTheVersionHasIsAccepted() {
        List<String> issues = issues("Task");
        assertFalse(issues.stream().anyMatch(i -> i.contains("Task")),
                "an extension on Task, which r4 has, was refused its place: " + issues);
    }

    @Test
    void anExtensionPlacedOnAnElementTheVersionLacksIsStillRefused() {
        List<String> issues = issues("Task.noSuchElement");
        assertTrue(issues.stream().anyMatch(i -> i.contains("Task.noSuchElement")),
                "the place an extension names went unchecked: " + issues);
    }

    /**
     * The library's own answer is to read the core package out of the
     * machine's package cache, and to fetch it into that cache when it is not
     * there — at bring-up, for the face's own extension definitions.
     */
    @Test
    @Proving(DboPromises.VER_DEFINITIONS_TRAVEL_WITH_THE_FACE)
    void theVersionTheCheckAsksForIsTheViewAndNotThePackageCache() {
        SimpleWorkerContext view = ElementVersion.of("r4").payloadsFor(Terms.NONE).context();
        assertSame(view, ElementPayloads.sessionOver(view).getOtherVersions().get("4.0"),
                "a validator's session does not hold the view as r4, so checking where an "
                        + "extension may sit loads a core context of its own");
    }
}
