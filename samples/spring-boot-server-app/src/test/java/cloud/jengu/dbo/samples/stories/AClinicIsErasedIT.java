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
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-A-TENANT-IS-ERASED, walked in Rowling Land, the sample world.
 *
 * <p>A clinic closes, and the contract that ends with it asks that nothing be
 * kept. The operator retracts it, so it stops being served, and then erases
 * it at the deployment's erasure door — under the token the deployment gave
 * for erasure alone, stating why. What is checked afterwards is the database
 * server, because what is claimed is that the content is gone from where it
 * was, not that an interface stopped answering for it.
 *
 * <p><b>A clinic of its own.</b> Erasure is the one act here nobody takes
 * back, so the clinic erased is one this story declared, under its prefix, and
 * nothing any other story is walking. It holds a recording and a record: the
 * two kinds of thing a drop has to take.
 */
@AUserStory
class AClinicIsErasedIT {

    /** What the deployment gave whoever may erase. Not the operator's token. */
    private static final String ERASE = "stories-erase";

    /** What it gave whoever reads the node. */
    private static final String OPS = "stories-ops";

    private final StoryNames names = StoryNames.of(DboStories.A_TENANT_IS_ERASED);
    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    DboTestContext dbo;

    @Autowired
    Environment environment;

    private String clinic;
    private String recording;
    private boolean erased;

    @BeforeAll
    void aClinicThatIsClosing() {
        // Long and hyphenated, as a clinic's code is when somebody chose it:
        // the drop has to consider every name a declaration accepts.
        clinic = names.tenant("closing-clinic");
        dbo.declare(clinic, """
                {"code":"%s","face":"r4","audit":{"level":"none"},
                 "types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}"""
                .formatted(clinic));
        assertTrue(dbo.until(clinic, true, Duration.ofMinutes(10)),
                clinic + " never came up: " + dbo.serving());
    }

    @AfterAll
    void nothingIsLeftDeclared() {
        if (clinic != null && !erased) {
            dbo.retract(clinic);
        }
    }

    // ── what the clinic held, and where ──

    @Test
    @Order(1)
    @DisplayName("a recording the clinic holds is kept in the clinic's own database, beside its "
            + "records")
    @Proving(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA)
    void theRecordingIsInTheClinicsDatabase() throws Exception {
        byte[] audio = new byte[2048];
        new SecureRandom().nextBytes(audio);
        HttpResponse<String> kept = http.send(HttpRequest.newBuilder(
                        URI.create(dbo.at(clinic) + "/blob"))
                .header("Content-Type", "audio/ogg")
                .header("Authorization", "Bearer " + dbo.token(clinic))
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, kept.statusCode(), "the recording was not kept: " + kept.body());
        String location = kept.headers().firstValue("Location").orElseThrow(
                () -> new AssertionError("a kept recording said nowhere it can be read"));
        recording = location.substring(location.lastIndexOf('/') + 1);
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
                new WhatTheDatabaseHolds(environment, clinic).content(recording).isPresent(),
                "the recording is not in the clinic's own database, so dropping the clinic "
                        + "would leave it wherever it actually is");

