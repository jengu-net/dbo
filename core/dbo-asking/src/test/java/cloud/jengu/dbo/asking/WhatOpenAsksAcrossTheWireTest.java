package cloud.jengu.dbo.asking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the binding across the wire asks when it is asked what is open.
 *
 * <p>Pinned on the question rather than on an answer, because what went wrong
 * was the question. {@code status:not=completed} reads as open and is not:
 * a run can end without being done — failed, cancelled — and its Task is not
 * completed, so the surface would call it open while the store called it over.
 * A word in this vocabulary may not mean two things.
 *
 * <p>That the two bindings answer alike is walked against a tenant in the
 * clinical story; this is the half that needs no tenant at all.
 */
class WhatOpenAsksAcrossTheWireTest {

    @Test
    @DisplayName("open names the statuses that still owe something, because a negation asked "
            + "a different question and the surface never answered it")
    void openAsksForTheStatusesThatStillOwe() {
        List<String> asked = new ArrayList<>();
        Questions recording = Across.through(pathAndQuery -> {
            asked.add(pathAndQuery);
            return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0,"
                    + "\"entry\":[]}";
        });

        recording.work().open().count();

        assertEquals(1, asked.size(), "one question, one request: " + asked);
        assertTrue(asked.get(0).contains("status=ready,in-progress,on-hold"),
                "open did not ask for the statuses that still owe: " + asked.get(0));
        assertFalse(asked.get(0).contains(":not"),
                "open asked a negation, which this surface does not answer: " + asked.get(0));
    }

    @Test
    @DisplayName("what waits for a person is asked as the Task's own status and performer "
            + "type: ready, and for a person alone")
    void whatWaitsForAPersonIsAskedInTheTasksOwnWords() {
        List<String> asked = new ArrayList<>();
        Questions recording = Across.through(pathAndQuery -> {
            asked.add(pathAndQuery);
            return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0,"
                    + "\"entry\":[]}";
        });

        recording.work().awaiting(cloud.jengu.dbo.work.Awaits.PERSON).count();

        assertTrue(asked.get(0).contains("status=ready&performer-type=person"),
                "a person's list was not asked as ready and for a person alone: " + asked.get(0));
    }
}
