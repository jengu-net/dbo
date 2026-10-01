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
 * {@code Holder.NOBODY} is "done, OR abandoned", so an abandoned run's Task is
 * not completed and the surface called it open while the store called it
 * closed. A word in this vocabulary may not mean two things.
 *
 * <p>That the two bindings answer alike is walked against a tenant in the
 * clinical story; this is the half that needs no tenant at all.
 */
class WhatOpenAsksAcrossTheWireTest {

    @Test
    @DisplayName("open names the holders that still owe something, because a negation asked a "
            + "different question and the surface never answered it")
    void openAsksForTheHoldersThatStillOwe() {
        List<String> asked = new ArrayList<>();
        Questions recording = Across.through(pathAndQuery -> {
            asked.add(pathAndQuery);
            return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0,"
                    + "\"entry\":[]}";
        });

        recording.work().open().count();

        assertEquals(1, asked.size(), "one question, one request: " + asked);
        assertTrue(asked.get(0).contains("owner=automation,retry,person"),
                "open did not ask for the holders that still owe: " + asked.get(0));
        assertFalse(asked.get(0).contains(":not"),
                "open asked a negation, which this surface does not answer: " + asked.get(0));
    }
}
