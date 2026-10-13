package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How long what came back on a crossing outlives the work it was asked for. */
class WhatCameBackIsKeptAsLongAsTheZoneSaysTest {

    private static final Instant ENDED = Instant.parse("2026-10-13T08:00:00Z");

    @Test
    @Proving(DboPromises.ZONE_WHAT_CAME_BACK_GOES_WITH_ITS_PURPOSE)
    @DisplayName("an answer goes as soon as its work is over, unless the jurisdiction declared it "
            + "may be kept, and then once that has passed")
    void anAnswerIsKeptAsLongAsTheZoneSaysAndNoLonger() {
        assertTrue(HeldAnswers.due(ENDED, null, ENDED),
                "with nothing declared, the answer outlived its work");
        Duration week = Duration.ofDays(7);
        assertFalse(HeldAnswers.due(ENDED, week, ENDED.plus(Duration.ofDays(1))),
                "an answer the zone said may be kept a week went after a day");
        assertTrue(HeldAnswers.due(ENDED, week, ENDED.plus(week)),
                "an answer was kept past what the zone declared");
    }
}
