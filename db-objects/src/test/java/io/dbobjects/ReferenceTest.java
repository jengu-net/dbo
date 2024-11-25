package io.dbobjects;

import io.dbobjects.context.TestcontainersTestContext;
import io.dbobjects.fhir.types.WithUniqueId;
import io.dbobjects.storage.Reference;
import io.dbobjects.testdomain.TestDataStorage;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.dbobjects.ReferenceTest.Family.REF_TYPE_CHILD;
import static io.dbobjects.ReferenceTest.Family.REF_TYPE_FATHER;
import static io.dbobjects.ReferenceTest.Family.REF_TYPE_MOTHER;
import static io.dbobjects.context.TestcontainersTestContext.randomName;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
public class ReferenceTest {

    private static final String FAMILY_MEMBER_TYPE = FamilyMember.class.getName();

    private static TestcontainersTestContext ctx;
    private DbObjects dbo;
    private TestDataStorage<Family> familyStorage;
    private TestDataStorage<FamilyMember> familyMemberStorage;

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
        String domain = randomName("domain_");
        this.familyStorage = TestDataStorage.of(Family.class, domain);
        this.familyMemberStorage = TestDataStorage.of(FamilyMember.class, domain);
        this.dbo = ctx.createNode(randomName("app_"), randomName("node_"),
            List.of(this.familyStorage, this.familyMemberStorage));
    }

    @Test
    public void testCreateOneToOneReferenceUsingAtomicOperations() throws Exception {
        assertEquals(Family.class, familyStorage.getTypeClass());
        assertEquals(FamilyMember.class, familyMemberStorage.getTypeClass());

        // define reference types (startup)
        // during save
        // -- all managed references are re-created by the function defined during reference setup definitions
        //    (default implementation will take an object create empty list of references)

        var fatherIdRef = new AtomicReference<String>();
        runAndSync(() -> fatherIdRef.set(familyMemberStorage.put(null, new FamilyMember().setGivenName("Bob"))));
        var father = familyMemberStorage.getById(fatherIdRef.get()).orElseThrow(() -> new IllegalStateException("father with id " + fatherIdRef.get() + " must be created at this point"));
        var familyIdRef = new AtomicReference<String>();
        runAndSync(() -> familyIdRef.set(familyStorage.put(null,
            new Family().setFamilyName("Dylan").setFather(fatherIdRef.get()))));
        var family = familyStorage.getById(familyIdRef.get()).orElseThrow(() -> new IllegalStateException("family with id " + familyIdRef.get() + " must be created at this point"));
        var familyWithReferences = familyStorage.getById(family.getId(), true);
        familyWithReferences.ifPresent(f -> {
            log.info("family: {}", familyStorage.storageObjectToString(f));
            if (f.getReferencedObjects() != null) {
                f.getReferencedObjects().stream()
                    .map(familyMemberStorage::storageObjectToString)
                    .forEach(log::info);
            }
        });
    }
    private Collection<Reference> createFamilyReferences(String id, Family family) {
        log.info("creating references for family {} ({})", id, family.getFamilyName());
        var result = new ArrayList<Reference>();
        addRefIfNecessary(result, family::getFather, id, REF_TYPE_FATHER, FAMILY_MEMBER_TYPE);
        addRefIfNecessary(result, family::getMother, id, REF_TYPE_MOTHER, FAMILY_MEMBER_TYPE);
        if (family.getChildren() != null) {
            family.getChildren().forEach(child -> addRefIfNecessary(result, () -> child, id, REF_TYPE_CHILD, FAMILY_MEMBER_TYPE));
        }
        log.info("number of references: {}", result.size());
        return result;
    }

    private void addRefIfNecessary(Collection<Reference> refs, Supplier<String> idSupplier, String ownerId, String refType, String targetType) {
        var id = idSupplier == null ? null : idSupplier.get();
        if (id != null && !id.isBlank()) {
            var ref = new Reference();
            ref.setOwnerId(ownerId).setTargetId(id).setReferenceType(refType).setTargetType(targetType);
            refs.add(ref);
        }
    }

    public void runAndSync(Runnable f) {
        f.run();
        dbo.synchronize();
    }

    @Data
    public static class Family implements WithUniqueId<Family> {

        public static final String REF_TYPE_MOTHER = "mother";
        public static final String REF_TYPE_FATHER = "father";
        public static final String REF_TYPE_CHILD = "child";

        private String id;
        private Collection<String> children;
        private String familyName;
        private String mother;
        private String father;
    }

    @Data
    public static class FamilyMember implements WithUniqueId<FamilyMember> {
        private String id;
        private String givenName;
    }
}