        var patient = dbo.write(clinic, "Patient",
                "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Aiakas\"}]}");
        assertTrue(patient.accepted(), patient.body());
    }

    // ── who may erase, and when ──

    @Test
    @Order(2)
    @DisplayName("erasure is refused to the operator's own token, to an erasure that gives no "
            + "reason, and while the clinic is still declared")
    @Proving(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN)
    void erasureIsNotAskedLightly() throws Exception {
        HttpResponse<String> withOps = erase(clinic, OPS, "{\"reason\":\"the clinic closed\"}");
        Proves.that(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN, withOps.statusCode() == 401,
                "the token that reads the node was let near erasure: " + withOps.statusCode()
                        + " " + withOps.body());
        HttpResponse<String> unsaid = erase(clinic, ERASE, "{}");
        Proves.that(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN,
                unsaid.statusCode() == 400 && unsaid.body().contains("reason"),
                "an erasure that said nothing about why was not refused for it: "
                        + unsaid.statusCode() + " " + unsaid.body());
        HttpResponse<String> declared = erase(clinic, ERASE,
                "{\"reason\":\"the clinic closed\"}");
        Proves.that(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN,
                declared.statusCode() == 409 && declared.body().contains("still declared"),
                "a clinic still declared was erased, and the next pass would have brought it "
                        + "up again, empty: " + declared.statusCode() + " " + declared.body());
        assertTrue(dbo.serving().contains(clinic), "a refused erasure took the clinic down");
        assertEquals(1, databasesNamed(clinic), "a refused erasure dropped the database");
    }

    // ── retracted, then erased, and gone from the server ──

    @Test
    @Order(3)
    @DisplayName("retracted and then erased, the clinic's database is gone from the server, "
            + "and the recording and the records with it")
    @Proving({DboPromises.TEN_ERASURE_BY_DROP, DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA,
            DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN})
    void theClinicIsGone() throws Exception {
        assertTrue(recording != null, "the leg before kept no recording");
        assertTrue(dbo.retract(clinic), clinic + " was not declared by this story");
        assertTrue(dbo.until(clinic, false, Duration.ofMinutes(3)),
                clinic + " is still served after its retraction: " + dbo.serving());
        // Retracting is not erasing: the database is still there to come back to.
        Proves.that(DboPromises.TEN_ERASURE_BY_DROP, databasesNamed(clinic) == 1,
                "the retraction dropped the database, so a retraction could not be undone");

        HttpResponse<String> gone = erase(clinic, ERASE, "{\"reason\":\"the clinic closed and "
                + "its contract asks that nothing be kept\"}");
        erased = gone.statusCode() == 200;
        Proves.that(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN, erased,
                "the holder of the erasure token, stating why, did not erase a retracted "
                        + "clinic: " + gone.statusCode() + " " + gone.body());
        Proves.that(DboPromises.TEN_ERASURE_BY_DROP, databasesNamed(clinic) == 0,
                "the clinic's database survived its erasure, so nothing it held was erased");
        Proves.that(DboPromises.OPS_TENANT_BLOBS_ARE_TENANT_DATA, databasesNamed(clinic) == 0,
                "the recording's database survived, so the recording did too");

        HttpResponse<String> again = erase(clinic, ERASE, "{\"reason\":\"the clinic closed and "
                + "its contract asks that nothing be kept\"}");
        Proves.that(DboPromises.TEN_ERASURE_HAS_A_DOOR_OF_ITS_OWN,
                again.statusCode() == 200 && again.body().equals(gone.body()),
                "asking again for an erasure that happened answered differently: "
                        + gone.body() + " / " + again.statusCode() + " " + again.body());
    }

    @Test
    @Order(4)
    @DisplayName("a name no declaration would accept is refused by name, and nothing is dropped")
    @Proving(DboPromises.TEN_ERASURE_BY_DROP)
    void aNameThatIsNotACodeIsRefused() throws Exception {
        HttpResponse<String> notACode = erase("Not%20A%20Code", ERASE,
                "{\"reason\":\"a mistake\"}");
        Proves.that(DboPromises.TEN_ERASURE_BY_DROP,
                notACode.statusCode() == 400 && notACode.body().contains("Not A Code"),
                "a name no declaration accepts was not refused by name: "
                        + notACode.statusCode() + " " + notACode.body());
    }

    private HttpResponse<String> erase(String code, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(dbo.at(clinic))
                        .resolve("/runtime/erase/" + code))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private long databasesNamed(String code) {
        return WhatTheDatabaseHolds.theServer(environment).count(
                "SELECT count(*) FROM pg_database WHERE datname = ?",
                "tenant_" + code.replace('-', '_'));
    }
}
