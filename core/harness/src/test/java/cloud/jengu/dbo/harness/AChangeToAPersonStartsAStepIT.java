package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.runner.Lane;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.work.Executor;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.Scope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A change to a record about a person starts a step, behind the membrane as
 * anywhere: one run per version, its slot read as the participant may read the
 * person, and a search the membrane would answer with nobody refused where the
 * tenant is declared.
 *
 * <p>A world of its own because its tenant holds people behind the membrane
 * and steps that start on every change to them, and because two of its
 * declarations are meant to be refused; the shared world's tenants holding
 * people declare no such steps, and adding them would start runs in every
 * class after this one.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AChangeToAPersonStartsAStepIT {

    private static final String RUN = Long.toString(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 40), 36);
    private static final String CLINIC = "people-" + RUN;
    private static final String BY_NAME = "byname-" + RUN;
    private static final String NONSENSE = "nonsense-" + RUN;
    private static final String NID = "urn:example:nid";
    private static final List<String> PEOPLE = List.of("Person", "Patient", "Practitioner",
            "RelatedPerson");

    private final HttpClient http = HttpClient.newHttpClient();
    private LocalDatabasePerTenantProvisioner databases;
    private TenantRuntimeManager manager;

    @BeforeAll
    void aClinicHoldingPeople() throws Exception {
        databases = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("AChangeToAPersonStartsAStepIT"),
                SharedPostgres.get().getUsername(), SharedPostgres.get().getPassword());
        Path dir = Files.createTempDirectory("dbo-person-changes");
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, databases, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
        StringBuilder types = new StringBuilder();
        StringBuilder steps = new StringBuilder();
        for (String type : PEOPLE) {
            types.append("""
                    {"name":"%s","identity":"identifier","systems":["%s"],
                     "handling":"operational"},""".formatted(type, NID));
            steps.append("""
                    {"code":"people.%s.watch","slots":{"subject":"Reference(%s)"},
                     "starts":{"change":"%s","keep":"P7D","where":"origin"}},"""
                    .formatted(type.toLowerCase(), type, type));
        }
        Files.writeString(dir.resolve(CLINIC + ".json"), """
                {"code":"%s","face":"r5","pdi":true,
                 "types":[%s
                  {"name":"Device","identity":"internal","handling":"operational"}],
                 "steps":[%s
                  {"code":"people.women.watch","slots":{"subject":"Reference(Patient)"},
                   "starts":{"change":"Patient?gender=female","keep":"P7D","where":"origin"}}]}"""
                .formatted(CLINIC, types, steps));
        Files.writeString(dir.resolve(BY_NAME + ".json"), """
                {"code":"%s","face":"r5","pdi":true,
                 "types":[{"name":"Person","identity":"identifier","systems":["%s"],
                   "handling":"operational"}],
                 "steps":[{"code":"people.person.byname","slots":{"subject":"Reference(Person)"},
                   "starts":{"change":"Person?family=Lovelace","keep":"P7D","where":"origin"}}]}"""
                .formatted(BY_NAME, NID));
        Files.writeString(dir.resolve(NONSENSE + ".json"), """
                {"code":"%s","face":"r5",
                 "types":[{"name":"Device","identity":"internal","handling":"operational"}],
                 "steps":[{"code":"people.device.odd","slots":{"subject":"Reference(Device)"},
                   "starts":{"change":"Device?no-such-parameter=1","keep":"P7D",
                             "where":"origin"}}]}"""
                .formatted(NONSENSE));
        UntilServed.scan(manager, CLINIC);
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        SuiteDatabases.retire(databases);
    }

    @Test
    @Order(1)
    @DisplayName("a change to a person of each type starts one run for the record created and "
            + "one for each later version, behind the membrane")
    @Proving(DboPromises.PROC_A_CHANGE_TO_A_PERSON_STARTS_A_STEP_BEHIND_THE_MEMBRANE)
    void eachVersionOfAPersonStartsARun() throws Exception {
        for (String type : PEOPLE) {
            String id = write(type, "Lovelace", "3900101" + type.length());
            String step = "people." + type.toLowerCase() + ".watch";
            assertTrue(started(step, id, 1), type + " version 1 started no run");
            update(type, id, "Byron", "3900101" + type.length());
            assertTrue(started(step, id, 2), type + " version 2 started no run");
        }
    }

    @Test
    @Order(2)
    @DisplayName("a search on an element that does not identify somebody is answered: it starts "
            + "runs for the people it finds and none for the rest")
    @Proving(DboPromises.PROC_A_CHANGE_TO_A_PERSON_STARTS_A_STEP_BEHIND_THE_MEMBRANE)
    void aSearchThatDoesNotIdentifyIsAnswered() throws Exception {
        String woman = engine().put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","gender":"female","identifier":[{"system":"%s",
                 "value":"48001010001"}],"name":[{"family":"Curie"}]}""").formatted(NID)
                .getBytes(StandardCharsets.UTF_8))).id();
        String man = engine().put(PutRequest.create("Patient", ("""
                {"resourceType":"Patient","gender":"male","identifier":[{"system":"%s",
                 "value":"38001010002"}],"name":[{"family":"Pasteur"}]}""").formatted(NID)
                .getBytes(StandardCharsets.UTF_8))).id();
        assertTrue(started("people.women.watch", woman, 1), "the woman started no run");
        assertTrue(started("people.patient.watch", man, 1), "the man's change was not read");
        assertFalse(runs().byKey("people.women.watch/change/" + man + "/1").isPresent(),
                "a search on gender started a run for a man");
    }

    @Test
    @Order(3)
    @DisplayName("a participant that may not see the person is handed the pseudonymous record, "
            + "and one that may, asking with a purpose, is handed the person")
    @Proving(DboPromises.PROC_A_CHANGE_TO_A_PERSON_STARTS_A_STEP_BEHIND_THE_MEMBRANE)
    void theSlotIsReadAsTheMembraneSays() throws Exception {
        String id = write("Person", "Hopper", "40612090000");
        String key = "people.person.watch/change/" + id + "/1";
        assertTrue(started("people.person.watch", id, 1), "no run started");

        Executor plain = new Executor("plain", "1", "example.clinic", Scope.BASELINE);
        Lane inProcess = declared(manager.fleetLane(CLINIC, "people.person.watch", plain)
                .orElseThrow());
        Run held = inProcess.claim(runs().byKey(key).orElseThrow(), Duration.ofMinutes(5))
                .orElseThrow();
        String pseudonymous = new String(inProcess.inputs(held).get("subject").get(0)
                .payload(), StandardCharsets.UTF_8);
        assertFalse(pseudonymous.contains("Hopper") || pseudonymous.contains("40612090000"),
                "the slot handed out the person: " + pseudonymous);
        inProcess.released(held, "handing it on", null);

        cloud.jengu.dbo.core.api.seal.ParticipantKeys keys =
                cloud.jengu.dbo.core.api.seal.ParticipantKeys.adoptOrMake(
                        Files.createTempDirectory("dbo-entitled"));
        manager.authority(CLINIC).ensureClient("entitled", "entitled-secret", List.of("work"),
                keys.sealingKey(), keys.signingKey());
        Executor entitledOne = new Executor("entitled", "1", "example.clinic", Scope.BASELINE);
        Lane entitled = declared(cloud.jengu.dbo.runner.http.HttpLane.holding(
                URI.create(base() + "/work"), () -> token("entitled", "entitled-secret"), CLINIC,
                "entitled", entitledOne, keys.sealing(), keys.signing()));
        Run taken = entitled.claim(runs().byKey(key).orElseThrow(), Duration.ofMinutes(5))
                .orElseThrow();
        StoredObject person = entitled.identified(taken, "Person/" + id, "TREAT")
                .open("entitled", keys.sealing());
        String reassembled = new String(person.payload(), StandardCharsets.UTF_8);
        assertTrue(reassembled.contains("Hopper"), "the person was not reassembled: "
                + reassembled);
    }

    @Test
    @Order(4)
    @DisplayName("a step starting on a search by what identifies a person, or by a parameter the "
            + "face does not know, is refused when the tenant is declared, naming it and never "
            + "the value")
    @Proving(DboPromises.PROC_A_CHANGE_TO_A_PERSON_STARTS_A_STEP_BEHIND_THE_MEMBRANE)
    void aSearchThatCannotBeAskedIsRefused() throws Exception {
        String byName = refusal(BY_NAME);
        assertTrue(byName.contains("people.person.byname") && byName.contains("name"), byName);
        assertFalse(byName.contains("Lovelace"), "the refusal carries the value: " + byName);
        String nonsense = refusal(NONSENSE);
        assertTrue(nonsense.contains("no-such-parameter"), nonsense);
    }

    @Test
    @Order(5)
    @DisplayName("nothing that identifies a person is in a run's key or on its trail")
    @Proving(DboPromises.PROC_A_CHANGE_TO_A_PERSON_STARTS_A_STEP_BEHIND_THE_MEMBRANE)
    void nothingOfThePersonIsInTheRun() throws Exception {
        String id = write("Practitioner", "Nightingale", "52005120000");
        assertTrue(started("people.practitioner.watch", id, 1), "no run started");
        Run run = runs().byKey("people.practitioner.watch/change/" + id + "/1").orElseThrow();
        StringBuilder said = new StringBuilder(run.toString());
        for (StoredObject entry : engine().select(Criteria.of("AuditEntry")
                .eq("run", EnvelopeValue.of(run.key())))) {
            said.append(new String(entry.payload(), StandardCharsets.UTF_8));
        }
        assertFalse(said.toString().contains("Nightingale")
                        || said.toString().contains("52005120000"),
                "the run or its trail names the person: " + said);
    }

    // ── the clinic ──

    private cloud.jengu.dbo.core.api.ObjectStore engine() {
        return manager.runtime(CLINIC).orElseThrow().engine();
    }

    private Runs runs() {
        return new Runs(engine());
    }

    private String write(String type, String family, String code) {
        return engine().put(PutRequest.create(type, body(type, family, code))).id();
    }

    private void update(String type, String id, String family, String code) {
        StoredObject current = engine().get(type, id).orElseThrow();
        engine().put(PutRequest.update(type, id, current.versionId(), body(type, family, code)));
    }

    private static byte[] body(String type, String family, String code) {
        return ("""
                {"resourceType":"%s","identifier":[{"system":"%s","value":"%s"}],
                 "name":[{"family":"%s"}]}""").formatted(type, NID, code, family)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Whether the version of a record started its run, waiting for the feed to be read. */
    private boolean started(String step, String id, long version) throws Exception {
        String key = step + "/change/" + id + "/" + version;
        long giveUp = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < giveUp) {
            if (runs().byKey(key).isPresent()) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private Lane declared(Lane lane) {
        lane.declare(new cloud.jengu.dbo.work.Declarations.Declared("people.person", "watch",
                lane.identity().name(), lane.identity().version(), lane.identity().provider(),
                lane.identity().scope(), lane.identity().name()));
        return lane;
    }

    /** Why a declared tenant is not served, as the deployment says it. */
    private String refusal(String code) throws Exception {
        long giveUp = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        String said = null;
        while (said == null && System.nanoTime() < giveUp) {
            manager.scanOnce();
            said = manager.troubles().get(code);
            Thread.sleep(250);
        }
        assertTrue(manager.runtime(code).isEmpty(), code + " was served");
        assertTrue(said != null, code + " was neither served nor said to be refused");
        return said;
    }

    private String token(String client, String secret) {
        try {
            String body = http.send(HttpRequest.newBuilder(URI.create(base() + "/oidc/token"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "grant_type=client_credentials&client_id=" + client
                                            + "&client_secret=" + secret)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = Pattern.compile("\"access_token\":\"([^\"]*)\"").matcher(body);
            assertTrue(m.find(), body);
            return m.group(1);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + manager.port() + "/t/" + CLINIC;
    }
}
