package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.SearchParameter;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A face brings the definitions it validates and extracts against, and a
 * version it does not carry is refused rather than fetched
 * (REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE).
 *
 * <p>The failure this rules out is quiet: loaded the obvious way, a face names
 * a package and the toolchain downloads it into a cache under the home
 * directory. It works on a developer's machine, works in CI the second time,
 * and fails on the box with no route to a package registry — at bring-up, as a
 * tenant that will not come up.
 */
class DefinitionsTravelWithTheFaceTest {

    @Test
    @DisplayName("the packages are in the bundle, pinned by the build")
    void thePackagesAreCarried() {
        List<CarriedDefinitions.Carried> carried = CarriedDefinitions.carried();

        assertTrue(carried.stream().anyMatch(c -> c.id().equals("hl7.fhir.r6.core#6.0.0-ballot5")),
                "the version's own definitions must travel with it: " + carried);
        assertEquals("hl7.fhir.r6.core", carried.get(0).name(),
                "a version's core package is loaded first — the rest resolve against it");
    }

    @Test
    @DisplayName("and a context is built from those bytes, reporting the version they declare")
    void aContextIsBuiltOffline() {
        SimpleWorkerContext context = CarriedDefinitions.contextFor("r6");

        assertEquals("6.0.0-ballot5", context.getVersion(),
                "a ballot is served under its own version code, never the release it anticipates");
        assertTrue(context.fetchResourcesByType(StructureDefinition.class).size() > 200,
                "the shapes a face validates against are missing");
        assertTrue(context.fetchResourcesByType(SearchParameter.class).size() > 1000,
                "the search parameters an envelope is extracted from are missing");
        assertTrue(context.getResourceNames().size() > 100,
                "a version that names no resources cannot serve one");
    }

    @Test
    @DisplayName("a version this face does not carry is refused, not downloaded")
    void anUncarriedVersionIsRefused() {
        CarriedDefinitions.NotCarried refusal = assertThrows(CarriedDefinitions.NotCarried.class,
                () -> CarriedDefinitions.contextFor("r4"));

        assertTrue(refusal.getMessage().contains("r6"),
                "a refusal that does not say what is carried cannot be acted on: "
                        + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("never fetched"),
                "the refusal has to say that waiting will not help: " + refusal.getMessage());
    }
}
