package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jurisdictions as a tree, read from the files that declare them.
 *
 * <p>The world is the one the decision was argued over: a hospital in Estonia
 * and one in Germany, both countries in the EU, and a second root nobody
 * shares with them.
 */
class ZonesFormATreeTest {

    private static TenantSpec zone(String code, String parent, String... exchanges) {
        return TenantSpec.parse("""
                {"code":"%s","face":"r5","zoneRoot":true,%s"exchanges":[%s],
                 "types":[{"name":"ValueSet","identity":"canonical",
                           "handling":"operational"}]}"""
                .formatted(code, parent == null ? "" : "\"zone\":\"" + parent + "\",",
                        String.join(",", Stream.of(exchanges).map(e -> "\"" + e + "\"")
                                .toList())));
    }

    private static TenantSpec member(String code, String zone) {
        return TenantSpec.parse("""
                {"code":"%s","face":"r5","zone":"%s",
                 "types":[{"name":"Observation","identity":"internal",
                           "handling":"operational"}]}""".formatted(code, zone));
    }

    private static final List<TenantSpec> EUROPE = List.of(
            zone("eu", null, "care.records"),
            zone("ee", "eu", "care.records"),
            zone("de", "eu", "care.records"),
            member("tallinn", "ee"),
            member("tartu", "ee"),
            member("berlin", "de"),
            zone("elsewhere", null, "care.records"),
            member("faraway", "elsewhere"));

    @Test
    @Proving(DboPromises.ZONE_A_ZONE_NAMES_THE_ZONE_IT_BELONGS_TO)
    @DisplayName("a zone belongs to the zone it names, and zones that lead back to one of "
            + "their own are refused, naming every zone on the way round")
    void zonesFormATree() {
        ZoneTree europe = ZoneTree.of(EUROPE);
        assertEquals(List.of("ee", "eu"), europe.chainOf("tallinn"));
        assertEquals(List.of("eu"), europe.chainOf("ee"), "a zone is not above itself");
        assertEquals(List.of(), europe.chainOf("elsewhere"), "a second root stands");
        EUROPE.forEach(europe::refuse);

        ZoneTree round = ZoneTree.of(List.of(
                zone("ee", "nordic"), zone("nordic", "eu"), zone("eu", "ee")));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> round.refuse(zone("ee", "nordic")));
        assertTrue(refused.getMessage().contains("ee → nordic → eu → ee"),
                "the refusal does not show the way round: " + refused.getMessage());

        IllegalArgumentException itself = assertThrows(IllegalArgumentException.class,
                () -> zone("eu", "eu"));
        assertTrue(itself.getMessage().contains("eu"), itself.getMessage());
    }

    @Test
    @Proving(DboPromises.ZONE_A_ZONE_ONLY_NARROWS_THE_ZONE_IT_BELONGS_TO)
    @DisplayName("a zone may permit only what the zone it belongs to permits, and only a zone "
            + "permits anything")
    void aZoneOnlyNarrows() {
        TenantSpec looser = zone("de", "eu", "care.records", "care.gossip");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ZoneTree.of(List.of(zone("eu", null, "care.records"), looser))
                        .refuse(looser));
        assertTrue(refused.getMessage().contains("de")
                        && refused.getMessage().contains("eu")
                        && refused.getMessage().contains("care.gossip")
                        && !refused.getMessage().contains("care.records"),
                "the refusal does not name both zones and only what went too far: "
                        + refused.getMessage());

        ZoneTree.of(List.of(zone("eu", null, "care.records", "care.gossip"),
                zone("de", "eu", "care.records"))).refuse(zone("de", "eu", "care.records"));

        assertThrows(IllegalArgumentException.class, () -> TenantSpec.parse("""
                {"code":"berlin","face":"r5","zone":"de","exchanges":["care.records"],
                 "types":[{"name":"Observation","identity":"internal",
                           "handling":"operational"}]}"""),
                "a hospital permitted something only a jurisdiction permits");
        assertThrows(IllegalArgumentException.class, () -> zone("eu", null, "care"),
                "an exchange that is not a process was taken");
    }

    @Test
    @Proving(DboPromises.ZONE_WORK_CROSSES_THROUGH_THE_LOWEST_ZONE_TWO_TENANTS_SHARE)
    @DisplayName("work between two tenants goes up to the lowest zone they share and down, "
            + "and between tenants that share none it has no way to go")
    void workCrossesThroughTheLowestSharedZone() {
        ZoneTree europe = ZoneTree.of(EUROPE);

        ZoneTree.Route berlinAsksTallinn = europe.route("berlin", "tallinn");
        assertEquals("eu", berlinAsksTallinn.lowest());
        assertEquals(List.of("de"), berlinAsksTallinn.up());
        assertEquals(List.of("ee"), berlinAsksTallinn.down());
        assertEquals(List.of("de", "eu", "ee"), berlinAsksTallinn.zones());
        assertTrue(berlinAsksTallinn.permits("care.records"));
        assertFalse(berlinAsksTallinn.permits("care.gossip"),
                "a process no zone permits was let through");

        ZoneTree.Route neighbours = europe.route("tartu", "tallinn");
        assertEquals("ee", neighbours.lowest(), "neighbours went further up than they share");
        assertEquals(List.of("ee"), neighbours.zones());

        ZoneTree.Route regulatorAsks = europe.route("ee", "tallinn");
        assertEquals("ee", regulatorAsks.lowest());
        assertEquals(List.of(), regulatorAsks.up());

        IllegalArgumentException apart = assertThrows(IllegalArgumentException.class,
                () -> europe.route("faraway", "tallinn"));
        assertTrue(apart.getMessage().contains("elsewhere")
                        && apart.getMessage().contains("eu"),
                "the refusal does not say which roots they are under: " + apart.getMessage());

        IllegalArgumentException alone = assertThrows(IllegalArgumentException.class,
                () -> ZoneTree.of(List.of(member("lonely", "nowhere"),
                        TenantSpec.parse("""
                                {"code":"solo","face":"r5","types":[{"name":"Observation",
                                 "identity":"internal","handling":"operational"}]}""")))
                        .route("solo", "lonely"));
        assertTrue(alone.getMessage().contains("solo"), alone.getMessage());
    }
}
