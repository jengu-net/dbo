package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.fhir.Patient;
import io.dbobjects.fhir.storage.PatientStorage;
import io.dbobjects.storage.StorageObjectIdentifier;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.dbobjects.context.TestcontainersTestContext.randomName;
import static io.dbobjects.fhir.Patient.ID_SYSTEM_ESTONIAN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class StorageObjectIdentifierTest {
    private static TestcontainersTestContext ctx;
    private DbObjects dbo;

    private PatientStorage storage;

    @BeforeAll
    public static void beforeAllTests() {
        ctx = new TestcontainersTestContext();
    }

    @AfterAll
    public static void afterAllTests() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
    }

    @BeforeEach
    public void setUpTest() {
        this.storage = new PatientStorage(randomName("domain_"));
        this.dbo = ctx.createNode(randomName("app_"), randomName("node_"), List.of(this.storage));
    }

    @Test
    public void testGetPutByIdentifier() {
        runAndSync(() -> storage.put(new Patient().setName("patient-1")));
        var idCode = "testIdCode";
        var initialName = "initialName";
        var recordIdRef = new AtomicReference<String>();
        runAndSync(() -> recordIdRef.set(storage.put(
            putIdentifier(new Patient().setName(initialName), ID_SYSTEM_ESTONIAN_ID, idCode))));
        var recordId = recordIdRef.get();
        runAndSync(() -> storage.put(
            putIdentifier(
                new Patient().setName("name3"), ID_SYSTEM_ESTONIAN_ID, "idCode3")));
        var recordById = storage.getById(recordId).get();
        log.info("recordById: {}", recordById);
        var recordByIdentifier = storage.getByIdentifier(new StorageObjectIdentifier(ID_SYSTEM_ESTONIAN_ID, idCode)).orElse(null);
        log.info("recordByIdentifier: {}", recordByIdentifier);
        assertEquals(recordById, recordByIdentifier);
        runAndSync(() -> storage.put(
            putIdentifier(recordById.setId(recordId), ID_SYSTEM_ESTONIAN_ID, "anotherIDCode")));
        Assertions.assertTrue(storage.getByIdentifier(new StorageObjectIdentifier(ID_SYSTEM_ESTONIAN_ID, idCode)).isEmpty());
        Assertions.assertTrue(storage.getById(recordId).isPresent());
    }

    private Patient putIdentifier(Patient p, String system, String identifier) {
        if (p.getIdentities() == null) {
            p.setIdentities(new ArrayList<>());
        }
        p.setIdentities(p.getIdentities().stream().filter(i -> !i.getSystem().equals(system)).collect(Collectors.toList()));
        p.getIdentities().add(new StorageObjectIdentifier(system, identifier));
        return p;
    }

    public void runAndSync(Runnable f) {
        f.run();
        dbo.synchronize();
    }

}
