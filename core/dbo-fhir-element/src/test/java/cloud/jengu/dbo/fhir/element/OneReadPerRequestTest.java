package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.FhirVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Accepting a write reads its payload once, however many parts of the write ask
 * about it (REQ-DBO-VER-ONE-READ-PER-REQUEST).
 *
 * <p>The type, the verdict and the searchable envelope are three questions
 * about one document, and the third is asked on the far side of
 * {@code ObjectStore.put} — where the engine has bytes and no document. It is
 * the same read because the write carries the array the face read from, and the
 * face hands back the document it holds for exactly those bytes.
 *
 * <p>Counted rather than compared: the output of one read and of three is
 * identical, which is why it goes unnoticed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OneReadPerRequestTest {

    private static final String PATIENT = """
            {"resourceType":"Patient","identifier":[{"system":"urn:t","value":"1"}],
             "name":[{"family":"Aiakas"}],"gender":"female"}""";

    private final FhirVersion version = ElementFhirVersion.serving("r6");

    private FhirVersion.ForTypes declared() {
        return version.forTypes(List.of(new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER,
                Set.of("urn:t"), Handling.operational())));
    }

    @Test
    @DisplayName("a write's type, verdict and envelope come from one read")
    void aWriteReadsItsPayloadOnce() {
        Capturing engine = new Capturing();
        FhirVersion.ForTypes declared = declared();
        TypeRegistration registration = declared.registrations().get(0);
        long before = ElementPayloads.READS.get();

        declared.store(engine, "http://dbo.test").create(PATIENT);
        // what the engine does inside the transaction, with the bytes it was handed
        registration.extractor().extract("Patient", engine.captured.payload());

        assertEquals(1, ElementPayloads.READS.get() - before,
                "the write path is reading the same bytes more than once again");
    }

    @Test
    @DisplayName("and the write carries the array it was read from, not a copy of the body")
    void theWriteCarriesTheBytesThatWereRead() {
        Capturing engine = new Capturing();

        declared().store(engine, "http://dbo.test").create(PATIENT);

        long after = ElementPayloads.READS.get();
        ElementVersion.of("r6").face()
                .require(cloud.jengu.dbo.core.face.Payloads.class)
                .read("Patient", engine.captured.payload());
        assertEquals(after, ElementPayloads.READS.get(),
                "the write carried a copy of the body rather than the bytes that were read");
    }

    /** Stands in for the engine: remembers the write and stores nothing. */
    private static final class Capturing implements ObjectStore {
        PutRequest captured;

        private PutResult capture(PutRequest request) {
            captured = request;
            return new PutResult("id", 1L, true);
        }

        @Override
        public PutResult put(PutRequest request) {
            return capture(request);
        }

        @Override
        public PutResult put(PutRequest request, Handling.Authority caller) {
            return capture(request);
        }

        @Override
        public PutResult putIfAbsent(IdentityRef identity, PutRequest request) {
            return capture(request);
        }

        @Override
        public PutResult putConditional(IdentityRef identity, PutRequest request) {
            return capture(request);
        }

        @Override
        public Optional<StoredObject> get(String typeName, String id) {
            return Optional.empty();
        }

        @Override
        public List<StoredObject> getByIdentifier(String typeName, List<Identifier> identifiers) {
            return List.of();
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion) {
        }

        @Override
        public void delete(String typeName, String id, Long expectedVersion,
                Handling.Authority caller) {
        }

        @Override
        public List<StoredObject> history(String typeName, String id) {
            return List.of();
        }

        @Override
        public List<StoredObject> select(Criteria criteria) {
            return List.of();
        }

        @Override
        public long count(Criteria criteria) {
            return 0;
        }

        @Override
        public FeedChunk<StoredObject> page(Criteria criteria, String cursor) {
            return new FeedChunk<>(List.of(), null, true);
        }

        @Override
        public int rebuildEnvelopes(String typeName) {
            return 0;
        }
    }
}
