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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a step produced is in the Task a run is rendered as: the counts it kept,
 * each version it wrote, and the milestone it reached.
 *
 * <p>The application that asked for a run reads its result from that Task and
 * from nothing else, so a result the rendering left out is a result the asker
 * never learns.
 */
class ARunsResultIsItsTasksOutputTest {

    private final RecordProjection projection =
            ElementVersion.of("r4").face().require(RecordProjection.class);

    @Test
    @DisplayName("a closed run is completed, and its tally, the versions it produced and the "
            + "milestone it reached are outputs")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void aClosedRunsResultIsItsOutput() {
        Map<?, ?> task = rendered("""
                {"key":"hogwarts.admission.admit/one","process":"hogwarts.admission",
                 "step":"admit","kind":"pipeline","holder":"nobody",
                 "tally":{"admitted":1},
                 "milestone":{"name":"identified","position":1,"total":2},
                 "produced":{"counted":2,"versions":["Encounter/e1/1","Patient/p1/3"]},
                 "inputs":{"patient":"Patient/p1"},"requester":"the-asker"}""");

        assertEquals("completed", task.get("status"), "a run nobody holds is not completed");
        assertEquals(List.of(Map.of("system", "urn:dbo:run",
                        "value", "hogwarts.admission.admit/one")), task.get("identifier"),
                "the run's key is not its identifier");
        List<String> outputs = outputs(task);
        assertTrue(outputs.contains("urn:dbo:run:tally|admitted=1"),
                "the tally is not an output: " + outputs);
        assertTrue(outputs.contains("urn:dbo:run:output|produced=Encounter/e1/_history/1")
                        && outputs.contains("urn:dbo:run:output|produced=Patient/p1/_history/3"),
                "a version the run produced is not an output: " + outputs);
        assertTrue(outputs.contains("urn:dbo:run:output|milestone=identified"),
                "the milestone reached is not an output: " + outputs);
        assertTrue(outputs.stream().noneMatch(o -> o.contains("produced-count")),
                "a count was said beside a manifest that names everything: " + outputs);
    }

    @Test
    @DisplayName("a run past its manifest's cap says how many versions it produced, so the "
            + "named ones are not read as all of them")
    @Proving(DboPromises.PROC_A_RUN_ANSWERS_ITS_INITIATOR)
    void aCappedManifestSaysItsCount() {
        Map<?, ?> task = rendered("""
                {"key":"k/big","process":"m.p","step":"s","kind":"pipeline",
                 "holder":"automation",
                 "produced":{"counted":201,"versions":["Patient/p1/1"],
                             "watermark":{"Patient":201}}}""");

        assertEquals("in-progress", task.get("status"), "a run automation holds is under way");
        List<String> outputs = outputs(task);
        assertTrue(outputs.contains("urn:dbo:run:output|produced-count=201"),
                "a manifest that stopped naming versions did not say how many there were: "
                        + outputs);
    }

    @Test
    @DisplayName("a run whose result the tenant refused is failed, and the tenant's reason is "
            + "the outcome it points at")
    @Proving(DboPromises.PROC_A_REFUSED_RESULT_ENDS_THE_RUN)
    void aRefusedResultIsAFailedTask() {
        Map<?, ?> task = rendered("""
                {"key":"hogwarts.admission.register/one","process":"hogwarts.admission",
                 "step":"register","kind":"pipeline","holder":"nobody",
                 "tally":{"registered":1},
                 "refused":"hogwarts: the identifier urn:rl:nid|39001 is already held",
                 "requester":"the-asker"}""");

        assertEquals("failed", task.get("status"),
                "a run that ended refused reads as one that completed");
        assertTrue(outputs(task).contains("urn:dbo:run:output|outcome=#outcome"),
                "the refusal is not an output the asker can follow: " + outputs(task));
        Map<?, ?> outcome = (Map<?, ?>) ((List<?>) task.get("contained")).get(0);
        assertEquals("OperationOutcome", outcome.get("resourceType"));
        assertTrue(String.valueOf(outcome.get("issue")).contains("already held"),
                "the tenant's reason is not in the outcome: " + outcome);
    }

    private Map<?, ?> rendered(String run) {
        String document = projection.project(new RecordProjection.Record("Run", "run-1", 3,
                run.getBytes(StandardCharsets.UTF_8), List.of())).orElseThrow();
        Map<?, ?> bundle = (Map<?, ?>) RecordWire.read(document);
        return (Map<?, ?>) ((Map<?, ?>) ((List<?>) bundle.get("entry")).get(0)).get("resource");
    }

    /** Each output as {@code system|code=value}. */
    private static List<String> outputs(Map<?, ?> task) {
        return ((List<?>) task.get("output")).stream().map(o -> (Map<?, ?>) o).map(o -> {
            Map<?, ?> coding = (Map<?, ?>) ((List<?>) ((Map<?, ?>) o.get("type"))
                    .get("coding")).get(0);
            Object value = o.containsKey("valueReference")
                    ? ((Map<?, ?>) o.get("valueReference")).get("reference")
                    : o.containsKey("valueInteger") ? o.get("valueInteger") : o.get("valueString");
            return coding.get("system") + "|" + coding.get("code") + "=" + value;
        }).toList();
    }
}
