package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.api.feed.FeedItem;
import cloud.jengu.dbo.core.api.feed.FeedSelection;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upstream selects by name, rather than the dependent discarding what it
 * did not want.
 *
 * <p>A dependency declares types, and a derived one also names the canonicals
 * its closure reaches. Until now both were applied at the DEPENDENT: the feed
 * answered with everything and the sync engine filtered, so the reading, the
 * moving and the parse of every item nobody wanted were all done first. Item
 * 021 settled that a filter is not a predicate that travels — it is a set of
 * names, computed once and agreed between the two ends, and the upstream
 * selects by it.
 *
 * <p><b>Selection, not execution.</b> A type is compared to a list of type
 * names and a canonical to a list of canonicals. No expression crosses, so
 * this is not one tenant running another's query against the rule that a
 * tenant declares only against its direct upstream.
 *
 * <p><b>And a canonical narrows only what has one.</b> An ordinary record is
 * not withheld for being absent from a list of definition urls, which is the
 * mistake that would make a derived dependency stop delivering a tenant's
 * patients the moment it was switched on.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnUpstreamSelectsByNameIT {

    private static final String KEPT = "https://ee.ee/StructureDefinition/ValitudProfiil";
    private static final String DROPPED = "https://ee.ee/StructureDefinition/ValimataProfiil";

    static SharedTenants.Tenant tenant;

    @BeforeAll
    void up() throws Exception {
        tenant = SharedTenants.of(SharedTenants.Shape.R4_PROFILED);
        for (String url : List.of(KEPT, DROPPED)) {
            tenant.store().create("""
                    {"resourceType":"StructureDefinition",
                     "url":"%s","name":"%s","status":"active","kind":"resource",
                     "abstract":false,"type":"Patient",
                     "baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient",
                     "derivation":"constraint",
                     "differential":{"element":[
                       {"id":"Patient.name","path":"Patient.name","min":1,"max":"1"}]}}"""
                    .formatted(url, url.substring(url.lastIndexOf('/') + 1)));
        }
        tenant.store().create("{\"resourceType\":\"Patient\",\"gender\":\"female\"}");
    }

    @Test
    @DisplayName("a consumer that names canonicals is sent those and not the others, and the "
            + "records that carry no canonical are unaffected")
    @Proving(DboPromises.VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS)
    void theUpstreamSendsWhatWasNamed() {
        // Definitions move on a feed of their own, so the canonicals are
        // asked of that one and the records of the other.
        List<String> allUrls = canonicalsIn(drain(tenant.definitionsFeed(),
                "selects-everything", FeedSelection.EVERYTHING));
        assertTrue(allUrls.contains(KEPT) && allUrls.contains(DROPPED),
                "the unnarrowed feed did not carry both profiles, so there is nothing to "
                        + "narrow: " + allUrls);

        List<String> namedUrls = canonicalsIn(drain(tenant.definitionsFeed(),
                "selects-by-name", new FeedSelection(Set.of("StructureDefinition"), Set.of(KEPT))));
        assertTrue(namedUrls.contains(KEPT), "the named canonical was not sent: " + namedUrls);
        assertTrue(!namedUrls.contains(DROPPED),
                "a canonical nobody named was sent anyway, so the upstream is not selecting: "
                        + namedUrls);

        // A RECORD CARRIES NO CANONICAL AND MUST STILL ARRIVE. Asked with a
        // manifest that names only definitions: a derived dependency that
        // stopped delivering a tenant's patients the moment it named a
        // profile would be worse than no narrowing at all.
        List<FeedItem> records = drain(tenant.feed(), "selects-records",
                new FeedSelection(Set.of("Patient"), Set.of(KEPT)));
        assertTrue(records.stream().anyMatch(item -> "Patient".equals(item.typeName())),
                "a record carrying no canonical was withheld by a list of definition urls");

        // And a type nobody named is not sent, which is the half that used to
        // be done by discarding after the fact.
        List<FeedItem> onlyPatients = drain(tenant.feed(), "selects-one-type",
                FeedSelection.ofTypes(Set.of("Patient")));
        assertEquals(List.of(), onlyPatients.stream()
                        .map(FeedItem::typeName).filter(one -> !"Patient".equals(one))
                        .distinct().toList(),
                "a type nobody named was sent");
        assertTrue(!onlyPatients.isEmpty(), "naming one type sent nothing at all");
    }

    /** The canonicals the chunk carries, read off the payloads it delivered. */
    private static List<String> canonicalsIn(List<FeedItem> items) {
        List<String> urls = new ArrayList<>();
        java.util.regex.Pattern url = java.util.regex.Pattern.compile(
                "\"url\"\\s*:\\s*\"([^\"]+)\"");
        for (FeedItem item : items) {
            if (item.payload() == null) {
                continue;
            }
            java.util.regex.Matcher found = url.matcher(
                    new String(item.payload(), java.nio.charset.StandardCharsets.UTF_8));
            if (found.find()) {
                urls.add(found.group(1));
            }
        }
        return urls;
    }

    private static List<FeedItem> drain(cloud.jengu.dbo.core.api.feed.ChangeFeed feed,
            String consumer, FeedSelection wanted) {
        List<FeedItem> all = new ArrayList<>();
        FeedChunk<FeedItem> chunk;
        while (!(chunk = feed.readFor(consumer, 200, wanted)).items().isEmpty()) {
            all.addAll(chunk.items());
            feed.ack(consumer, chunk.nextCursor());
        }
        return all;
    }
}
