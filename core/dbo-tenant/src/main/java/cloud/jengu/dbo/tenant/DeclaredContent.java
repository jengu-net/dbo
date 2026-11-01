package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.BlobStore;
import cloud.jengu.dbo.policy.TenantPolicies;
import java.util.Map;
import java.util.Optional;

/**
 * A tenant's content, held to the kinds it declares.
 *
 * <p><b>Once a tenant declares a kind, everything it puts is of one.</b>
 * Content put under none would be kept for ever beside content that goes when
 * its window passes, and nothing could later say which was which. A tenant
 * that declares none keeps content as it always has.
 *
 * <p>Refused at the door of the store rather than at the HTTP door, so an
 * application embedding the store is held to the same declaration as one
 * putting content over the wire.
 *
 * <p>An archive put back is not new content: it restores under the kinds it
 * left with, declared here or not, rather than failing half way through a
 * restore.
 */
final class DeclaredContent implements BlobStore {

    private final String tenant;
    private final BlobStore kept;
    private final Map<String, TenantPolicies.ContentKind> kinds;

    DeclaredContent(String tenant, BlobStore kept, Map<String, TenantPolicies.ContentKind> kinds) {
        this.tenant = tenant;
        this.kept = kept;
        this.kinds = Map.copyOf(kinds);
    }

    @Override
    public String put(byte[] content, String media) {
        return put(content, media, null, null);
    }

    @Override
    public String put(byte[] content, String media, String person) {
        return put(content, media, person, null);
    }

    @Override
    public String put(byte[] content, String media, String person, String kind) {
        if (kind == null && !kinds.isEmpty()) {
            throw new IllegalArgumentException(tenant + " puts its content under one of the "
                    + "kinds it declares, and this names none: " + new java.util.TreeSet<>(
                    kinds.keySet()));
        }
        if (kind != null && !kinds.containsKey(kind)) {
            throw new IllegalArgumentException("'" + kind + "' is not a kind of content " + tenant
                    + " declares" + (kinds.isEmpty() ? "; it declares none"
                    : "; it declares " + new java.util.TreeSet<>(kinds.keySet())));
        }
        return kept.put(content, media, person, kind);
    }

    @Override
    public boolean seals() {
        return kept.seals();
    }

    @Override
    public void restore(String key, byte[] content, String media) {
        kept.restore(key, content, media);
    }

    @Override
    public void restore(String key, byte[] content, String media, String kind) {
        kept.restore(key, content, media, kind);
    }

    @Override
    public Optional<Blob> get(String key) {
        return kept.get(key);
    }

    @Override
    public boolean drop(String key) {
        return kept.drop(key);
    }

    @Override
    public long count() {
        return kept.count();
    }
}
