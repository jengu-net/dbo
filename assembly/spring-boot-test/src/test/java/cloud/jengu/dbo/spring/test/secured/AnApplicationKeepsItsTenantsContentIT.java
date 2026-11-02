package cloud.jengu.dbo.spring.test.secured;

import cloud.jengu.dbo.core.api.BlobStore;
import cloud.jengu.dbo.core.api.Caller;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Disclosure;
import cloud.jengu.dbo.promises.DboPromises;
import cloud.jengu.dbo.promises.Proving;
import cloud.jengu.dbo.spring.server.DboTenants;
import cloud.jengu.dbo.spring.test.DboSpringBootTest;
import cloud.jengu.dbo.spring.test.DboTestContext;
import cloud.jengu.promise.proving.Proves;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application keeping and reading a tenant's content without leaving its
 * process.
 *
 * <p><b>Here rather than on the sample world</b>, because no tenant there
 * declares kinds of content, and declaring them on one for this test would
 * change what every story's tenant keeps.
 */
@DboSpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnApplicationKeepsItsTenantsContentIT {

    private static final String DESK = "desk";
    private static final String READER = "Practitioner/a-desk-reader";

    @Autowired
    DboTestContext dbo;

    @Autowired
    DboTenants tenants;

    @BeforeAll
    void theDeskKeepsScansAndForms() {
        dbo.declare(DESK, """
                {"code":"desk","face":"r4","audit":{"level":"none"},
                 "content":{"scan":{"keepAtPlace":"P1D","keepAtTenant":"P30D","purpose":"required"},
                            "form":{"keepAtPlace":"P1D","keepAtTenant":"P30D"}},
                 "types":[{"name":"Organization","identity":"internal",
                           "handling":"operational"}]}""");
        assertTrue(dbo.until(DESK, true, Duration.ofMinutes(5)), "the desk never served");
    }

    @AfterEach
    void forgetWhoAsked() {
        Caller.clear();
        Disclosure.clear();
    }

    @Test
    @DisplayName("a put through the application's handle is held to the tenant's kinds: one "
            + "naming none, or one it does not declare, is refused naming the kinds there are")
    @Proving(DboPromises.POL_AN_APPLICATION_KEEPS_ITS_TENANTS_CONTENT_IN_PROCESS)
    void aPutIsHeldToTheDeclaredKinds() {
        BlobStore content = tenants.content(DESK).orElseThrow(() -> new AssertionError(
                "a serving tenant's content is not offered to the application"));
        byte[] form = "a form".getBytes(StandardCharsets.UTF_8);
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> content.put(form, "text/plain"));
        IllegalArgumentException undeclared = assertThrows(IllegalArgumentException.class,
                () -> content.put(form, "text/plain", null, "letter"));
        Proves.that(DboPromises.POL_AN_APPLICATION_KEEPS_ITS_TENANTS_CONTENT_IN_PROCESS,
                none.getMessage().contains("[form, scan]")
                        && undeclared.getMessage().contains("'letter'")
                        && undeclared.getMessage().contains("[form, scan]"),
                "a refused put does not name the kinds there are: " + none.getMessage()
                        + " / " + undeclared.getMessage());
        String key = content.put(form, "text/plain", null, "form");
        assertArrayEquals(form, content.get(key).orElseThrow().content());
    }

    @Test
    @DisplayName("a scan read without a purpose is refused before anything is recorded, and "
            + "read for one it is an access entry naming the reader, the kind and the purpose")
    @Proving(DboPromises.POL_AN_APPLICATION_KEEPS_ITS_TENANTS_CONTENT_IN_PROCESS)
    void aReadIsAskedWhyAndRecorded() {
        BlobStore content = tenants.content(DESK).orElseThrow();
        byte[] scan = "a scan".getBytes(StandardCharsets.UTF_8);
        String key = content.put(scan, "image/png", null, "scan");

        Caller.set(READER);
        assertThrows(BlobStore.PurposeRequiredException.class, () -> content.get(key));
        assertEquals(List.of(), accessesOf(key), "a refused read is on the trail as one");

        Disclosure.set(Disclosure.Mode.OMIT, "TREAT");
        assertArrayEquals(scan, content.get(key).orElseThrow().content());
        List<String> accesses = accessesOf(key);
        Proves.that(DboPromises.POL_AN_APPLICATION_KEEPS_ITS_TENANTS_CONTENT_IN_PROCESS,
                accesses.size() == 1 && accesses.get(0).contains(READER)
                        && accesses.get(0).contains("\"scan\"")
                        && accesses.get(0).contains("TREAT"),
                "the read is not one access entry naming the reader, the kind and the "
                        + "purpose: " + accesses);
    }

    private List<String> accessesOf(String key) {
        return tenants.store(DESK).orElseThrow().select(Criteria.of("AuditEntry")).stream()
                .map(entry -> new String(entry.payload(), StandardCharsets.UTF_8))
                .filter(entry -> entry.contains("\"access\"") && entry.contains(key))
                .toList();
    }
}
