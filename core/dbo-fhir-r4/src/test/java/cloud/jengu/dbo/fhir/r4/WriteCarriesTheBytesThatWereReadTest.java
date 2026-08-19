package cloud.jengu.dbo.fhir.r4;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A write carries the array the face read it from, so the engine's envelope
 * extraction is that read rather than a fourth one
 * (REQ-DBO-VER-ONE-READ-PER-REQUEST).
 *
 * <p>Encoding the body a second time at the {@code PutRequest} would produce an
 * equal array and lose the parse silently — nothing about the stored object
 * would differ, which is why this counts instead of comparing.
 */
class WriteCarriesTheBytesThatWereReadTest {

    private static final String PATIENT = "{\"resourceType\":\"Patient\",\"active\":true}";

    /** Stands in for the engine: remembers the write and extracts nothing. */
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
            return new FeedChunk<>(List.of(), null, false);
        }

        @Override
        public int rebuildEnvelopes(String typeName) {
            return 0;
        }
    }

    private static R4Store storeInto(Capturing engine) {
        return new R4Store(engine, new R4Personality(List.of(
                new FhirTypeConfig("Patient", IdentityClass.IDENTIFIER, Set.of("urn:t"),
                        Handling.operational()))), "http://example.test");
    }

    @SuppressWarnings("unchecked")
    private static Payloads<Object> payloads() {
        return (Payloads<Object>) R4Version.face().require(Payloads.class);
    }

    @Test
    @DisplayName("what the engine is handed is what the face read")
    void theEngineIsHandedTheArrayThatWasRead() {
        Capturing engine = new Capturing();
        long before = R4Version.READS.get();

        storeInto(engine).create(PATIENT);

        assertEquals(1, R4Version.READS.get() - before, "accepting one write read it twice");
        // the same array, so the face still holds its document for it
        long read = R4Version.READS.get();
        payloads().read("Patient", engine.captured.payload());
        assertEquals(read, R4Version.READS.get(),
                "the write carried a copy of the body rather than the bytes that were read");
    }
}
