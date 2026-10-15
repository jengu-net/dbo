package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.wire.RecordWire;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A tenant's working key: the one key everything it keeps secret is wrapped
 * under — its signing keys, its secrets, its people's keys — kept in the
 * tenant's own database and only ever wrapped, by the key its declaration
 * names.
 *
 * <p><b>Made once, moved never.</b> What wraps it may change — the deployment's
 * key, a Transit key, a key the owner holds — and then the working key is
 * wrapped again under the new one and nothing it wraps is touched. A tenant
 * that ran before there was a working key had its keys wrapped by the
 * deployment's own; they are moved under the working key once, and until
 * that has finished they are opened under either.
 *
 * <p>The index key the people's identifiers are looked up by is kept beside
 * it, wrapped by it: an index cannot be rebuilt from what it indexes, so a
 * tenant that ran before keeps the one it had.
 */
final class WorkingKeys {

    static final String TYPE = "WorkingKey";

    private static final Identifier CURRENT = new Identifier(
            cloud.jengu.dbo.auth.IdentityModel.WORKING_KEY_SYSTEM, "current");
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The working key, its index key, and whether what it wraps is still
     * being moved from under the deployment's key.
     */
    record Held(byte[] key, byte[] index, boolean moving) {
    }

    private WorkingKeys() {
    }

    /**
     * The tenant's working key, unwrapped — made if it has none, and wrapped
     * again where its declaration now names another key or the key it names
     * has a newer version.
     *
     * @param wrapper   what its declaration names now
     * @param declared  the declaration's own words for it, kept so the key
     *                  that wrapped it can be reached again after the
     *                  declaration changes; null for the deployment's
     * @param kek       the deployment's key, which wrapped everything before
     * @param wrapperOf reaches a key from the words it was declared in
     * @throws RuntimeException when the key that wraps it will not unwrap it
     */
    static Held resolve(ObjectStore identities, KeyWrapper wrapper,
            TenantSpec.WorkingKey declared, byte[] kek,
            Function<TenantSpec.WorkingKey, KeyWrapper> wrapperOf) {
        Optional<StoredObject> stored = current(identities);
        if (stored.isEmpty()) {
            // A tenant that ran before signed with a key the deployment's key
            // wrapped, and indexed its people under the index that key gave.
            boolean ran = !identities.select(cloud.jengu.dbo.core.api.Criteria.of("SigningKey"))
                    .isEmpty();
            byte[] key = random();
            byte[] index = ran ? cloud.jengu.dbo.pdi.PersonVault.indexKeyOf(kek) : random();
            write(identities, null, wrapper.describe(), declared, wrapper.wrap(key), key, index,
                    ran);
            return new Held(key, index, ran);
        }
        Map<?, ?> record = read(stored.get());
        byte[] wrapped = Base64.getDecoder().decode(String.valueOf(record.get("wrapped")));
        boolean same = wrapper.describe().equals(record.get("wrapper"));
        KeyWrapper was = same ? wrapper : wrapperOf.apply(declaredOf(record));
        byte[] key = was.unwrap(wrapped);
        byte[] index = new cloud.jengu.dbo.auth.KeyProtector(key).unwrap(
                Base64.getDecoder().decode(String.valueOf(record.get("index"))));
        boolean moving = Boolean.TRUE.equals(record.get("moving"));
        byte[] now = same ? wrapper.rewrapped(wrapped) : wrapper.wrap(key);
        if (!same || !Arrays.equals(now, wrapped)) {
            write(identities, stored.get(), wrapper.describe(), declared, now, key, index, moving);
        }
        return new Held(key, index, moving);
    }

    /** What it wraps has all been moved under it: the deployment's key opens nothing more. */
    static void moved(ObjectStore identities) {
        StoredObject stored = current(identities).orElseThrow();
        Map<Object, Object> record = new LinkedHashMap<>(read(stored));
        record.put("moving", Boolean.FALSE);
        identities.put(PutRequest.update(TYPE, stored.id(), stored.versionId(),
                RecordWire.write(record).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Unwrapped again, to learn whether it still can be: what a key held
     * elsewhere is asked on a timer.
     *
     * @throws RuntimeException when it will not unwrap
     */
    static void check(ObjectStore identities, KeyWrapper wrapper) {
        Map<?, ?> record = read(current(identities).orElseThrow(
                () -> new IllegalStateException("the tenant has no working key")));
        wrapper.unwrap(Base64.getDecoder().decode(String.valueOf(record.get("wrapped"))));
    }

    private static Optional<StoredObject> current(ObjectStore identities) {
        return identities.getByIdentifier(TYPE, List.of(CURRENT)).stream().findFirst();
    }

    private static Map<?, ?> read(StoredObject stored) {
        return (Map<?, ?>) RecordWire.read(new String(stored.payload(), StandardCharsets.UTF_8));
    }

    private static void write(ObjectStore identities, StoredObject was, String wrapper,
            TenantSpec.WorkingKey declared, byte[] wrapped, byte[] key, byte[] index,
            boolean moving) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("wrapper", wrapper);
        if (declared != null) {
            Map<String, Object> words = new LinkedHashMap<>();
            words.put("key", declared.key());
            words.put("vault", declared.vault());
            words.put("mount", declared.mount());
            words.put("authMount", declared.authMount());
            words.put("role", declared.role());
            words.values().removeIf(java.util.Objects::isNull);
            record.put("declared", words);
        }
        record.put("wrapped", Base64.getEncoder().encodeToString(wrapped));
        record.put("index", Base64.getEncoder().encodeToString(
                new cloud.jengu.dbo.auth.KeyProtector(key).wrap(index)));
        record.put("moving", moving);
        byte[] payload = RecordWire.write(record).getBytes(StandardCharsets.UTF_8);
        if (was == null) {
            identities.putConditional(IdentityRef.identifier(CURRENT.system(), CURRENT.value()),
                    PutRequest.create(TYPE, payload));
        } else {
            identities.put(PutRequest.update(TYPE, was.id(), was.versionId(), payload));
        }
    }

    private static TenantSpec.WorkingKey declaredOf(Map<?, ?> record) {
        if (!(record.get("declared") instanceof Map<?, ?> words)) {
            return null;
        }
        return new TenantSpec.WorkingKey(str(words, "key"), str(words, "vault"),
                str(words, "mount"), str(words, "authMount"), str(words, "role"));
    }

    private static String str(Map<?, ?> words, String name) {
        Object value = words.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static byte[] random() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }
}
