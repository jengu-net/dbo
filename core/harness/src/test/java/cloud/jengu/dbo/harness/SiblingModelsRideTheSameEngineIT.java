package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.testmodel.GadgetModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Non-FHIR object models ride the same engine as FHIR resources, not beside
 * it (R6).
 *
 * <p>ONE {@link PgObjectStore} is built from a FHIR personality's
 * registrations and a plain-JSON model's registrations together, and every
 * assertion goes through the same engine primitive for both families. The
 * failure this guards against is a second store growing up beside the first —
 * "the engine for FHIR" and "the engine for everything else" — which is how a
 * platform ends up with two version semantics and two identity rules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiblingModelsRideTheSameEngineIT {

    private static final String EID = "https://ee.ee/eid";

    static PgObjectStore engine;

    @BeforeAll
    void up() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(SharedPostgres.urlFor("SiblingModelsRideTheSameEngineIT"));
        pg.setUser(SharedPostgres.get().getUsername());
        pg.setPassword(SharedPostgres.get().getPassword());

        // one registration list, two object models: HAPI-extracted FHIR
        // resources and Jackson-extracted gadgets, registered side by side
        R4Personality fhir = new R4Personality(List.of(
                FhirTypeConfig.identifier("Patient", EID)));
        List<TypeRegistration> both = new ArrayList<>(fhir.registrations());
        both.addAll(GadgetModel.registrations());
        engine = new PgObjectStore(pg, both);
    }

    @Test
    @DisplayName("one engine serves both families through the same primitives — "
            + "write, identifier lookup, history")
    @Proving(DboPromises.CORE_SIBLING_MODELS)
    void bothFamiliesAnswerThroughTheSamePrimitives() {
        PutResult patient = engine.put(PutRequest.create("Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010777"}],
                 "name":[{"family":"Naaber"}]}""".formatted(EID)
                .getBytes(StandardCharsets.UTF_8)));
        PutResult gadget = engine.put(PutRequest.create("Gadget",
                "{\"serial\":\"S-SIB-1\",\"vendor\":\"acme\",\"name\":\"Pump\",\"weightGrams\":900}"
                        .getBytes(StandardCharsets.UTF_8)));

        // the SAME identifier primitive answers for both, each under the
        // identity systems its own model declared
        List<StoredObject> patients = engine.getByIdentifier("Patient",
                List.of(new Identifier(EID, "38001010777")));
        List<StoredObject> gadgets = engine.getByIdentifier("Gadget",
                List.of(new Identifier(GadgetModel.SERIAL_SYSTEM, "S-SIB-1")));
        assertEquals(patient.id(), patients.get(0).id());
        assertEquals(gadget.id(), gadgets.get(0).id());

        // and the SAME history primitive: a version is a version, whichever
        // model wrote it
        assertEquals(1, engine.history("Patient", patient.id()).size());
        assertEquals(1, engine.history("Gadget", gadget.id()).size());
    }

    @Test
    @DisplayName("the engine's rules are one set of rules: optimistic concurrency "
            + "refuses a stale write identically for a FHIR resource and a gadget")
    @Proving(DboPromises.CORE_SIBLING_MODELS)
    void oneSetOfRulesNotTwo() {
        PutResult patient = engine.put(PutRequest.create("Patient", """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010778"}]}""".formatted(EID)
                .getBytes(StandardCharsets.UTF_8)));
        PutResult gadget = engine.put(PutRequest.create("Gadget",
                "{\"serial\":\"S-SIB-2\",\"vendor\":\"acme\",\"name\":\"Valve\",\"weightGrams\":120}"
                        .getBytes(StandardCharsets.UTF_8)));

        engine.put(PutRequest.update("Patient", patient.id(), 1, """
                {"resourceType":"Patient",
                 "identifier":[{"system":"%s","value":"38001010778"}],
                 "name":[{"family":"Muudetud"}]}""".formatted(EID)
                .getBytes(StandardCharsets.UTF_8)));
        engine.put(PutRequest.update("Gadget", gadget.id(), 1,
                "{\"serial\":\"S-SIB-2\",\"vendor\":\"acme\",\"name\":\"Valve b\",\"weightGrams\":121}"
                        .getBytes(StandardCharsets.UTF_8)));

        // a stale expected version is the same typed refusal on either side —
        // not a FHIR behaviour with a sibling approximation
        assertThrows(VersionConflictException.class, () ->
                engine.put(PutRequest.update("Patient", patient.id(), 1,
                        "{\"resourceType\":\"Patient\"}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(VersionConflictException.class, () ->
                engine.put(PutRequest.update("Gadget", gadget.id(), 1,
                        ("{\"serial\":\"S-SIB-2\",\"vendor\":\"acme\",\"name\":\"Valve c\","
                                + "\"weightGrams\":122}").getBytes(StandardCharsets.UTF_8))));

        assertTrue(engine.history("Patient", patient.id()).size() == 2
                        && engine.history("Gadget", gadget.id()).size() == 2,
                "the refused writes left both histories exactly where they were");
    }
}
