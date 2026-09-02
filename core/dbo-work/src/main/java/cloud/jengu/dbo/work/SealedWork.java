package cloud.jengu.dbo.work;

import java.util.List;

/**
 * Work as it leaves a tenant: a manifest anybody carrying it may read, and
 * the documents it names sealed to the participants meant to open them.
 *
 * <p>A sealed copy is a copy in flight and not the record. The store keeps
 * the original, still indexes, searches and shreds it, and the copy is
 * bounded by the work that caused it.
 */
public record SealedWork(Manifest manifest, List<SealedPayload> payload) {

    public SealedWork {
        payload = payload == null ? List.of() : List.copyOf(payload);
    }
}
