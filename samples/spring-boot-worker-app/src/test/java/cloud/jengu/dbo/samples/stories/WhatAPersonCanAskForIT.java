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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-DBO-PERSON-RIGHTS, walked on the sample world.
 *
 * <p>Liis is a patient at Hogwarts, which holds its people behind the
 * membrane: what identifies her is sealed in the tenant's vault, and the
 * record the store keeps is pseudonymous. This is what she can ask for, and
 * what the store asks of anybody looking for her.
 *
 * <p><b>The hospital keys patients by the national number</b>, a system the
 * world fixes, so this story cannot key Liis by a system of its own. Her
 * number carries the story's prefix and this run's mark instead, which is what
 * keeps her apart from every other patient the stories running beside this one
 * write there.
 */
@AUserStory
class WhatAPersonCanAskForIT {

    private static final String HOSPITAL = "hogwarts";

    /** The system Hogwarts keys its patients by, in the sample world's spec. */
    private static final String NATIONAL_NUMBER = "urn:rl:nid";

    private final StoryNames names = StoryNames.of(DboStories.PERSON_RIGHTS);

    @Autowired
    DboTestContext dbo;

    private String liis;

    @Test
    @Order(1)
    @DisplayName("a credential that may write every type still reads Liis back without her "
            + "name, because what identifies her lives in the vault")
    @Proving(DboPromises.PDI_STRUCTURAL_VAULT)
    void readingHerIsNotTheSameAsWritingHer() {
        var written = dbo.write(HOSPITAL, "Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"Tamm","given":["Liis"]}],
                 "birthDate":"1990-01-01"}""".formatted(NATIONAL_NUMBER, names.value("liis")));
        assertTrue(written.accepted(), "Liis was not accepted: " + written.body());
        liis = written.idOrFail();

        HttpResponse<String> read = dbo.read(HOSPITAL, "Patient", liis);
        assertEquals(200, read.statusCode(), read.body());
        var record = dbo.says(read);
        Proves.that(DboPromises.PDI_STRUCTURAL_VAULT, !record.has("name"),
                "her name came back to a credential nothing declared may identify her: "
                        + read.body());
    }

    @Test
    @Order(2)
    @DisplayName("looking Liis up by her national number is refused until the caller says "
            + "what it is for, and then finds her and nobody else")
    @Proving({DboPromises.PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL, DboPromises.PDI_EXACT_RESOLUTION})
    void lookingSomebodyUpIsAnActWithAReason() {
        String byHerNumber = "identifier=" + NATIONAL_NUMBER + "|" + names.value("liis");

        HttpResponse<String> unstated = dbo.search(HOSPITAL, "Patient", byHerNumber);
        Proves.that(DboPromises.PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL,
                unstated.statusCode() == 403,
                "an identifying search with no purpose answered " + unstated.statusCode()
                        + " rather than a refusal the caller can act on: " + unstated.body());

        HttpResponse<String> stated = dbo.search(HOSPITAL, "Patient", byHerNumber, "TREAT");
        assertEquals(200, stated.statusCode(), stated.body());
        List<String> ids = dbo.says(stated).at("entry.resource.id");
        Proves.that(DboPromises.PDI_EXACT_RESOLUTION, ids.equals(List.of(liis)),
                "her number, with a purpose stated, should resolve to her and only her, and "
                        + "resolved to " + ids + " where she is " + liis + ": " + stated.body());
    }
}
