package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.core.face.Coarsening;
import cloud.jengu.dbo.core.face.DeclaredFace;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import cloud.jengu.dbo.fhir.common.FhirVersions;
import cloud.jengu.dbo.tenant.FaceRequirements;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import cloud.jengu.dbo.tenant.TenantSpec;
import cloud.jengu.dbo.tenant.TenantState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tenant whose face cannot serve its spec is refused at bring-up, by name
 * (#107).
 *
 * <p>The declaring half of the face contract was load-bearing; the refusing
 * half did not exist, so an absent capability surfaced where it was first
 * needed — an exception mid-request, or the PDI scar: built without the
 * face's coarsening, every GENERALISE element silently became a REMOVE while
 * the capability was published all along (#114). This proves the failure now
 * arrives at bring-up, named, and — just as load-bearing — that a tenant
 * requiring nothing unusual still comes up on the same face.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaceRefusalIT {

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;

    @BeforeAll
    void up() throws Exception {
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-facerefusal");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("FaceRefusalIT"),
                postgres.getUsername(), postgres.getPassword());
        // The real r4, wearing a face stripped of Coarsening: the smallest
        // honest way to have an incomplete face without inventing one.
        FhirVersion real = FhirVersions.installed().require("r4");
        // A real KEK, because the pdi path needs one before it ever asks the
        // coarsening question: without it the pdi tenant fails for the WRONG
        // reason and this test proves nothing about the gate — which is
        // exactly what its first falsification run showed.
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null),
                FhirVersions.of(new SansCoarsening(real)));

        // asks for coarsening (pdi) — must be refused
        Files.writeString(dir.resolve("keeldub.json"), """
                {"code":"keeldub","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        // asks for nothing unusual — must come up on the same stripped face
        Files.writeString(dir.resolve("lubatud.json"), """
                {"code":"lubatud","fhirVersion":"r4","types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        UntilServed.scan(manager, up -> up.contains("lubatud"));
    }

    @AfterAll
    void down() {
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            provisioner.close();
        }
    }

    /** Absence is only an error against a requirement. */
    @Test
    void aTenantRequiringNothingUnusualComesUpOnTheSameFace() {
        Map<String, TenantState.State> states = manager.tenantStates().stream()
                .collect(Collectors.toMap(TenantState::code, TenantState::state));
        assertEquals(TenantState.State.SERVING, states.get("lubatud"),
                "a spec that asks for nothing the face lacks is served: " + states);
    }

    /** The failure arrives at bring-up, not mid-request — and not silently. */
    @Test
    void aSpecTheFaceCannotServeIsRefusedAtBringUpNotDegradedAtRuntime() {
        Map<String, TenantState.State> states = manager.tenantStates().stream()
                .collect(Collectors.toMap(TenantState::code, TenantState::state));
        assertEquals(TenantState.State.FAILED, states.get("keeldub"),
                "pdi needs the face's coarsening, and this face has none — before the "
                        + "gate this came up and silently turned GENERALISE into REMOVE: "
                        + states);
    }

    /** The refusal names both sides: the capability, and what asked for it. */
    @Test
    void theRefusalNamesTheCapabilityAndTheRequirement() throws Exception {
        TenantSpec spec = TenantSpec.parse("""
                {"code":"nimeline","fhirVersion":"r4","pdi":true,"types":[
                  {"name":"Patient","identity":"internal","handling":"operational"}]}""");
        DomainFace stripped = new SansCoarsening(
                FhirVersions.installed().require("r4")).face();
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> FaceRequirements.refuseUnservable(spec, stripped));
        assertTrue(refusal.getMessage().contains("Coarsening"),
                "the capability, by name: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("pdi"),
                "and the part of the spec that asked for it: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains(stripped.name()),
                "and whose face fell short: " + refusal.getMessage());
    }

    /** The real r4, minus one capability, everything else delegated. */
    private static final class SansCoarsening implements FhirVersion {
        private final FhirVersion real;
        private final DomainFace face;

        SansCoarsening(FhirVersion real) {
            this.real = real;
            DeclaredFace.Builder builder = DeclaredFace.named(real.face().name() + "-sans-coarsening");
            for (Class<?> capability : real.face().capabilities()) {
                if (!Coarsening.class.equals(capability)) {
                    provide(builder, capability, real.face());
                }
            }
            this.face = builder.build();
        }

        @SuppressWarnings("unchecked")
        private static <T> void provide(DeclaredFace.Builder builder,
                Class<T> type, DomainFace from) {
            builder.providing(type, (T) from.capability(type).orElseThrow());
        }

        @Override
        public String code() {
            return real.code();
        }

        @Override
        public String domain() {
            return real.domain();
        }

        @Override
        public String payloadVersion() {
            return real.payloadVersion();
        }

        @Override
        public DomainFace face() {
            return face;
        }

        @Override
        public ForTypes forTypes(List<cloud.jengu.dbo.fhir.common.FhirTypeConfig> types) {
            return real.forTypes(types);
        }
    }
}
