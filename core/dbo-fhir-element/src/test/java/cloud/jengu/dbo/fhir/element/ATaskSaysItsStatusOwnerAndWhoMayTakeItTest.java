package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run rendered as a Task says where it stands, who holds it and who may take
 * it, each in the element FHIR already has for it — in R4's spelling and in
 * R5's.
 */
class ATaskSaysItsStatusOwnerAndWhoMayTakeItTest {

    private static Map<?, ?> rendered(String code, String run) {
        String document = ElementVersion.of(code).face().require(RecordProjection.class)
                .project(new RecordProjection.Record("Run", "run-1", 3,
                        run.getBytes(StandardCharsets.UTF_8), List.of())).orElseThrow();
        Map<?, ?> bundle = (Map<?, ?>) RecordWire.read(document);
        return (Map<?, ?>) ((Map<?, ?>) ((List<?>) bundle.get("entry")).get(0)).get("resource");
    }

    @Test
    @DisplayName("a released run reads ready rather than in-progress, open to people alone, "
            + "with why")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void aReleasedRunIsReady() {
        String run = """
                {"key":"lab.result.verify/one","process":"lab.result","step":"verify",
                 "kind":"pipeline","status":"ready",
                 "performerType":["person"],"statusReason":"the worker answered nonsense",
                 "note":"the work failed"}""";
        Map<?, ?> r4 = rendered("r4", run);
        Map<?, ?> r5 = rendered("r5", run);

        assertEquals("ready", r4.get("status"));
        assertFalse(r4.containsKey("owner"), "nobody holds a released run: " + r4.get("owner"));
        assertEquals(Map.of("text", "the worker answered nonsense"), r4.get("statusReason"));
        assertEquals(List.of(Map.of("coding", List.of(Map.of("system", "urn:dbo:run:performer",
                "code", "person")))), r4.get("performerType"));
        assertEquals(List.of(Map.of("concept", Map.of("coding", List.of(Map.of(
                        "system", "urn:dbo:run:performer", "code", "person"))))),
                r5.get("requestedPerformer"));
        assertEquals(Map.of("concept", Map.of("text", "the worker answered nonsense")),
                r5.get("statusReason"));
    }

    @Test
    @DisplayName("a run for one participant is owned by it until it is taken, and then by "
            + "the executor that took it")
    @Proving(DboPromises.PROC_A_RUN_NAMES_WHO_MAY_TAKE_IT)
    void aRunForOneParticipantIsOwnedByIt() {
        String waiting = """
                {"key":"fleet.appliance.report/one","process":"fleet.appliance","step":"report",
                 "kind":"pipeline","status":"ready","performerType":["automation","person"],
                 "for":{"client":"ward-1","executor":"ward-1"}}""";
        for (String code : List.of("r4", "r5")) {
            assertEquals(Map.of("type", "Device",
                            "identifier", Map.of("system", "urn:dbo:auth:client-id",
                                    "value", "ward-1"),
                            "display", "ward-1 as ward-1"),
                    rendered(code, waiting).get("owner"), code);
        }

        String taken = waiting.replace("\"status\":\"ready\"", "\"status\":\"in-progress\","
                + "\"executor\":{\"name\":\"ward-1\",\"version\":\"7\",\"provider\":\"hogwarts\"}");
        Map<?, ?> owner = (Map<?, ?>) rendered("r5", taken).get("owner");
        assertEquals(Map.of("system", "urn:dbo:executor", "value", "ward-1"),
                owner.get("identifier"), "a taken run is not owned by the executor that took it");
    }

    @Test
    @DisplayName("a run a person holds is in progress and owned by the role they took it as")
    @Proving(DboPromises.PROC_A_PERSON_CLAIMS_AS_A_PRACTITIONER_ROLE)
    void aPersonsRunIsOwnedByTheirRole() {
        Map<?, ?> task = rendered("r5", """
                {"key":"lab.result.verify/two","process":"lab.result","step":"verify",
                 "kind":"pipeline","status":"in-progress",
                 "performerType":["person"],"role":"PractitionerRole/nurse-1",
                 "claimant":"person-1","until":"2026-10-03T12:00:00Z"}""");

        assertEquals("in-progress", task.get("status"));
        assertEquals(Map.of("reference", "PractitionerRole/nurse-1"), task.get("owner"));
    }

    @Test
    @DisplayName("a run held back after a failure that may pass is on hold, open to automation "
            + "and people, not before its time")
    @Proving(DboPromises.PROC_ESCALATION_BY_FAILURE_CLASS)
    void aHeldBackRunSaysWhenItMayBeTaken() {
        String run = """
                {"key":"lab.result.verify/three","process":"lab.result","step":"verify",
                 "kind":"pipeline","status":"on-hold",
                 "performerType":["automation","person"],"notBefore":"2026-10-03T12:01:00Z",
                 "attempts":1}""";
        Map<?, ?> r4 = rendered("r4", run);
        Map<?, ?> r5 = rendered("r5", run);

        assertEquals("on-hold", r4.get("status"));
        assertEquals(Map.of("period", Map.of("start", "2026-10-03T12:01:00Z")),
                r4.get("restriction"));
        assertEquals(Map.of("start", "2026-10-03T12:01:00Z"), r5.get("requestedPeriod"));
        assertEquals(2, ((List<?>) r4.get("performerType")).size());
        assertTrue(r5.containsKey("requestedPerformer"));
    }
}
