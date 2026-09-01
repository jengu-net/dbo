package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declaring a face is domain-neutral; serving one is not, and that was decided
 * rather than overlooked.
 *
 * <p>A face for a domain that is not healthcare costs three capabilities and
 * eight methods, none of which names a standard — the inward contract is
 * neutral and {@link ADomainThatIsNotHealthcareHasAFaceIT} drives it. The
 * outward seam is a different price: a face a tenant can name in its spec and
 * be <b>served</b> through produces a {@link FhirStoreFacade}, and five of its
 * methods carry FHIR in their names.
 *
 * <p>The decision is to accept those five rather than neutralise them, because
 * what they do is not FHIR — every served surface must answer
 * write-if-nothing-matches, render one object's versions, say what it serves,
 * give a verdict, and render a refusal in its own words — and because the HTTP
 * surface really is FHIR REST, so a neutral name over it would claim a
 * neutrality it does not have.
 *
 * <p>What this holds is the <i>size</i> of that concession. Five was measured
 * and argued; a sixth would be somebody adding a FHIR-shaped obligation to a
 * seam every face has to implement, which is exactly the drift the decision
 * was taken to bound. Failing here does not mean the addition is wrong — it
 * means the paragraph in
 * {@code docs/arc42-008-crosscutting/engine-and-faces.md} is now describing a
 * different bargain and has to be rewritten or the method renamed.
 */
class ServingAFaceCostsAFhirShapedSeamTest {

    /**
     * The words that make a method name domain-specific. {@code resource} is
     * deliberately absent: it is an ordinary English noun the engine uses
     * freely, and matching it would flag neutral methods and teach everyone to
     * ignore this test.
     */
    private static final List<String> FHIR_WORDS =
            List.of("fhir", "bundle", "capabilitystatement", "operationoutcome",
                    "validationoutcome", "conditional");

    /**
     * The FHIR-named obligations a served face <b>must</b> implement. Measured,
     * argued and written down — not a list somebody may extend quietly.
     */
    private static final Set<String> MUST_IMPLEMENT = new TreeSet<>(Set.of(
            "conditionalCreate", "historyBundle", "capabilityStatement",
            "validationOutcome", "operationOutcome"));

    /**
     * FHIR-named, and optional: each carries a default that refuses by name, so
     * a face that does not offer it implements nothing and answers "not offered
     * here" rather than a 500.
     *
     * <p>Kept separate because the two cost a face different things, and the
     * decision is about what a face is <i>obliged</i> to produce. An optional
     * one is a surface this store happens to offer; a mandatory one is a
     * sentence in every face's contract.
     */
    private static final Set<String> MAY_OFFER =
            new TreeSet<>(Set.of("conditionalUpdate", "bundle"));

    @Test
    @DisplayName("the served facade names FHIR exactly where the decision says it may, "
            + "and nowhere else")
    @Proving(DboPromises.VER_VERSION_AGNOSTIC_CORE)
    void theFhirNamedSurfaceIsTheOneThatWasArgued() {
        Set<String> mandatory = new TreeSet<>();
        Set<String> optional = new TreeSet<>();
        for (Method m : FhirStoreFacade.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue;
            }
            String lower = m.getName().toLowerCase(Locale.ROOT);
            if (FHIR_WORDS.stream().noneMatch(lower::contains)) {
                continue;
            }
            // By NAME, not by signature: an overload with a default body does
            // not make the obligation optional when another overload of the
            // same name is abstract.
            boolean anyAbstract = List.of(FhirStoreFacade.class.getDeclaredMethods()).stream()
                    .filter(other -> other.getName().equals(m.getName()))
                    .anyMatch(other -> !other.isDefault());
            (anyAbstract ? mandatory : optional).add(m.getName());
        }

        assertEquals(MUST_IMPLEMENT, mandatory,
                "the set of FHIR-named obligations every served face MUST implement has "
                        + "changed. That may well be right, but it is a decision: the "
                        + "recorded bargain is these five, argued on the grounds that each "
                        + "names an obligation any served surface has. Rename it, give it a "
                        + "default that refuses by name, or rewrite the paragraph.");
        assertEquals(MAY_OFFER, optional,
                "the FHIR-named surface a face may decline has changed. Cheaper than the "
                        + "mandatory kind and still not free — every one of these is a "
                        + "method whose name a non-FHIR face has to read and ignore.");
    }

    @Test
    @DisplayName("and what a face must implement is still mostly neutral, which is what "
            + "made accepting the FHIR-named ones reasonable")
    void mostOfWhatAFaceOwesNamesNothing() {
        long total = List.of(FhirStoreFacade.class.getDeclaredMethods()).stream()
                .filter(m -> !m.isSynthetic())
                .filter(m -> !m.isDefault())
                .count();

        assertTrue(total > 2L * MUST_IMPLEMENT.size(),
                "the abstract seam is " + total + " methods of which " + MUST_IMPLEMENT.size()
                        + " name FHIR, so what a face must implement is no longer mostly "
                        + "neutral — and that ratio is the whole argument for accepting the "
                        + "names rather than renaming them");
    }
}
