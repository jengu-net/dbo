package cloud.jengu.dbo.pdi;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The tenant's person vault (§14.3): per-person data keys wrapped by the
 * tenant working key, an HMAC identifier index (exact-match lookup with no
 * plaintext at rest), the restriction flag, and the shred ledger. The
 * identifying DATA never lives here — it rides encrypted inside the
 * payloads; this vault holds only what unlocks or finds it.
 *
 * <p>Shredding (§14.1) nulls the wrapped key, drops the person's index
 * rows and writes the ledger — every ciphertext copy anywhere (state,
 * history, archives) becomes garbage at once, and the person is
 * unfindable, not merely unreadable.
 */
public final class PersonVault {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataSource ds;
    private final SecretKeySpec workingKey;
    private final SecretKeySpec indexKey;

    public PersonVault(DataSource dataSource, byte[] workingKey) {
        if (workingKey == null || workingKey.length != 32) {
            throw new IllegalArgumentException("working key must be 32 bytes");
        }
        this.ds = dataSource;
        this.workingKey = new SecretKeySpec(workingKey, "AES");
        this.indexKey = new SecretKeySpec(hmac(new SecretKeySpec(workingKey, "HmacSHA256"),
                "dbo-pdi-identifier-index".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        ensureSchema();
    }

    private void ensureSchema() {
        try (Connection c = ds.getConnection()) {
            for (String ddl : new String[] {
                    "CREATE SCHEMA IF NOT EXISTS pdi",
                    """
                    CREATE TABLE IF NOT EXISTS pdi.person (
                        id uuid PRIMARY KEY,
                        wrapped_key bytea,
                        restricted boolean NOT NULL DEFAULT false,
                        shredded_at timestamptz,
                        created_at timestamptz NOT NULL DEFAULT now())""",
                    """
                    CREATE TABLE IF NOT EXISTS pdi.identifier (
                        person_id uuid NOT NULL,
                        system text NOT NULL,
                        value_hmac bytea NOT NULL,
                        PRIMARY KEY (person_id, system, value_hmac))""",
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS pdi_identifier_claim
                        ON pdi.identifier (system, value_hmac)""",
                    // Lookup WITHOUT a claim, and a table of its own because
                    // the difference is enforced in the schema rather than in
                    // code: pdi_identifier_claim above is what makes a claim
                    // race-safe, and a value several people may hold cannot
                    // live under it. Keeping them apart says which job each
                    // row is doing.
                    """
                    CREATE TABLE IF NOT EXISTS pdi.lookup (
                        person_id uuid NOT NULL,
                        system text NOT NULL,
                        value_hmac bytea NOT NULL,
                        PRIMARY KEY (person_id, system, value_hmac))""",
                    """
                    CREATE INDEX IF NOT EXISTS pdi_lookup_value
                        ON pdi.lookup (system, value_hmac)""",
                    """
                    CREATE TABLE IF NOT EXISTS pdi.shred_ledger (
                        person_id uuid PRIMARY KEY,
                        key_fingerprint text NOT NULL,
                        shredded_at timestamptz NOT NULL DEFAULT now())""",
            }) {
                try (PreparedStatement ps = c.prepareStatement(ddl)) {
                    ps.execute();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault schema setup failed", e);
        }
    }

    // ------------------------------------------------------------- keys

    /** The person's data key — created on first use; empty once shredded. */
    public Optional<byte[]> keyFor(String personId, boolean createIfAbsent) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT wrapped_key, shredded_at FROM pdi.person WHERE id = ?::uuid")) {
                ps.setString(1, personId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] wrapped = rs.getBytes(1);
                        return wrapped == null ? Optional.empty()
                                : Optional.of(unwrap(wrapped));
                    }
                }
            }
            if (!createIfAbsent) {
                return Optional.empty();
            }
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO pdi.person (id, wrapped_key) VALUES (?::uuid, ?)
                    ON CONFLICT (id) DO NOTHING""")) {
                ps.setString(1, personId);
                ps.setBytes(2, wrap(key));
                ps.executeUpdate();
            }
            // concurrent creator may have won — read back the authoritative key
            return keyFor(personId, false);
        } catch (SQLException e) {
            throw new IllegalStateException("vault key access failed", e);
        }
    }

    public boolean restricted(String personId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT restricted FROM pdi.person WHERE id = ?::uuid")) {
            ps.setString(1, personId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault lookup failed", e);
        }
    }

    /** GDPR restriction of processing (§14.5): the serving path returns pseudonymous reads. */
    public void restrict(String personId, boolean restricted) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE pdi.person SET restricted = ? WHERE id = ?::uuid")) {
            ps.setBoolean(1, restricted);
            ps.setString(2, personId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("vault restrict failed", e);
        }
    }

    // ------------------------------------------------------------- identifiers

    /** Claims identifiers for a person; a foreign claim surfaces the owner. */
    public Optional<String> claim(String personId, List<String[]> systemValues) {
        try (Connection c = ds.getConnection()) {
            for (String[] sv : systemValues) {
                Optional<String> owner = ownerOf(c, sv[0], sv[1]);
                if (owner.isPresent() && !owner.get().equals(personId)) {
                    return owner;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO pdi.identifier (person_id, system, value_hmac)
                        VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING""")) {
                    ps.setString(1, personId);
                    ps.setString(2, sv[0]);
                    ps.setBytes(3, valueHmac(sv[1]));
                    ps.executeUpdate();
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("vault claim failed", e);
        }
    }

    /**
     * Index a value for lookup WITHOUT staking a claim on it.
     *
     * <p>The identifier index does two jobs and only one is inherent to the
     * membrane. FINDING a person by a value is what replaces plaintext search
     * once the plaintext is gone. REFUSING a second person the same value is a
     * uniqueness policy, and that is a judgement about what the value means: a
     * national identity number names one human, a telephone number is shared by
     * a household.
     *
     * <p>So telecom is indexed and never claimed. Two people share a phone and
     * neither is wrong, and refusing the second would be this store deciding
     * something it has no basis to decide.
     */
    public void index(String personId, String system, String value) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO pdi.lookup (person_id, system, value_hmac)"
                             + " VALUES (?::uuid, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, personId);
            ps.setString(2, system);
            ps.setBytes(3, valueHmac(value));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("vault index failed", e);
        }
    }

    /** The system value contact points carry in the lookup index. */
    public static final String TELECOM_SYSTEM = "urn:dbo:pdi:telecom";

    /**
     * Every person claiming ANY identifier in {@code system}, id-ordered and
     * keyset-paged. The enumeration a provisioning surface needs, exposed on
     * the vault and nowhere else (REQ-DBO-SCIM-ENUMERATION-STAYS-INSIDE): it
     * never appears on the store API or any face, so "list every person in
     * this namespace" exists only for the server inside the membrane.
     */
    public List<String> claimantsIn(String system, String afterPersonId, int limit) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT person_id FROM pdi.identifier WHERE system = ?"
                             + " AND person_id > ?::uuid ORDER BY person_id LIMIT ?")) {
            ps.setString(1, system);
            ps.setString(2, afterPersonId == null
                    ? "00000000-0000-0000-0000-000000000000" : afterPersonId);
            ps.setInt(3, limit);
            List<String> ids = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("vault enumeration failed", e);
        }
    }

    /** How many persons claim in {@code system} — a list's totalResults. */
    public long claimantsCount(String system) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(DISTINCT person_id) FROM pdi.identifier WHERE system = ?")) {
            ps.setString(1, system);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault enumeration failed", e);
        }
    }

    /**
     * Every person holding this value, because an unclaimed one may be held by
     * several — and answering with one of them arbitrarily would be a wrong
     * answer wearing the shape of a right one.
     */
    public List<String> findAllByIdentifier(String system, String value) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT person_id FROM pdi.lookup WHERE system = ? AND value_hmac = ?"
                             + " ORDER BY person_id")) {
            ps.setString(1, system);
            ps.setBytes(2, valueHmac(value));
            List<String> owners = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    owners.add(rs.getString(1));
                }
            }
            return owners;
        } catch (SQLException e) {
            throw new IllegalStateException("vault lookup failed", e);
        }
    }

    public Optional<String> findByIdentifier(String system, String value) {
        try (Connection c = ds.getConnection()) {
            return ownerOf(c, system, value);
        } catch (SQLException e) {
            throw new IllegalStateException("vault lookup failed", e);
        }
    }

    private Optional<String> ownerOf(Connection c, String system, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT person_id FROM pdi.identifier WHERE system = ? AND value_hmac = ?")) {
            ps.setString(1, system);
            ps.setBytes(2, valueHmac(value));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    // ------------------------------------------------------------- shredding

    /**
     * What a shred actually did.
     *
     * <p>It returns rather than being void because the caller cannot otherwise
     * tell <b>erased</b> from <b>was never here</b>, and those are different
     * answers to a data subject: one says the key is destroyed, the other says
     * this store never held them. A surface relaying the first when it meant
     * the second would be reporting an erasure that never happened.
     *
     * @param known         whether the vault held this person at all
     * @param keyDestroyed  whether there was still a key to destroy — false on
     *                      a repeat, which is a no-op rather than an error
     * @param identifiers   identifier rows removed
     * @param lookups       lookup rows removed; a hashed address left behind
     *                      still answers "is this person here"
     */
    public record Shred(boolean known, boolean keyDestroyed, int identifiers, int lookups) {

        /** Nothing here by that id — the honest answer, and not a failure. */
        static Shred unknown() {
            return new Shred(false, false, 0, 0);
        }
    }

    /** §14.1: destroy the key, drop the index, remember only the fact. */
    public Shred shred(String personId) {
        try (Connection c = ds.getConnection()) {
            String fingerprint;
            boolean known;
            boolean keyDestroyed;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT wrapped_key FROM pdi.person WHERE id = ?::uuid")) {
                ps.setString(1, personId);
                try (ResultSet rs = ps.executeQuery()) {
                    known = rs.next();
                    byte[] wrapped = known ? rs.getBytes(1) : null;
                    keyDestroyed = wrapped != null;
                    fingerprint = wrapped == null ? "absent" : fingerprint(wrapped);
                }
            }
            if (!known) {
                // No ledger entry for somebody this store never held: the
                // ledger is a record of erasures performed, and an entry for a
                // stranger would make a restore delete rows on their behalf.
                return Shred.unknown();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pdi.person SET wrapped_key = NULL, shredded_at = now() WHERE id = ?::uuid")) {
                ps.setString(1, personId);
                ps.executeUpdate();
            }
            int identifiers;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pdi.identifier WHERE person_id = ?::uuid")) {
                ps.setString(1, personId);
                identifiers = ps.executeUpdate();
            }
            // The lookup rows go with them. A hashed address left behind after
            // an erasure still answers "is this person here", which is the
            // question Article 17 says nobody may still be able to ask.
            int lookups;
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pdi.lookup WHERE person_id = ?::uuid")) {
                ps.setString(1, personId);
                lookups = ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO pdi.shred_ledger (person_id, key_fingerprint)
                    VALUES (?::uuid, ?) ON CONFLICT (person_id) DO NOTHING""")) {
                ps.setString(1, personId);
                ps.setString(2, fingerprint);
                ps.executeUpdate();
            }
            return new Shred(true, keyDestroyed, identifiers, lookups);
        } catch (SQLException e) {
            throw new IllegalStateException("shred failed", e);
        }
    }

    /**
     * §14.4 policy replay: re-applies every ledger entry — a restore cannot
     * resurrect an erased person. Idempotent.
     */
    public int replayLedger() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE pdi.person p SET wrapped_key = NULL,
                            shredded_at = COALESCE(p.shredded_at, l.shredded_at)
                     FROM pdi.shred_ledger l
                     WHERE p.id = l.person_id AND p.wrapped_key IS NOT NULL""");
             PreparedStatement psi = c.prepareStatement("""
                     DELETE FROM pdi.identifier i USING pdi.shred_ledger l
                     WHERE i.person_id = l.person_id""")) {
            int replayed = ps.executeUpdate();
            psi.executeUpdate();
            return replayed;
        } catch (SQLException e) {
            throw new IllegalStateException("ledger replay failed", e);
        }
    }

    // ------------------------------------------------------------- crypto

    public byte[] encrypt(byte[] personKey, byte[] plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(personKey, "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plain);
            byte[] out = new byte[12 + sealed.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(sealed, 0, out, 12, sealed.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("pdi encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] personKey, byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(personKey, "AES"),
                    new GCMParameterSpec(128, sealed, 0, 12));
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("pdi decrypt failed", e);
        }
    }

    private byte[] wrap(byte[] key) {
        return sealWith(workingKey, key, Cipher.ENCRYPT_MODE);
    }

    private byte[] unwrap(byte[] wrapped) {
        return sealWith(workingKey, wrapped, Cipher.DECRYPT_MODE);
    }

    private static byte[] sealWith(SecretKeySpec kek, byte[] data, int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (mode == Cipher.ENCRYPT_MODE) {
                byte[] iv = new byte[12];
                RANDOM.nextBytes(iv);
                cipher.init(mode, kek, new GCMParameterSpec(128, iv));
                byte[] sealed = cipher.doFinal(data);
                byte[] out = new byte[12 + sealed.length];
                System.arraycopy(iv, 0, out, 0, 12);
                System.arraycopy(sealed, 0, out, 12, sealed.length);
                return out;
            }
            cipher.init(mode, kek, new GCMParameterSpec(128, data, 0, 12));
            return cipher.doFinal(data, 12, data.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("working-key operation failed — wrong key?", e);
        }
    }

    /**
     * The fingerprint of a value as this vault indexes it — hex of the same
     * HMAC the index holds, so an auditor who has the value can compute it and
     * find out whether anybody looked it up.
     */
    public String fingerprintOf(String value) {
        byte[] mac = valueHmac(value);
        StringBuilder hex = new StringBuilder(mac.length * 2);
        for (byte b : mac) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16))
                    .append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private byte[] valueHmac(String value) {
        return hmac(indexKey, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String fingerprint(byte[] wrapped) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(wrapped);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
