package cloud.jengu.dbo.fhir.element;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the door's evaluability check actually reads.
 *
 * <p>A stored SearchParameter is refused where its author is standing if its
 * expression cannot be evaluated, and the check is handed the tenant's own
 * worker context — which is the object holding a version's whole definition
 * corpus. That makes it look like one of the reaches keeping the corpus
 * resident.
 *
 * <p>It is not, and this says so rather than leaving it to be assumed. The
 * check parses; parsing FHIRPath is a question about the text. An expression
 * over a resource type nothing has ever heard of parses, and an expression
 * that is not FHIRPath does not — so what the context contributes to this
 * answer is nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatParsingAnExpressionNeedsTest {

    private final ElementVersion version = ElementVersion.of("r5");

    @Test
    @DisplayName("an expression over a type the context has never heard of still parses, so "
            + "the check reads the text and not the definitions")
    void parsingDoesNotConsultTheCorpus() {
        assertEquals(java.util.Optional.empty(),
                version.whyNotEvaluable(version.context(), "Unicorn.horn.where(length > 3)"),
                "an expression naming a type no definition declares was reported "
                        + "unevaluable, which would mean this check reads the corpus");
    }

    @Test
    @DisplayName("and text that is not FHIRPath is still refused, so the check is not simply "
            + "answering yes")
    void whatIsNotAnExpressionIsStillRefused() {
        assertTrue(version.whyNotEvaluable(version.context(), "name.where(").isPresent(),
                "an unclosed call was accepted, so this check answers yes to everything "
                        + "and the test above proves nothing");
        assertTrue(version.whyNotEvaluable(version.context(), "").isPresent(),
                "an empty expression was accepted");
    }
}
