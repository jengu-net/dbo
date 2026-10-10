package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.StoreUnreachableException;
import cloud.jengu.dbo.core.api.feed.ChangeFeed;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.transport.Origin;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.ConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a site says it serves, read with no node behind it: the declarations
 * the source hands over, and what it keeps between reads.
 */
class ASiteServesItsPlacesAsTheyAreDeclaredTest {

    private static final String CLINIC = """
            {"code":"haru","face":"r4"}""";

    @TempDir
    Path kept;

    @Test
    @DisplayName("a place is declared as its origin declares it, and the declaration is kept")
    @Proving(DboPromises.SYNC_A_PLACE_IS_DECLARED_AS_ITS_ORIGIN_IS)
    void aPlaceIsDeclaredAsItsOriginIs() throws Exception {
        PlacesConfigSource source = new PlacesConfigSource(kept, null);
        source.reaches(origin("haru", CLINIC));

        ConfigSource.Fetch read = source.fetch();

        assertEquals(Map.of("haru", CLINIC), declared(read));
        assertTrue(read.complete());
        assertEquals(CLINIC, Files.readString(kept.resolve("haru.json")),
                "the declaration was not kept for a read with the link down");
    }

    @Test
    @DisplayName("an origin that cannot be reached, or has been let go, leaves its place "
            + "declared as it was last kept")
    @Proving(DboPromises.SYNC_A_PLACE_IS_DECLARED_AS_ITS_ORIGIN_IS)
    void anOriginAwayLeavesThePlaceDeclared() {
        PlacesConfigSource source = new PlacesConfigSource(kept, null);
        Origin answering = origin("haru", CLINIC);
        source.reaches(answering);
        source.fetch();

        source.reaches(origin("haru", null));
        assertEquals(Map.of("haru", CLINIC), declared(source.fetch()),
                "a place was withdrawn because its origin did not answer");

        source.letGo(answering);
        assertEquals(Map.of("haru", CLINIC), declared(new PlacesConfigSource(kept, null).fetch()),
                "a site restarted with no link forgot what it serves");
    }

    @Test
    @DisplayName("a place with nothing kept and no origin to ask fails the read rather than "
            + "saying the site serves nothing, and one whose kept declaration is removed is "
            + "no longer served")
    @Proving(DboPromises.SYNC_A_PLACE_IS_DECLARED_AS_ITS_ORIGIN_IS)
    void nothingKeptAndNobodyToAskIsNotEmpty() throws Exception {
        PlacesConfigSource source = new PlacesConfigSource(kept, null);
        source.reaches(origin("haru", null));

        assertThrows(IllegalStateException.class, source::fetch);

        Files.writeString(kept.resolve("haru.json"), CLINIC);
        PlacesConfigSource restarted = new PlacesConfigSource(kept, null);
        assertEquals(Map.of("haru", CLINIC), declared(restarted.fetch()));
        Files.delete(kept.resolve("haru.json"));
        assertEquals(Map.of(), declared(restarted.fetch()));
    }

    @Test
    @DisplayName("what the deployment declares itself is served beside its places")
    @Proving(DboPromises.SYNC_A_PLACE_IS_DECLARED_AS_ITS_ORIGIN_IS)
    void theDeploymentsOwnAreServedBeside() {
        ConfigApplication.Declared own = new ConfigApplication.Declared(
                TenantDeclarationModel.TYPE, "kohalik",
                "{\"code\":\"kohalik\"}".getBytes(StandardCharsets.UTF_8));
        PlacesConfigSource source = new PlacesConfigSource(kept,
                () -> new ConfigSource.Fetch(List.of(own), "own", true));
        source.reaches(origin("haru", CLINIC));

        assertEquals(Map.of("haru", CLINIC, "kohalik", "{\"code\":\"kohalik\"}"),
                declared(source.fetch()));
    }

    @Test
    @DisplayName("a place's face root is declared beside it from this node's own release, "
            + "unless the deployment declares that root itself")
    @Proving(DboPromises.SYNC_A_PLACE_TAKES_ITS_FACE_FROM_A_ROOT_BESIDE_IT)
    void aPlacesFaceRootIsDeclaredBesideIt() {
        String taking = """
                {"code":"haru","face":"r4",
                 "dependencies":[{"name":"tuum-r4","face":true,
                   "types":["StructureDefinition","SearchParameter","ValueSet","CodeSystem"]}],
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"replicated"},
                  {"name":"SearchParameter","identity":"canonical","handling":"replicated"},
                  {"name":"ValueSet","identity":"canonical","handling":"replicated"},
                  {"name":"CodeSystem","identity":"canonical","handling":"replicated"},
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""";
        PlacesConfigSource source = new PlacesConfigSource(kept, null);
        source.reaches(origin("haru", taking));

        Map<String, String> declared = declared(source.fetch());

        assertEquals(java.util.Set.of("haru", "tuum-r4"), declared.keySet());
        assertTrue(declared.get("tuum-r4").contains("\"faceRoot\":true")
                && declared.get("tuum-r4").contains("\"face\":\"r4\""), declared.get("tuum-r4"));

        ConfigApplication.Declared own = new ConfigApplication.Declared(
                TenantDeclarationModel.TYPE, "tuum-r4",
                "{\"code\":\"tuum-r4\",\"face\":\"r4\",\"faceRoot\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        PlacesConfigSource declaring = new PlacesConfigSource(kept,
                () -> new ConfigSource.Fetch(List.of(own), "own", true));
        declaring.reaches(origin("haru", taking));
        assertEquals(2, declaring.fetch().declarations().size(),
                "a root the deployment declares was declared a second time");
    }

    private static Map<String, String> declared(ConfigSource.Fetch read) {
        Map<String, String> byName = new java.util.TreeMap<>();
        read.declarations().forEach(declared -> byName.put(declared.name(),
                new String(declared.payload(), StandardCharsets.UTF_8)));
        return byName;
    }

    /** An origin answering with this declaration, or unreachable when it is null. */
    private static Origin origin(String tenant, String declaration) {
        return new Origin() {
            @Override
            public String tenant() {
                return tenant;
            }

            @Override
            public String declaration() {
                if (declaration == null) {
                    throw new StoreUnreachableException(tenant + ": the lane did not answer");
                }
                return declaration;
            }

            @Override
            public ChangeFeed records() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChangeFeed definitions() {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChangeFeed definitionsWithoutTheFace() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
