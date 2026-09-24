package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.tenant.api.Change;
import cloud.jengu.dbo.tenant.api.TenantDomain;
import cloud.jengu.dbo.core.api.feed.ChangeKind;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The narrowest crossing, closed.
 *
 * <p>A feed carries the exact version's payload, and handing that to an
 * observer is the surface the boundary document names: every integrator takes
 * the feed instead of the data plane, and the boundary is decorative. The
 * tempting repair — let an observer declare the types it may receive — is
 * refused in the same document, because a declaration of types with no anchor
 * is type-level access control wearing a step's clothing, and a feed has no
 * anchor to give.
 *
 * <p>So what is asserted here is that the payload does not reach an observer
 * of a tenant's records or of its trail, and that it does reach one of the two
 * domains an enumerated reason already admits.
 */
class AFeedSaysWhatChangedNotWhatItSaysTest {

    private static final byte[] PAYLOAD =
            "{\"resourceType\":\"Patient\",\"name\":[{\"family\":\"Potter\"}]}"
                    .getBytes(StandardCharsets.UTF_8);

    private static FeedItem written(String typeName) {
        return new FeedItem(7, "01a0af1c-0000-7000-8000-000000000001", typeName, 3,
                ChangeKind.UPDATED, Instant.parse("2026-09-18T09:00:00Z"), PAYLOAD,
                false, "r5", List.of());
    }

    @Test
    @Proving(DboPromises.TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS)
    @DisplayName("an observer of a tenant's records is told that a record changed, and not "
            + "what it says")
    void contentDoesNotTravelOnTheFeed() {
        Change change = TenantObservations.asChanges(
                TenantDomain.CONTENT, List.of(written("Patient"))).getFirst();

        assertTrue(change.content().isEmpty(),
                "the record's payload reached an observer, which is the feed becoming the "
                        + "door the data plane exists to be");

        // Everything a consumer needs in order to NOTICE is still there. This
        // half matters as much as the refusal: a feed that said nothing would
        // have been replaced by somebody with one that said everything.
        assertEquals("Patient", change.typeName());
        assertEquals("01a0af1c-0000-7000-8000-000000000001", change.objectId());
        assertEquals(3, change.versionId());
        assertEquals(Instant.parse("2026-09-18T09:00:00Z"), change.committedAt());
        assertFalse(change.deleted());
    }

    @Test
    @Proving(DboPromises.TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS)
    @DisplayName("nor does the trail's, which is the read that says who saw whom")
    void theTrailIsNotReadThroughAFeedEither() {
        assertTrue(TenantObservations.asChanges(
                        TenantDomain.AUDIT, List.of(written("AuditEntry")))
                        .getFirst().content().isEmpty(),
                "an audit entry's content reached an observer — the most sensitive read in "
                        + "the store, taken through the one surface with no run to name");
    }

    @Test
    @Proving(DboPromises.TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS)
    @DisplayName("work and identity carry theirs, because each is the machinery's own "
            + "bookkeeping")
    void theMachinerysOwnRecordsAreNotACrossing() {
        // Not an inconsistency: a task describing the delivery of a task does
        // not terminate, which is the self-reference the direct list already
        // admits. An observer of runs is watching the mechanism, not the
        // people it is about.
        assertTrue(TenantObservations.asChanges(
                        TenantDomain.WORK, List.of(written("WorkItem")))
                        .getFirst().content().isPresent(),
                "an observer of work was denied the bookkeeping it exists to read");
        assertTrue(TenantObservations.asChanges(
                        TenantDomain.IDENTITY, List.of(written("Credential")))
                        .getFirst().content().isPresent(),
                "an observer of identity was denied what authorises a task in the first "
                        + "place, which is the regress self-reference exists for");
    }

    @Test
    @Proving(DboPromises.TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS)
    @DisplayName("the rule is the domain's, so a new stream has to say which it is")
    void aDomainStatesWhetherItCarriesContent() {
        // The discriminator lives on the domain rather than at the call site,
        // which is what stops the next stream inheriting an answer nobody
        // made for it — the same mistake the inline mount conditions were.
        assertFalse(TenantDomain.CONTENT.carriesContent());
        assertFalse(TenantDomain.AUDIT.carriesContent());
        assertTrue(TenantDomain.WORK.carriesContent());
        assertTrue(TenantDomain.IDENTITY.carriesContent());
    }
}
