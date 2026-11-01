package cloud.jengu.dbo.pdi;

import cloud.jengu.dbo.core.api.BlobStore;

import java.util.Optional;

/**
 * Content sealed to the person it is about, by the store that holds their key.
 *
 * <p><b>Why here and not above.</b> Sealed by a caller, protection is a
 * discipline every caller has to remember, and content is safe only while all
 * of them do. Sealed by the store, it is a property of the door: there is no
 * other way to put content in. That is the argument that put erasure-by-drop
 * where it is, one layer down.
 *
 * <p><b>And why not by whoever wrote it.</b> A key held outside this membrane
 * is wrapped under the tenant's key, not the person's — so destroying a
 * person's key here would leave content sealed under the outside part
 * perfectly readable, and an erasure receipt would say the key was destroyed
 * while the recording still opened. Two mechanisms, and this store's erasure
 * cannot reach the other one.
 *
 * <p><b>Nothing here reads the bytes.</b> The writer names the person; no
 * classification happens and none is possible — opaque content cannot be
 * judged to be about somebody. What the store contributes is the one thing it
 * has and a caller does not: the key, and the erasure that destroys it.
 *
 * <p>Erasure needs no step of its own. {@link PersonVault#keyFor} answers
 * empty once a person is shredded, so the content is unreadable because there
 * is no key — not because a sweep went and deleted it, which is the kind of
 * step that fails quietly.
 */
public final class SealingBlobStore implements BlobStore {

    private final BlobStore kept;
    private final PersonVault vault;
    private final java.util.Set<String> windowed;

    public SealingBlobStore(BlobStore kept, PersonVault vault) {
        this(kept, vault, java.util.Set.of());
    }

    /**
     * @param windowed the kinds whose content naming no person is sealed to its
     *                 window: under the kind's key for the day it is put
     */
    public SealingBlobStore(BlobStore kept, PersonVault vault, java.util.Set<String> windowed) {
        this.kept = kept;
        this.vault = vault;
        this.windowed = java.util.Set.copyOf(windowed);
    }

    @Override
    public boolean seals() {
        return true;
    }

    @Override
    public String put(byte[] content, String media, String person) {
        if (person == null) {
            throw new IllegalArgumentException("name the person this content is about, or "
                    + "put it without one");
        }
        return put(content, media, person, null);
    }

    @Override
    public String put(byte[] content, String media, String person, String kind) {
        if (person == null && kind != null && windowed.contains(kind)) {
            // Under the day it is put, in one calendar for every node: a key
            // per day is what lets a whole day's content go at once when its
            // window has passed for the last of it.
            java.time.LocalDate day = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
            byte[] key = vault.windowKey(kind, day, true).orElseThrow(() ->
                    new IllegalStateException("the key of '" + kind + "' for " + day
                            + " is destroyed while content is still being put under it"));
            return kept.putSealed(vault.encrypt(key, content), media, kind, day);
        }
        if (person == null) {
            return kept.put(content, media, null, kind);
        }
        if (person.isBlank()) {
            throw new IllegalArgumentException("name the person this content is about, or "
                    + "put it without one");
        }
        String whose = personOf(person);
        byte[] key = vault.keyFor(whose, true).orElseThrow(() -> new ErasedException(person));
        return kept.put(vault.encrypt(key, content), media, whose, kind);
    }

    @Override
    public String put(byte[] content, String media) {
        // Still legal, and still plain. A tenant putting content that is about
        // nobody — a form, a logo, a template — is not helped by a key, and
        // refusing it would push somebody into naming a person who is not in
        // it.
        return kept.put(content, media);
    }

    @Override
    public void restore(String key, byte[] content, String media) {
        // What an archive carries is what was stored, which for sealed content
        // is ciphertext. Putting it back is putting those bytes back; it is
        // not this decorator's business to seal them a second time.
        kept.restore(key, content, media);
    }

    @Override
    public void restore(String key, byte[] content, String media, String kind) {
        kept.restore(key, content, media, kind);
    }

    @Override
    public Optional<Blob> get(String key) {
        Optional<Blob> held = kept.get(key);
        if (held.isPresent() && held.get().window() != null) {
            Blob sealed = held.get();
            byte[] window = vault.windowKey(sealed.kind(), sealed.window(), false)
                    .orElseThrow(() -> new WindowPassedException(key));
            return Optional.of(new Blob(sealed.key(), sealed.media(),
                    vault.decrypt(window, sealed.content()), null, sealed.kind(),
                    sealed.window(), sealed.written()));
        }
        if (held.isEmpty() || held.get().person() == null) {
            return held;
        }
        Blob sealed = held.get();
        byte[] person = vault.keyFor(sealed.person(), false).orElse(null);
        if (person == null) {
            // It was here. Saying "not found" would tell an auditor this store
            // has never heard of a recording it destroyed on purpose, which is
            // a different and worse answer than the true one.
            throw new ErasedException(key);
        }
        return Optional.of(new Blob(sealed.key(), sealed.media(),
                vault.decrypt(person, sealed.content()), sealed.person(), sealed.kind(), null,
                sealed.written()));
    }

    /**
     * The person a subject names, which is not the same as the record.
     *
     * <p>A writer knows a record — {@code Person/xyz}, the thing they just
     * wrote — and the vault knows people. Taking the reference for a person id
     * would mint a key belonging to nobody: the content would seal, read back
     * perfectly, and survive the erasure of the human it is about, because the
     * key it was sealed under was never theirs. That is worse than not
     * sealing, because it looks sealed.
     *
     * <p>Resolved the way the erasure door resolves its subject, so the two
     * agree about who a reference means — which is the whole point, since one
     * destroys what the other sealed.
     */
    private String personOf(String subject) {
        int slash = subject.lastIndexOf('/');
        if (slash < 0 || slash == subject.length() - 1) {
            return subject;
        }
        return vault.personOf(subject.substring(0, slash), subject.substring(slash + 1))
                .orElseThrow(() -> new IllegalArgumentException(
                        "no person is known for " + subject + ", so content named for them "
                                + "could only be sealed under a key belonging to nobody"));
    }

    @Override
    public java.util.List<String> keysPutBy(String run) {
        return kept.keysPutBy(run);
    }

    @Override
    public java.util.List<String> keysSealedTo(String person) {
        return kept.keysSealedTo(person);
    }

    @Override
    public boolean arrive(String key, byte[] content, String media, String kind,
            java.time.Instant written) {
        Optional<Blob> here;
        try {
            here = get(key);
        } catch (WindowPassedException | ErasedException gone) {
            // Held here once, and gone on purpose: not kept again.
            return false;
        }
        if (here.isPresent()) {
            if (!java.util.Arrays.equals(here.get().content(), content)) {
                throw new IllegalStateException("a different blob is held under " + key);
            }
            return false;
        }
        if (kind == null || !windowed.contains(kind)) {
            return kept.arrive(key, content, media, kind, written);
        }
        // Under the day it was put where it was captured, so its window here
        // counts from then, as it would have had it been put here.
        java.time.LocalDate day = java.time.LocalDate.ofInstant(
                written == null ? java.time.Instant.now() : written, java.time.ZoneOffset.UTC);
        Optional<byte[]> window = vault.windowKey(kind, day, true);
        if (window.isEmpty()) {
            // That day's window has passed here already.
            return false;
        }
        return kept.arriveSealed(key, vault.encrypt(window.get(), content), media, kind, day,
                written);
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
