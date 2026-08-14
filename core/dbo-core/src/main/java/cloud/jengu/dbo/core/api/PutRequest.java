package cloud.jengu.dbo.core.api;

import java.util.Objects;

/**
 * A write: the whole payload, never a patch. {@code id} null on create-with-
 * generated-id; {@code expectedVersion} null skips the optimistic check.
 */
public record PutRequest(String typeName, String id, Long expectedVersion, byte[] payload) {

    public PutRequest {
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(payload, "payload");
    }

    public static PutRequest create(String typeName, byte[] payload) {
        return new PutRequest(typeName, null, null, payload);
    }

    public static PutRequest update(String typeName, String id, long expectedVersion, byte[] payload) {
        return new PutRequest(typeName, id, expectedVersion, payload);
    }
}
