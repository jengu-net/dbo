package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.sync.ConfigApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant that is missing, and a deployment that does not mention it.
 *
 * <p>A declaration whose spec will not parse never reaches bring-up, so none of
 * the reporting there fires. The refusal was recorded as a card — the right
 * home for it, since somebody has to change the file — and written nowhere an
 * operator was looking. Six tenants of seven read exactly like six, and the
 * reason, which named the field and said what to put in it, sat unread.
 *
 * <p>So it is said. Once, because the pass that produces it runs on every beat
 * of the scan: a refusal repeated every few seconds is the same mistake as the
 * failure that repeated silently, made from the other side.
 */
class ARefusedDeclarationIsSaidOnceTest {

    private static ConfigApplication.Card card(String declaration, String reason) {
        return new ConfigApplication.Card(declaration, reason);
    }

    @Test
    @Proving(DboPromises.TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE)
    @DisplayName("a refusal is said once, however many passes read the same broken file")
    void aLogThatRepeatsItselfStopsBeingRead() {
        Refusals refusals = new Refusals();
        List<ConfigApplication.Card> refused =
                List.of(card("hogwarts.json", "scim speaks a system Person is not identified by"));

        assertEquals(1, refusals.worthSaying(refused).size(), "the first refusal said nothing");
        assertEquals(List.of(), refusals.worthSaying(refused),
                "every pass says it again, and the pass runs on every beat of the scan");
        assertEquals(List.of(), refusals.worthSaying(refused));
    }

    @Test
    @Proving(DboPromises.TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE)
    @DisplayName("and said again when the reason changes, because that is a different problem")
    void somebodyFixingAFileWorksThroughItOneProblemAtATime() {
        Refusals refusals = new Refusals();

        assertEquals(1, refusals.worthSaying(
                List.of(card("hogwarts.json", "Person is not identified by that system")))
                .size());
        // The operator fixed that and hit the next one.
        List<ConfigApplication.Card> next =
                List.of(card("hogwarts.json", "Practitioner is not declared"));
        assertEquals(1, refusals.worthSaying(next).size(),
                "the second problem is news and was suppressed as though it were the first");
        assertEquals(List.of(), refusals.worthSaying(next));
    }

    @Test
    @Proving(DboPromises.TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE)
    @DisplayName("each declaration is its own, so one broken file does not silence another")
    void oneBrokenFileDoesNotSpeakForAnother() {
        Refusals refusals = new Refusals();
        List<ConfigApplication.Card> both = List.of(
                card("hogwarts.json", "a reason"),
                card("gringotts.json", "a reason"));

        List<ConfigApplication.Card> said = refusals.worthSaying(both);
        assertEquals(2, said.size(),
                "two files were refused for the same reason and only one was reported");
        assertTrue(said.stream().anyMatch(c -> c.declaration().equals("gringotts.json")));
        assertEquals(List.of(), refusals.worthSaying(both));
    }

    @Test
    @Proving(DboPromises.TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE)
    @DisplayName("a file that was fixed and breaks again is told about again")
    void beingFixedIsNotBeingForgivenForever() {
        Refusals refusals = new Refusals();
        List<ConfigApplication.Card> refused = List.of(card("hogwarts.json", "a reason"));

        assertEquals(1, refusals.worthSaying(refused).size());
        // It applied, so the deployment is serving it.
        refusals.applied("hogwarts.json");
        assertEquals(1, refusals.worthSaying(refused).size(),
                "it broke again after being fixed and nobody was told, because the first "
                        + "refusal was still being remembered");
    }
}
