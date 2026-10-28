package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Answered;
import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.wire.RecordWire;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What each file of a tenant's configuration source applied, kept in the
 * tenant's own store.
 *
 * <p><b>The store's account, not the repository's.</b> A source's next apply
 * is the commit asked for against this, never against the commit applied last:
 * that commit may be gone — rewritten, or never fetched here — and what is in
 * the store is what has to be reconciled whatever happened to the history. It
 * is also how a file that stopped declaring something withdraws exactly that
 * and nothing another declarer wrote.
 *
 * <p>Authored by the store, as a run is, and riding the backup rather than an
 * export: it describes how this deployment applied the owner's files, and the
 * owner holds the files.
 */
final class ConfigFiles {

    static final String TYPE = "ConfigFile";

    /** A file is identified by the source it is in and its path there. */
    static final String SYSTEM = "urn:dbo:config-file";

    /** What one file applied: the blob it was, the type, and what it declared. */
    record Entry(String path, String blob, String type, List<Identifier> identities,
            String id, Long version) {

        Entry {
            identities = List.copyOf(identities);
        }
    }

    private final ObjectStore store;
    private final String source;

    ConfigFiles(ObjectStore store, String source) {
        this.store = store;
        this.source = source;
    }

    static List<TypeRegistration> registrations() {
        return List.of(new TypeRegistration(TYPE, cloud.jengu.dbo.work.WorkModel.DOMAIN,
                IdentityClass.IDENTIFIER, Set.of(SYSTEM), Handling.storeAuthored(),
                extractor(), List.of()));
    }

    /**
     * Every file this source has applied, by path.
     *
     * <p>Walked to its end, a page at a time: a selection stops at a page, and
     * an account read short calls files applied long ago new, never withdraws
     * one past the page, and lets a place release only the first page of it.
     */
    Map<String, Entry> applied() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try (Stream<StoredObject> ledger = Answered.pagedBy(store::page, Criteria.of(TYPE)
                .eq("source", EnvelopeValue.of(source)))) {
            ledger.map(ConfigFiles::entryOf).forEach(entry -> entries.put(entry.path(), entry));
        }
        return entries;
    }

    /** A file applied as this blob, declaring these. */
    void record(String path, String blob, String type, List<Identifier> identities) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("path", path);
        payload.put("blob", blob);
        payload.put("type", type);
        List<Object> named = new ArrayList<>();
        for (Identifier identity : identities) {
            named.add(Map.of("system", identity.system(), "value", identity.value()));
        }
        payload.put("identities", named);
        store.putConditional(IdentityRef.identifier(SYSTEM, source + "|" + path),
                PutRequest.create(TYPE, RecordWire.write(payload)
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** A file nothing applies from any more. */
    void forget(Entry entry) {
        store.delete(TYPE, entry.id(), entry.version());
    }

    private static Entry entryOf(StoredObject stored) {
        Map<?, ?> payload = (Map<?, ?>) RecordWire.read(
                new String(stored.payload(), StandardCharsets.UTF_8));
        List<Identifier> identities = new ArrayList<>();
        if (payload.get("identities") instanceof List<?> named) {
            for (Object one : named) {
                Map<?, ?> identity = (Map<?, ?>) one;
                identities.add(new Identifier(String.valueOf(identity.get("system")),
                        String.valueOf(identity.get("value"))));
            }
        }
        return new Entry(String.valueOf(payload.get("path")), String.valueOf(payload.get("blob")),
                String.valueOf(payload.get("type")), identities, stored.id(),
                stored.versionId());
    }

    private static EnvelopeExtractor extractor() {
        return (type, payload) -> {
            Map<?, ?> read = (Map<?, ?>) RecordWire.read(
                    new String(payload, StandardCharsets.UTF_8));
            String source = String.valueOf(read.get("source"));
            String path = String.valueOf(read.get("path"));
            Envelope envelope = new Envelope();
            envelope.identifier(SYSTEM, source + "|" + path);
            envelope.value("source", EnvelopeValue.of(source));
            envelope.value("path", EnvelopeValue.of(path));
            return envelope;
        };
    }
}
