package cloud.jengu.dbo.samples.stories;

import cloud.jengu.dbo.promise.proving.Proves;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.DboStories;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.test.DboTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-CLINICAL-RECORD, walked on the sample world.
 *
 * <p>Maarja works at St Jerome, the clinic of the sample world. This is the
 * store doing what it exists for: a patient written is a patient read back,
 * found again by what the clinic knows her by, and accounted for afterwards.
 *
 * <p><b>The clinic is shared with every other story running.</b> So Liis is
 * keyed by this story's own identifier system, with a value carrying this run's
 * mark, and every question asked about her is asked by that identifier. What
 * the clinic holds besides her is somebody else's.
 */
@AUserStory
class TheClinicRecordsCareAndAccountsForItIT {

    private static final String CLINIC = "st-jerome";

    private final StoryNames names = StoryNames.of(DboStories.CLINICAL_RECORD);

    @Autowired
    DboTestContext dbo;

    private String liis;

    @Test
    @Order(1)
    @DisplayName("a patient is written and reads back as what was written, an element nothing "
            + "indexes included")
    @Proving({DboPromises.CORE_PAYLOAD_IS_TRUTH, DboPromises.CORE_READ_YOUR_WRITES})
    void whatWasWrittenIsWhatIsRead() {
        var written = dbo.write(CLINIC, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(names.system(), names.value("liis")));
        assertTrue(written.accepted(), "Liis was not accepted: " + written.body());
        liis = written.idOrFail();

        HttpResponse<String> read = dbo.read(CLINIC, "Patient", liis);
        assertEquals(200, read.statusCode(), read.body());
        var record = dbo.says(read);
        Proves.that(DboPromises.CORE_READ_YOUR_WRITES,
                record.at("name.family").contains("Tamm"),
                "the write had returned and the read straight after it did not see it: "
                        + read.body());
        Proves.that(DboPromises.CORE_PAYLOAD_IS_TRUTH,
                record.one("birthDate").equals(Optional.of("1990-01-01")),
                "an element nothing indexes did not come back as written, so the record was "
                        + "rebuilt from something other than its payload: " + read.body());
    }

    @Test
    @Order(2)
    @DisplayName("Liis is found again by the identifier she was written under, and that "
            + "identifier finds nobody else")
    @Proving(DboPromises.SRCH_TIER1_PARITY)
    void sheIsFoundByWhatTheClinicKnowsHerBy() {
        HttpResponse<String> found = dbo.search(CLINIC, "Patient",
                "identifier=" + names.system() + "|" + names.value("liis"));
        assertEquals(200, found.statusCode(), found.body());
        List<String> ids = dbo.says(found).at("entry.resource.id");
        Proves.that(DboPromises.SRCH_TIER1_PARITY, ids.equals(List.of(liis)),
                "her identifier should find her and only her, and found " + ids
                        + " where she is " + liis + ": " + found.body());
    }
}
