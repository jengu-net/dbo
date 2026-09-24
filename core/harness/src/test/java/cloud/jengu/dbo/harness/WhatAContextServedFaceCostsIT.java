package cloud.jengu.dbo.harness;

import cloud.jengu.dbo.fhir.element.ElementVersion;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;
import cloud.jengu.dbo.fhir.index.DefinitionRows;
import cloud.jengu.dbo.tenant.LocalDatabasePerTenantProvisioner;
import cloud.jengu.dbo.tenant.TenantRuntimeManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control for {@link WhatTheIndexFaceCostsIT}: the same tenant, the same
 * face, the same runtime, and the dial OFF.
 *
 * <p>Without it the other class's numbers attribute nothing. It reports zero
 * worker contexts built, and the reason could as easily be that this task
 * brings tenants up from a face IMAGE — rows somebody already expanded, which
 * needs no toolchain — as that the face reads the index. One of those is item
 * 025's case and the other is item 024's, and a measurement that cannot tell
 * them apart is not evidence for either.
 *
 * <p>So this is the same class with one property unset. Its own process,
 * because {@code forkEvery = 1} gives each class one: a context is built once
 * per version per process and never shrinks, so two of these in one JVM would
 * measure each other.
 *
 * <p>Item 025's whole case is memory, and after ten of its twelve moves not
 * one megabyte has moved. The dial that chooses the index face exists so that the
 * two can be measured against each other on one tenant, the way the carried
 * definitions were when they were offered by name. This takes that
 * measurement.
 *
 * <p><b>The decisive question is not how small the index is.</b> That was
 * measured at step 1 and the answer was 31× on one closure. It is whether a
 * tenant whose face reads the index still BUILDS a worker context — because a
 * context is built once per version per process and never shrinks, so one
 * built at bring-up is paid for by every tenant afterwards however they are
 * configured. {@code ElementVersion.contextBuilds()} counts them, so this is
 * an observable rather than an argument.
 *
 * <p><b>What is measured, and what it is not.</b> Heap in use after a forced
 * collection: what the JVM still holds when asked to let go of what it can.
 * Not retained size, not resident set. Reported rather than asserted tightly,
 * because allocation wanders between runs and a threshold that fights the
 * collector fails on Tuesdays.
 *
 * <p><b>A world of its own, for two reasons that each suffice.</b> The number
 * reported is what this runtime holds, so it means nothing on a runtime other
 * classes have used. And the dial is global, so a shared tenant whose payloads
 * were first built inside this window would keep the index face for the rest
 * of the run.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatAContextServedFaceCostsIT {

    private static final String DIAL = "dbo.payloads.index";
    private static final String TENANT = "kontekstihind";
    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";
    private static final Path REPORT = Path.of("build", "context-face-cost.txt");

    static PostgreSQLContainer<?> postgres;
    static Path dir;
    static LocalDatabasePerTenantProvisioner provisioner;
    static TenantRuntimeManager manager;
    static String restore;

    @BeforeAll
    void up() throws Exception {
        restore = System.getProperty(DIAL);
        System.clearProperty(DIAL);
        postgres = SharedPostgres.get();
        dir = Files.createTempDirectory("dbo-index-cost");
        provisioner = new LocalDatabasePerTenantProvisioner(
                SharedPostgres.urlFor("WhatAContextServedFaceCostsIT"),
                postgres.getUsername(), postgres.getPassword());
        byte[] kek = new byte[32];
        new java.security.SecureRandom().nextBytes(kek);
        manager = new TenantRuntimeManager(dir, provisioner, "127.0.0.1", 0, null,
                new TenantRuntimeManager.AuthorityConfig(kek, null));
    }

    @AfterAll
    void down() {
        if (restore == null) {
            System.clearProperty(DIAL);
        } else {
            System.setProperty(DIAL, restore);
        }
        if (manager != null) {
            manager.close();
        }
        if (provisioner != null) {
            SuiteDatabases.retire(provisioner);
        }
    }

    @Test
    @DisplayName("what the same tenant holds with the dial off, and how many worker contexts "
            + "were built to get there")
    void whatTheIndexFaceCosts() throws Exception {
        long emptyRuntime = heapInUse();
        long buildsBefore = ElementVersion.contextBuilds();

        // A face root, because the index judges what the tenant holds as rows
        // and a root is what loads a version and expands it.
        Files.writeString(dir.resolve(TENANT + ".json"), """
                {"code":"%s","face":"r4","faceRoot":true,"audit":{"level":"none"},
                 "types":[
                  {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                  {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                  {"name":"ValueSet","identity":"canonical","handling":"operational"},
                  {"name":"CodeSystem","identity":"canonical","handling":"operational"},
                  {"name":"Patient","identity":"internal","handling":"operational"},
                  {"name":"Observation","identity":"internal","handling":"operational"}]}"""
                .formatted(TENANT));
        UntilServed.scan(manager, TENANT);

        // A write, so that the payloads and the extractor are both built:
        // both are lazy, and a tenant that has served nothing has asked the
        // index for nothing.
        manager.runtime(TENANT).orElseThrow().store().create(
                "{\"resourceType\":\"Patient\",\"gender\":\"female\"}");

        long served = heapInUse();
        long buildsAfter = ElementVersion.contextBuilds();

        // The index on its own, beside what the tenant already holds: built a
        // second time here so its cost is a delta rather than a share of a
        // number nobody can take apart.
        long beforeIndex = heapInUse();
        Set<String> seeds = new LinkedHashSet<>();
        for (String type : new String[] {"StructureDefinition", "SearchParameter", "ValueSet",
                "CodeSystem", "Patient", "Observation"}) {
            seeds.add(PREFIX + type);
        }
        DefinitionIndex index = DefinitionRows.over(source(),
                DefinitionRows.closureOf(source(), seeds));
        BoundCodes codes = BoundCodes.over(source(), index);
        long afterIndex = heapInUse();
        // Held across the reading, or the collector is measuring nothing.
        assertTrue(index.elements() > 0 && codes.codes() >= 0, "the index went away");

        String said = """
                === the control: the same tenant, the dial OFF ===
                empty runtime                      %d MB
                after serving and one write        %d MB
                the tenant costs                   %d MB
                worker contexts built on the way   %d
                the index and its codes cost       %d MB (%d elements, %d structures, %d codes)
                recorded for a context-served tenant: 226 MB
                """.formatted(mb(emptyRuntime), mb(served), mb(served - emptyRuntime),
                        buildsAfter - buildsBefore, mb(afterIndex - beforeIndex),
                        index.elements(), index.structures(), codes.codes());
        System.out.println(said);
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, said);

        // The measurement has to have measured something.
        assertTrue(served > emptyRuntime,
                "serving a face root cost nothing, so this is measuring the wrong runtime");
        assertTrue(index.elements() > 500,
                "the index is too small to be this tenant's closure: " + index.elements());
    }

    /**
     * What the JVM still holds when asked to let go of what it can.
     *
     * <p>Three times, with a pause: one collection frees what is unreachable
     * and the next collects what that made unreachable — soft references among
     * them, which is how the validator pool is held.
     */
    private static long heapInUse() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static long mb(long bytes) {
        return Math.max(0, bytes) / (1024 * 1024);
    }

    private static PGSimpleDataSource source() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(SharedPostgres.urlFor("x")
                .replaceAll("/[^/?]+(\\?.*)?$", "/tenant_" + TENANT.replace('-', '_')));
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }
}
