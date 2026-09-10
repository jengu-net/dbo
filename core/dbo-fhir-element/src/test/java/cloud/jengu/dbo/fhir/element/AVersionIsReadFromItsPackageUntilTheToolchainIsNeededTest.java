package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.model.SearchParameter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A version is looked up for its coordinate, a type's search parameters and
 * a definition's envelope long before anything validates — at every
 * bring-up, for every tenant. None of those needs the toolchain's context,
 * and the context is the whole of a version's memory, so none of them may
 * build it. What they read from the package instead has to be what the
 * context would have said.
 */
class AVersionIsReadFromItsPackageUntilTheToolchainIsNeededTest {

    @Test
    @DisplayName("the coordinate, the parameters and a definition's envelope cost no context")
    void whatABringUpAsksForCostsNoContext() {
        ElementVersion version = new ElementVersion("r4");
        assertEquals("4.0.1", version.payloadVersion());
        assertFalse(version.parametersFor("Patient").isEmpty());
        assertFalse(version.parametersFor("StructureDefinition").isEmpty());
        assertFalse(ElementVersion.indexesFor(version.parametersFor("Observation")).isEmpty());
        FaceRootPackages.Definition patient = FaceRootPackages.definitionsFor("r4",
                        Set.of("StructureDefinition")).stream()
                .filter(d -> d.url().endsWith("/StructureDefinition/Patient")).findFirst().orElseThrow();
        assertFalse(version.extractor("StructureDefinition", true)
                .extract("StructureDefinition", patient.document()).paths().isEmpty());
        assertFalse(version.contextBuilt(), "one of those built the toolchain's context");
    }

    @Test
    @DisplayName("what the package says a type is searched by is what the loaded context says")
    void theParametersReadFromThePackageAreTheContexts() {
        for (String face : CarriedDefinitions.versions()) {
            ElementVersion version = new ElementVersion(face);
            var context = version.context();
            assertEquals(context.getVersion(), version.payloadVersion(), face);
            int types = 0;
            for (String type : context.getResourceNames()) {
                List<SearchParameter> fromContext = fromContext(context, type);
                List<SearchParameter> fromPackage = version.parametersFor(type);
                // Compared by code: the package path orders by code, the
                // context by the archive's file order — and first-wins can
                // only differ where a code is defined twice, which is
                // refused below.
                assertEquals(describe(fromContext).stream().sorted().toList(),
                        describe(fromPackage).stream().sorted().toList(),
                        face + " " + type + ": the package and the context disagree about the parameters");
                // The specification's example parameters are not the type's.
                assertTrue(fromPackage.stream().noneMatch(p -> p.getUrl().contains("/SearchParameter/example")),
                        face + " " + type + " lists a specification example as a parameter");
                types++;
            }
            assertTrue(types > 100, face + " has " + types + " resource types");
        }
    }

    /** The rule the context path applied before parameters were read from the package. */
    private static List<SearchParameter> fromContext(org.hl7.fhir.r5.context.SimpleWorkerContext context,
            String type) {
        java.util.Map<String, SearchParameter> byCode = new java.util.LinkedHashMap<>();
        for (SearchParameter parameter : context.fetchResourcesByType(SearchParameter.class)) {
            boolean applies = parameter.getBase().stream().anyMatch(base -> type.equals(base.getCode()));
            if (!applies || parameter.getCode().startsWith("_")
                    || parameter.getUrl().contains("/SearchParameter/example")
                    || parameter.getExpression() == null || parameter.getExpression().isBlank()) {
                continue;
            }
            byCode.putIfAbsent(parameter.getCode(), parameter);
        }
        return List.copyOf(byCode.values());
    }

    private static List<String> describe(List<SearchParameter> parameters) {
        return parameters.stream().map(p -> p.getCode() + " " + p.getType() + " " + p.getExpression()
                + " " + p.getTarget().stream().map(t -> t.getCode()).sorted().toList()).toList();
    }
}
