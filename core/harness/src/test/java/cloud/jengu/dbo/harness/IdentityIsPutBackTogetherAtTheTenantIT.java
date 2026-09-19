package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.seal.KeyWrap;
import cloud.jengu.dbo.core.api.seal.ParticipantKey;
import cloud.jengu.dbo.core.api.seal.SigningKey;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.fhir.r4.R4Personality;
import cloud.jengu.dbo.pdi.PdiObjectStore;
import cloud.jengu.dbo.pdi.PdiSetup;
import cloud.jengu.dbo.pdi.PdiSpec;
import cloud.jengu.dbo.pdi.PersonVault;
import cloud.jengu.dbo.postgres.PgChangeFeed;
import cloud.jengu.dbo.postgres.PgObjectStore;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.work.Declarations;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunChain;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import cloud.jengu.dbo.work.SealedPayload;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runner that needs the person asks the tenant, and the asking is the
 * record of it.
 *
 * <p>Opening a sealed payload yields the carrier form — the record with its
 * identifying elements still under the person's key — which is enough for most
 * work and is why a runner needs no vault. Putting the person back together is
 * a further act. It could have been done by handing the runner the vault, and
 * that is the design this refuses: a reassembly performed at the runner is one
 * that happened somewhere nothing wrote it down, and the trail exists to
 * answer who saw whom.
 *
 * <p>So it is a callback, and the same one everywhere — in an appliance it is
 * a local call, which is what makes one rule affordable. What comes back is
 * sealed to the asker, because the plane this may cross holds nothing readable
 * and reassembled identity is the last thing that should be its exception.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdentityIsPutBackTogetherAtTheTenantIT {

    static final String EID = "https://eesti.ee/isikukood";
    private static final String FAMILY = "Kask";
    private static final String CODE = "37001010021";
    private static final StepDeclaration ASSAY =
            StepDeclaration.of("lab.result.assay", "1.0", WorkModel.DOMAIN)
                    .taking("patient", "https://meristem.example/shape/patient");

    static PGSimpleDataSource ds;
    static PersonVault vault;
    static PdiObjectStore store;
    static Runs runs;
    static Declarations declarations;
    static KeyPair analyser;
    static KeyPair signer;
    static final List<String> openings = new ArrayList<>();
    static final List<RunChain.Link> links = new ArrayList<>();

    @BeforeAll
    void up() {
        ds = new PGSimpleDataSource();
        ds.setUrl(SharedPostgres.urlFor("IdentityIsPutBackTogetherAtTheTenantIT"));
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
        signer = SigningKey.newKeyPair();
    }

    @Test
    @DisplayName("the carrier form is what work arrives as, and the person comes back only "
            + "by asking the tenant — sealed, purposed, and on the record")
    @Proving({DboPromises.PROC_IDENTITY_IS_REASSEMBLED_AT_THE_TENANT,
            DboPromises.PROC_WORK_TRAVELS_SEALED})
    void thePersonComesBackOnlyByAsking() throws Exception {
        String patientId = store.put(PutRequest.create("Patient",
                ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                        + "\",\"value\":\"" + CODE + "\"}],\"name\":[{\"family\":\"" + FAMILY
                        + "\"}],\"birthDate\":\"1970-01-01\",\"gender\":\"male\"}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "needs-the-person",
                Map.of("patient", "Patient/" + patientId));
        Lane lane = lane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        // What work arrives as, and what it does not: the analyser holds the
        // record and cannot read the person out of it.
        String carrier = opened(lane.sealed(held).payload().get(0));
        assertTrue(carrier.contains("__pdiEnc"), carrier);
        assertFalse(carrier.contains(FAMILY) || carrier.contains(CODE),
                "the seal handed out an identity, so nothing below this is about a "
                        + "reassembly that had to be asked for: " + carrier);

        int before = openings.size();
        SealedPayload answer = lane.identified(held, "Patient/" + patientId, "TREAT");

        // Sealed to the asker, checked on the bytes themselves.
        //
        // NOT on the wire rendering: every byte array renders as Base64
        // there, so a payload carrying the person in the clear reads exactly
        // like a sealed one to any search of it. An assertion over the
        // rendering would have passed on plaintext — it did, when this was
        // mutated to hand the record back unsealed — which makes it an
        // assertion about Base64 rather than about sealing.
        assertTrue(answer.wrapped().containsKey("analyser"),
                "the answer was sealed to nobody, so either it is readable or it is lost");
        String carried = new String(answer.ciphertext(), StandardCharsets.UTF_8);
        assertFalse(carried.contains(FAMILY) || carried.contains(CODE),
                "the reassembled person travelled readable, so a plane that holds nothing "
                        + "readable would now hold the one thing it most must not");

        String whole = new String(answer.open("analyser", analyser.getPrivate()).payload(),
                StandardCharsets.UTF_8);
        assertTrue(whole.contains(FAMILY) && whole.contains(CODE),
                "the answer did not carry the person, so the callback reassembled nothing "
                        + "and a runner that needed a name still has none: " + whole);

        // And the tenant wrote it down, which is the whole reason it is the
        // tenant that does it. A reassembly at the runner would have been
        // indistinguishable from this one, except here.
        assertEquals(before + 1, openings.size(),
                "putting the person back together left no entry, so the store cannot answer "
                        + "who saw whom — which is the only thing a callback buys over "
                        + "handing the vault to the runner");
        assertEquals("Patient/" + patientId + " by analyser",
                openings.get(openings.size() - 1));
    }

    @Test
    @DisplayName("without a stated purpose it is refused, as every identifying read is")
    @Proving(DboPromises.PROC_IDENTITY_IS_REASSEMBLED_AT_THE_TENANT)
    void anIdentifyingReadWithNoReasonIsRefused() {
        String patientId = store.put(PutRequest.create("Patient",
                ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                        + "\",\"value\":\"37001010099\"}],\"name\":[{\"family\":\"Tamm\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "no-reason-given",
                Map.of("patient", "Patient/" + patientId));
        Lane lane = lane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.identified(held, "Patient/" + patientId, null));
        assertTrue(refused.getMessage().contains("purpose"), refused.getMessage());
    }

    @Test
    @DisplayName("and for a document the run does not name, which is the boundary every "
            + "other reach has")
    @Proving(DboPromises.PROC_IDENTITY_IS_REASSEMBLED_AT_THE_TENANT)
    void aDocumentTheRunNeverNamedIsRefused() {
        String named = store.put(PutRequest.create("Patient",
                ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                        + "\",\"value\":\"37001010077\"}],\"name\":[{\"family\":\"Saar\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        String elsewhere = store.put(PutRequest.create("Patient",
                ("{\"resourceType\":\"Patient\",\"identifier\":[{\"system\":\"" + EID
                        + "\",\"value\":\"37001010088\"}],\"name\":[{\"family\":\"Ilves\"}]}")
                        .getBytes(StandardCharsets.UTF_8))).id();
        Run run = runs.of(ASSAY, RunKind.PIPELINE, "names-one-of-them",
                Map.of("patient", "Patient/" + named));
        Lane lane = lane();
        Run held = lane.claim(run, Duration.ofMinutes(5)).orElseThrow();

        // A real patient, in this store, of a type the step declares — and
        // not this run's. Reassembly is not a second way in.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> lane.identified(held, "Patient/" + elsewhere, "TREAT"));
        assertTrue(refused.getMessage().contains("names no input"), refused.getMessage());
    }

    // ------------------------------------------------------------ fixtures

    private static String opened(SealedPayload payload) throws Exception {
        StoredObject object = payload.open("analyser", analyser.getPrivate());
        return new String(object.payload(), StandardCharsets.UTF_8);
    }

    private static Lane lane() {
        Executor identity = new Executor("analyser", "1.0", "cloud.jengu.test", Scope.BASELINE);
        return Lane.inProcess("t-pdi", runs, new PgChangeFeed(ds, WorkModel.DOMAIN),
                declarations, "analyser", identity, store, null,
                Lane.Entitlement.everything(), null, new Lane.Trail() {
                    @Override
                    public void handedTo(Run r, String to, RunChain.Link link) {
                        links.add(link);
                    }

                    @Override
                    public void opened(Run r, String by, String typeName, String id,
                            RunChain.Link link) {
                        openings.add(typeName + "/" + id + " by " + by);
                        links.add(link);
                    }

                    @Override
                    public List<RunChain.Link> links(Run r) {
                        return List.copyOf(links);
                    }
                }, new Lane.Keys() {
                    @Override
                    public Optional<ParticipantKey> of(String participant) {
                        return Optional.of(ParticipantKey.of(analyser.getPublic()));
                    }

                    @Override
                    public Optional<SigningKey> signing(String participant) {
                        return Optional.of(SigningKey.of(signer.getPublic()));
                    }
                });
    }
}
