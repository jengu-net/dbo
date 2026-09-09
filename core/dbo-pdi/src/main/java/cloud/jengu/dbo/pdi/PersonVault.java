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
import java.util.Map;
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
                    // Which human a record speaks about.
                    //
                    // The vault's subject is a person; a record is a statement
                    // about one. Keying the vault by the record's own id made
                    // those the same thing, so a human held as a Person and a
                    // Patient was two people with two keys — and erasing one
                    // left the other readable, while a promise said every copy
                    // died at once.
                    """
                    CREATE TABLE IF NOT EXISTS pdi.record (
                        type_name text NOT NULL,
                        record_id uuid NOT NULL,
                        person_id uuid NOT NULL,
                        PRIMARY KEY (type_name, record_id))""",
                    """
                    CREATE INDEX IF NOT EXISTS pdi_record_person
                        ON pdi.record (person_id)""",
                    // One human who arrived as two.
                    //
                    // A record written before it carried the number that
                    // identifies it gets a person of its own, and its
                    // identifying data — live AND in history, which is
                    // byte-immutable — is sealed under that person's key. When
                    // the number arrives and says who they really are, the
                    // records move to the survivor and this remembers where
                    // the earlier person went.
                    //
                    // The earlier person's ROW stays, holding its key, because
                    // history still names it as what sealed those bytes. What
                    // this buys is the erasure: shredding the survivor
                    // destroys every key of everyone they absorbed, so "gone
                    // from live store, history, envelopes and archives at
                    // once" survives a merge instead of quietly excluding one.
                    """
                    CREATE TABLE IF NOT EXISTS pdi.absorbed (
                        person_id uuid PRIMARY KEY,
                        survivor_id uuid NOT NULL)""",
                    """
                    CREATE INDEX IF NOT EXISTS pdi_absorbed_survivor
                        ON pdi.absorbed (survivor_id)""",
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

    // ------------------------------------------------------------- records

    /** The person a record speaks about, if this vault has been told. */
    public Optional<String> personOf(String typeName, String recordId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT person_id FROM pdi.record"
                             + " WHERE type_name = ? AND record_id = ?::uuid")) {
            ps.setString(1, typeName);
            ps.setString(2, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault record lookup failed", e);
        }
    }

    /**
     * Records this person is spoken about by, of one type.
     *
     * <p>The type selects which of a person's records is wanted; the person is
     * who the value identified. Asking for a Practitioner by somebody's
     * national number answers with their Practitioner record — the number
     * identifies the human, and the type says which of their records the
     * caller means.
     */
    public List<String> recordsOf(String personId, String typeName) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT record_id FROM pdi.record"
                             + " WHERE person_id = ?::uuid AND type_name = ?"
                             + " ORDER BY record_id")) {
            ps.setString(1, personId);
            ps.setString(2, typeName);
            List<String> records = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(rs.getString(1));
                }
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("vault record listing failed", e);
        }
    }

    /** Every record of this person, whatever its type — what an erasure reaches. */
    public List<String[]> recordsOf(String personId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT type_name, record_id FROM pdi.record"
                             + " WHERE person_id = ?::uuid ORDER BY type_name, record_id")) {
            ps.setString(1, personId);
            List<String[]> records = new java.util.ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("vault record listing failed", e);
        }
    }

    /** Says that this record speaks about this person; idempotent. */
    public void bind(String typeName, String recordId, String personId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO pdi.record (type_name, record_id, person_id)
                     VALUES (?, ?::uuid, ?::uuid)
                     ON CONFLICT (type_name, record_id) DO UPDATE SET person_id = excluded.person_id""")) {
            ps.setString(1, typeName);
            ps.setString(2, recordId);
            ps.setString(3, personId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("vault record binding failed", e);
        }
    }

    /**
     * Who this person turned out to be, following any merge.
     *
     * <p>Answers the person themselves when nothing absorbed them. History
     * names the person whose key sealed it, and that name never changes; this
     * is how a read gets from there to whoever they are now.
     */
    public String survivorOf(String personId) {
        try (Connection c = ds.getConnection()) {
            String current = personId;
            for (int hops = 0; hops < 16; hops++) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT survivor_id FROM pdi.absorbed WHERE person_id = ?::uuid")) {
                    ps.setString(1, current);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return current;
                        }
                        current = rs.getString(1);
                    }
                }
            }
            throw new IllegalStateException("absorption chain does not settle from " + personId);
        } catch (SQLException e) {
            throw new IllegalStateException("vault survivor lookup failed", e);
        }
    }

    /** Every person whose keys this one now answers for, itself included. */
    public List<String> absorbedInto(String survivorId) {
        List<String> all = new java.util.ArrayList<>();
        all.add(survivorId);
        try (Connection c = ds.getConnection()) {
            for (int at = 0; at < all.size(); at++) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT person_id FROM pdi.absorbed WHERE survivor_id = ?::uuid")) {
                    ps.setString(1, all.get(at));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            all.add(rs.getString(1));
                        }
                    }
                }
            }
            return List.copyOf(all);
        } catch (SQLException e) {
            throw new IllegalStateException("vault absorption listing failed", e);
        }
    }

    /**
     * One human, held as two people, becomes one — records, claims and index
     * rows move; the absorbed person keeps its key and its identity as the
     * name history seals under.
     */
    public void absorb(String absorbedId, String survivorId) {
        if (absorbedId.equals(survivorId)) {
            return;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (String move : new String[] {
                        "UPDATE pdi.record SET person_id = ?::uuid WHERE person_id = ?::uuid",
                        "UPDATE pdi.identifier SET person_id = ?::uuid WHERE person_id = ?::uuid",
                        "UPDATE pdi.lookup SET person_id = ?::uuid WHERE person_id = ?::uuid"}) {
                    try (PreparedStatement ps = c.prepareStatement(move)) {
                        ps.setString(1, survivorId);
                        ps.setString(2, absorbedId);
                        ps.executeUpdate();
                    }
                }
                // Anyone the absorbed person had themselves absorbed comes
                // along, pointing at the survivor directly. Kept flat on
                // purpose: a read resolves who somebody is now, and a chain
                // would make that a walk per row on the path this exists to
                // keep cheap.
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pdi.absorbed SET survivor_id = ?::uuid WHERE survivor_id = ?::uuid")) {
                    ps.setString(1, survivorId);
                    ps.setString(2, absorbedId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO pdi.absorbed (person_id, survivor_id) VALUES (?::uuid, ?::uuid)
                        ON CONFLICT (person_id) DO UPDATE SET survivor_id = excluded.survivor_id""")) {
                    ps.setString(1, absorbedId);
                    ps.setString(2, survivorId);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException failed) {
                c.rollback();
                throw failed;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault absorption failed", e);
        }
    }

    /** Whether this person holds any claim of their own — an identified human. */
    public boolean claimed(String personId) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM pdi.identifier WHERE person_id = ?::uuid LIMIT 1")) {
            ps.setString(1, personId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("vault claim check failed", e);
        }
    }

    /**
     * What a read needs to know about the people a page of records was sealed
     * under, in one question.
     *
     * <p>Asked per row it was three: who they are now, whether they are
     * restricted, and the key. Every one of those took a connection of its
     * own, so a page of two hundred person records paid six hundred round
     * trips to decide whether a coarse birth date survived — on the DEFAULT
     * disclosure mode, where nothing is even decrypted.
     *
     * <p>Sharing is what makes it worth batching rather than merely caching:
     * several records of one human resolve to one person, and pay once.
     */
    public Map<String, Sealed> sealedUnder(java.util.Collection<String> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Sealed> states = new java.util.HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT p.id, p.wrapped_key, COALESCE(s.id, p.id) AS survivor,
                            COALESCE(s.restricted, p.restricted) AS restricted
                       FROM pdi.person p
                       LEFT JOIN pdi.absorbed a ON a.person_id = p.id
                       LEFT JOIN pdi.person s ON s.id = a.survivor_id
                      WHERE p.id = ANY (?)""")) {
            ps.setArray(1, c.createArrayOf("uuid", personIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] wrapped = rs.getBytes(2);
                    states.put(rs.getString(1), new Sealed(rs.getString(3),
                            rs.getBoolean(4), wrapped == null ? null : unwrap(wrapped)));
                }
            }
            return Map.copyOf(states);
        } catch (SQLException e) {
            throw new IllegalStateException("vault page lookup failed", e);
        }
    }

    /**
     * A person as a read needs them: who they are now, whether they are
     * restricted, and the key that opens what they sealed — null once
     * shredded, which is the same fact as unreadable.
     */
    public record Sealed(String survivor, boolean restricted, byte[] key) {

        /** Nobody by that id: no key, and nothing claiming to be restricted. */
        public static Sealed unknown(String personId) {
            return new Sealed(personId, false, null);
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

    /**
     * §14.1: destroy the key, drop the index, remember only the fact.
     *
     * <p>Every key this human holds, which after a merge is more than one.
     * History is byte-immutable and names the person whose key sealed it, so
     * a survivor's own key opens only what was written since — leaving the
     * absorbed key alive would leave a version of the same human readable,
     * which is the promise failing in the one place nobody would look.
     */
    public Shred shred(String survivorId) {
        Shred total = null;
        for (String personId : absorbedInto(survivorId)) {
            Shred one = shredOne(personId);
            total = total == null ? one
                    : new Shred(total.known() || one.known(),
                            total.keyDestroyed() || one.keyDestroyed(),
                            total.identifiers() + one.identifiers(),
                            total.lookups() + one.lookups());
        }
        return total == null ? Shred.unknown() : total;
    }

    private Shred shredOne(String personId) {
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
