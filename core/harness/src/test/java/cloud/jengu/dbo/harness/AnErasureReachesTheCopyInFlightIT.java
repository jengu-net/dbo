package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.wire.RecordWire;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.pdi.PdiObjectStore;
import cloud.jengu.dbo.pdi.PdiSetup;
import cloud.jengu.dbo.pdi.PdiSpec;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.SealedWork;
import cloud.jengu.dbo.work.WorkModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is sealed is the carrier form, so a sealed copy still in flight after
 * an erasure is in the same state as the store's own records after a shred.
 *
 * <p>The person-key layer sits inside the transport seal: the analyser that
 * opens the seal gets the record as the store's encrypted disclosure mode
 * hands it out — identifying elements under the person's key, which the
 * analyser never held. Shred the person and the copy is unchanged and now
 * unopenable by anybody, with no special case for copies in flight.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnErasureReachesTheCopyInFlightIT {

    static final String EID = "https://eesti.ee/isikukood";
    private static final StepDeclaration ASSAY =
            StepDeclaration.of("lab.result.assay", "1.0", WorkModel.DOMAIN)
                    .taking("patient", "https://meristem.example/shape/patient");

    static PGSimpleDataSource ds;
    static PersonVault vault;
    static PdiObjectStore store;
    static Runs runs;
    static Declarations declarations;
    static KeyPair analyser;
    static final List<String> openings = new ArrayList<>();

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("AnErasureReachesTheCopyInFlightIT"));
        ds.setUser(SharedPostgres.get().getUsername());
        ds.setPassword(SharedPostgres.get().getPassword());
        R4Personality personality = new R4Personality(List.of(
                new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER, Set.of(EID),
                        Handling.operational())));
        PdiSpec spec = PdiSpec.fhir();
        List<TypeRegistration> types = new ArrayList<>(
                PdiSetup.transform(personality.registrations(), spec));
        types.addAll(WorkModel.registrations());
        byte[] workingKey = new byte[32];
        new SecureRandom().nextBytes(workingKey);
        vault = new PersonVault(ds, workingKey);
        store = new PdiObjectStore(new PgObjectStore(ds, types), vault, spec,
                cloud.jengu.dbo.fhir.r4.R4FhirVersion.INSTANCE.face()
                        .require(cloud.jengu.dbo.core.face.Coarsening.class));
        runs = new Runs(store);
        declarations = new Declarations(store, new PgChangeFeed(ds, WorkModel.DOMAIN),
                Duration.ofSeconds(30));
        analyser = KeyWrap.newParticipantKeyPair();
    }

    @Test
    @DisplayName("a copy sealed before the shred yields, after it, a record whose identity "
            + "no key can reassemble — the same state as the store's own records")
    @Proving(DboPromises.PROC_WORK_TRAVELS_SEALED)
    void theCopyInFlightIsInTheSameStateAsTheRecord() throws Exception {
        String patientId = store.put(PutRequest.create("Patient",
                ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                        + "\",\"value\":\"37001010021\"}],\"name\":[{\"family\":\"Kask\"}],"
                        + "\"birthDate\":\"1970-01-01\",\"gender\":\"male\"}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "sealed-before-shred",
                Map.of("patient", "Patient/" + patientId));
        Executor identity = new Executor("analyser", "1.0", "cloud.jengu.test", Scope.BASELINE);
        Lane lane = Lane.inProcess("t-pdi", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, "analyser", identity, store, null,
                Lane.Entitlement.everything(), null, new Lane.Trail() {
                    @Override
                    public void handedTo(Run r, String to) {
                    }

                    @Override
                    public void opened(Run r, String by, String typeName, String id) {
                        openings.add(typeName + "/" + id + " by " + by);
                    }
                }, participant -> Optional.of(ParticipantKey.of(analyser.getPublic())));
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        SealedWork inFlight = lane.sealed(held);
        String wire = RecordWire.write(RecordWire.encode(inFlight));
        assertTrue(wire.contains("Patient/" + patientId), "the manifest names the document");
        assertFalse(wire.contains("Kask") || wire.contains("37001010021"),
                "the carrier sees an identity: " + wire);

        // Opened by the analyser before any erasure: the carrier form, with
        // the identifying elements under the person's key it never held.
        String openedBefore = open(inFlight);
        assertTrue(openedBefore.contains("__pdiEnc"), openedBefore);
        assertFalse(openedBefore.contains("Kask") || openedBefore.contains("1970-01-01"),
                "the seal hands out what the store's encrypted disclosure mode hands out, "
                        + "and that is not the identity: " + openedBefore);
        lane.opened(held, "Patient/" + patientId);
        assertEquals(List.of("Patient/" + patientId + " by analyser"), openings,
                "the opening is reported from where the key was used");

        vault.shred(patientId);

        // The same copy, after the shred: unchanged bytes, and the key that
        // would reassemble the identity is gone from the only place it was.
        String openedAfter = open(inFlight);
        assertEquals(openedBefore, openedAfter,
                "a copy in flight is a copy; the shred did not have to find it");
        assertTrue(vault.keyFor(patientId, false).isEmpty(),
                "the person's key is destroyed, so the ciphertext the copy carries is "
                        + "ciphertext for everybody now — with no special case for copies");
        String record = new String(store.get("Patient", patientId).orElseThrow().payload(),
                StandardCharsets.UTF_8);
        assertFalse(record.contains("Kask") || record.contains("__pdiEnc"),
                "and the store's own record reads pseudonymous and clean: " + record);
    }

    private static String open(SealedWork work) throws Exception {
        StoredObject opened = work.payload().get(0).open("analyser", analyser.getPrivate());
        return new String(opened.payload(), StandardCharsets.UTF_8);
    }
}
