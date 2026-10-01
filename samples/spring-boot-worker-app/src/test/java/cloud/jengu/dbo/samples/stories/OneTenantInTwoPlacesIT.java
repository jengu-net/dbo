package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-TWO-PLACES, walked in Rowling Land, the sample world.
 *
 * <p>The clinic is one place today and two by the end of this story. It takes
 * its canonical content from Rowling Land, the jurisdiction it sits in, and it
 * puts an appliance in the building so that a lost connection is an
 * inconvenience rather than a closed practice.
 *
 * <p>Both halves are the same idea: content that belongs somewhere else,
 * arriving because somebody declared that it should, and staying legible about
 * where it came from. Canonical content travels <b>by type</b>, because none
 * of it is about anybody. Patient data travels <b>by work</b>, arriving with a
 * task and leaving when no open run still names it.
 *
 * <p><b>The zone half</b> runs on Rowling Land's own tenant, with a clinic this story
 * declares after the zone already holds content, which is the ordinary order.
 * <b>The appliance half</b> is one tenant in two places, and the world is one
 * place. It is proven where it needs no runtime at all, as two databases and a
 * lane between them ({@code AnApplianceCarriesPatientDataByWorkIT} in the
 * harness), and this story leans on it.
 */
@AUserStory
class OneTenantInTwoPlacesIT {

    private static final String ZONE = "rl";

    private final StoryNames names = StoryNames.of(DboStories.TWO_PLACES);

    @Autowired
    DboTestContext dbo;

    // ── the zone half ──
    private String clinic;
    private String severity;

    @BeforeAll
    void theClinicsNames() {
        clinic = names.tenant("clinic");
        severity = names.canonical("severity");
    }

    @AfterAll
    void theClinicIsWithdrawn() {
        if (clinic != null) {
            dbo.retract(clinic);
        }
    }

    // ── canonical content arrives because somebody declared it should ──

    @Test
    @Order(1)
    @DisplayName("the clinic declares what it takes from the zone, and catches up on what the "
            + "zone held before the clinic existed")
    @Proving({DboPromises.SYNC_SPEC_DECLARED, DboPromises.SYNC_DECLARED_ONLY,
            DboPromises.ZONE_DECLARATIONS_AS_RECORDS})
    void theClinicDeclaresWhatItTakes() throws InterruptedException {
        // The zone publishes before the clinic exists, which is the ordinary
        // case: canonical content is older than the practices that use it.
        publish("CodeSystem", codeSystem(severity, "mild", "Mild"));

        // Declared now, after the zone already holds content: the clinic is
        // opened by saying it exists and what it takes.
        dbo.declare(clinic, """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "dependencies":[{"name":"%s","types":["CodeSystem"]}],
                 "types":[
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"}]}"""
                .formatted(clinic, ZONE));
        assertTrue(dbo.until(clinic, true, Duration.ofMinutes(10)),
                "the clinic never came up: " + dbo.serving());

        Proves.that(DboPromises.SYNC_SPEC_DECLARED, untilTheClinicHolds(severity),
                "content the zone held before the clinic was declared never arrived");
    }

    @Test
    @Order(2)
    @DisplayName("a type the clinic did not declare a dependency for brings nothing, however "
            + "much of it the zone holds")
    @Proving({DboPromises.SYNC_DECLARED_ONLY, DboPromises.SYNC_DIRECT_UPSTREAM_ONLY})
    void nothingUndeclaredArrives() throws InterruptedException {
        String valueSet = names.canonical("severity-values");
        publish("ValueSet", """
                {"resourceType":"ValueSet","url":"%s","status":"active"}""".formatted(valueSet));
        // Published after the value set, and of a type the clinic does take:
        // once it arrives, the stream has carried everything up to it.
        String later = names.canonical("severity-later");
        publish("CodeSystem", codeSystem(later, "severe", "Severe"));
        assertTrue(untilTheClinicHolds(later),
                "the code system published after the value set never arrived, so the absence "
                        + "below would prove nothing");

        HttpResponse<String> held = new ATenantsDoor(dbo, clinic)
                .get("/ValueSet?url=" + encoded(valueSet));
        assertEquals(200, held.statusCode(), held.body());
        Proves.that(DboPromises.SYNC_DECLARED_ONLY,
                dbo.says(held).at("entry.resource.id").isEmpty(),
                "an undeclared type arrived, so a dependency is a hint rather than a bound and "
                        + "a clinic ends up holding whatever its upstream happens to have: "
                        + held.body());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void publish(String type, String document) {
        HttpResponse<String> published = new ATenantsDoor(dbo, ZONE).post("/" + type, document);
        assertTrue(published.statusCode() == 200 || published.statusCode() == 201,
                "the zone did not take the " + type + ": " + published.body());
    }

    /** Waits for the clinic to answer for a code system, not merely to store it. */
    private boolean untilTheClinicHolds(String system) throws InterruptedException {
        ATenantsDoor door = new ATenantsDoor(dbo, clinic);
        long giveUp = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < giveUp) {
            HttpResponse<String> held = door.get("/CodeSystem?url=" + encoded(system));
            if (held.statusCode() == 200
                    && !dbo.says(held).at("entry.resource.id").isEmpty()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private static String codeSystem(String url, String code, String display) {
        return """
                {"resourceType":"CodeSystem","url":"%s","status":"active",
                 "content":"complete","version":"1.0",
                 "concept":[{"code":"%s","display":"%s"}]}""".formatted(url, code, display);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
