package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a step's asker may collect is declared on the step, and a declaration
 * that disagrees with itself is refused where it was written.
 *
 * <p>Each refusal here stands for a tenant that would otherwise come up and
 * answer its askers wrongly — silence for an audience nobody declared, a
 * person disclosed for no stated reason — at the first collection rather than
 * at the file that can be fixed.
 */
class AStepDeclaresWhatItsAskerCollectsTest {

    private static final String SPEC = """
            {"code":"murre","face":"r5","pdi":true,
             "types":[{"name":"Patient","identity":"identifier","systems":["urn:murre:mrn"],
               "handling":"operational"}],
             "disclosure":{"perAudience":{
               "desk":{"types":["Patient"],"reveals":"omit"},
               "ward":{"types":["Patient"],"reveals":"include"}}},
             "steps":[{"code":"murre.records.register","slots":{"patient":"Patient"},
                       "writes":["Patient"]%s}]}""";

    private static TenantSpec.Step step(String answer) {
        return TenantSpec.parse(SPEC.formatted(answer)).steps().get(0);
    }

    @Test
    @Proving(DboPromises.IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE)
    @DisplayName("a step answering a declared audience carries that audience's types and mode, "
            + "and its window")
    void theAudienceIsTheTenants() {
        TenantSpec.Answer answer = step(",\"answers\":\"desk\",\"collect\":\"PT15M\"").answer();

        assertEquals("desk", answer.audience());
        assertEquals(Set.of("Patient"), answer.types());
        assertEquals(Disclosure.Mode.OMIT, answer.reveals());
        assertEquals(Duration.ofMinutes(15), answer.collect());
        assertEquals(Disclosure.Mode.OMIT, answer.revealing("TREAT"),
                "a purpose stated by the request raised what an omit audience reveals");
    }

    @Test
    @Proving(DboPromises.IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE)
    @DisplayName("a step that declares no answer gives its asker nothing to collect")
    void noAnswerIsReferencesOnly() {
        assertNull(step("").answer(), "a step that said nothing about its asker collects");
    }

    @Test
    @Proving(DboPromises.IDN_THE_ASKER_IS_A_DECLARED_AUDIENCE)
    @DisplayName("an audience the tenant never declared is refused when the step is read")
    void anUndeclaredAudienceIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"partner\",\"collect\":\"PT15M\""));
        assertTrue(refused.getMessage().contains("partner"), refused.getMessage());
    }

    @Test
    @DisplayName("an audience without a window, a window without an audience, and a window "
            + "that is not a duration are refused by name")
    void aWindowAndAnAudienceComeTogether() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"desk\"")).getMessage().contains("collect"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> step(",\"collect\":\"PT15M\"")).getMessage().contains("answers"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"desk\",\"collect\":\"fifteen minutes\""))
                .getMessage().contains("PT15M"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"desk\",\"collect\":\"PT0S\""))
                .getMessage().contains("PT0S"));
    }

    @Test
    @Proving(DboPromises.IDN_A_STEP_STATES_ITS_PURPOSE)
    @DisplayName("a step whose audience reveals a person whole and that states no purpose is "
            + "refused when it is declared")
    void includeNeedsThePurposeOfTheStep() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"ward\",\"collect\":\"PT15M\""));
        assertTrue(refused.getMessage().contains("purpose"), refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> step(",\"answers\":\"ward\",\"collect\":\"PT15M\","
                        + "\"purpose\":\"TREAT\\\"}\""));
    }

    @Test
    @Proving(DboPromises.IDN_A_STEP_STATES_ITS_PURPOSE)
    @DisplayName("only the step's purpose stated again by the request reveals her whole; "
            + "another code or none is the strict mode")
    void twoKeys() {
        TenantSpec.Answer answer = step(",\"answers\":\"ward\",\"collect\":\"PT15M\","
                + "\"purpose\":\"TREAT\"").answer();

        assertEquals(Disclosure.Mode.INCLUDE, answer.revealing("TREAT"));
        assertEquals(Disclosure.Mode.OMIT, answer.revealing(null),
                "the step's purpose alone revealed her");
        assertEquals(Disclosure.Mode.OMIT, answer.revealing("HRESCH"),
                "a request stating a purpose the step never declared revealed her");
    }
}
