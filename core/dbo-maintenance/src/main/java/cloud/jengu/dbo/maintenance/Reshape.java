package cloud.jengu.dbo.maintenance;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.face.ShapeConversion;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converting stored objects to a target shape major, in place (§2,
 * [shape versioning]).
 *
 * <p>The loop is the engine's and the transformation is the face's, and the
 * split is the point: paging, the rate bound, the cursor, the ordinary
 * versioned write and the accounting live here, once, for every model —
 * while whether a payload CAN be converted is a question only a face can
 * answer ({@link ShapeConversion}). A consumer that ran this loop over the
 * API would rebuild all of it, per runner, outside the store that owns
 * history and identity.
 *
 * <p><b>Every rewrite is an ordinary write.</b> History therefore keeps the
 * pre-conversion object with its own stamp, the new version carries the new
 * one, and a concurrent writer is guarded by the same version check as any
 * other update. Nothing here is a special path into the engine — which is
 * why a migrated object and an ordinarily-updated one are indistinguishable
 * in shape, and distinguishable in the trail.
 *
 * <p><b>One object nobody can convert does not strand the rest.</b> It is
 * named in the result and left exactly as it was; the next run finds it
 * still behind and tries again. A loop that stopped at the first refusal
 * would leave a hospital's data half-converted with no way to say which
 * half.
 */
public final class Reshape {

    private Reshape() {
    }

    /**
     * Writing a converted object back through the face's accept path.
     *
     * <p>Not {@code ObjectStore.put}, and the difference is the whole
     * correctness of a run: the engine's write stores bytes, while ACCEPT
     * validates them against the pack and re-stamps them from it. A loop
     * that wrote straight to the engine would leave every converted object
     * carrying the stamp it had before — converted data claiming to be old,
     * which is worse than unconverted data telling the truth.
     *
     * <p>Declared here as a function so this loop stays ignorant of what a
     * face's accept path looks like; the caller, which already knows its
     * face, supplies it.
     */
    @FunctionalInterface
    public interface Accept {

        /** @throws RuntimeException when the converted form is refused */
        void reaccept(String typeName, String id, long expectedVersion, byte[] payload);
    }

    /**
     * What one run did.
     *
     * @param converted how many objects were rewritten
     * @param refused   the objects no converter covered, each with why —
     *                  reported rather than thrown, because they are the
     *                  ordinary outcome of a partial converter set
     * @param cursor    where to resume, or null when the walk reached the end
     *                  of what was behind when it started
     */
    public record Run(int converted, List<Refusal> refused, String cursor) {

        public Run {
            refused = List.copyOf(refused);
        }

        /**
         * Whether the target is reached: the walk ended AND nothing was left
         * behind. Deliberately not the same question as "did the walk
         * finish" — a page of pure refusals ends the walk while the data is
         * still old, and a caller that read the cursor alone would report
         * done about it.
         */
        public boolean complete() {
            return cursor == null && refused.isEmpty();
        }
    }

    /** One object the run could not convert, and the reason it could not. */
    public record Refusal(String id, String profile, String reason) {}

    /** A claim as a runner reads it: bytes base64, because the store holds bytes. */
    public static String json(Claim claim) {
        StringBuilder out = new StringBuilder("{\"cursor\":")
                .append(claim.cursor() == null ? "null" : Names.quote(claim.cursor()))
                .append(",\"held\":[");
        for (int i = 0; i < claim.held().size(); i++) {
            Held one = claim.held().get(i);
            out.append(i > 0 ? "," : "")
                    .append("{\"id\":").append(Names.quote(one.id()))
                    .append(",\"version\":").append(one.version())
                    .append(",\"payload\":").append(Names.quote(
                            java.util.Base64.getEncoder().encodeToString(one.payload())))
                    .append('}');
        }
        return out.append("]}").toString();
    }

    /**
     * A run as an operator reads it — rendered here, beside the record,
     * because the quoting belongs where the other maintenance reports keep
     * it rather than being re-spelled by every surface.
     */
    public static String json(Run run) {
        StringBuilder out = new StringBuilder("{\"converted\":").append(run.converted())
                .append(",\"complete\":").append(run.complete())
                .append(",\"cursor\":")
                .append(run.cursor() == null ? "null" : Names.quote(run.cursor()))
                .append(",\"refused\":[");
        for (int i = 0; i < run.refused().size(); i++) {
            Refusal refusal = run.refused().get(i);
            out.append(i > 0 ? "," : "")
                    .append("{\"id\":").append(Names.quote(refusal.id()))
                    .append(",\"profile\":").append(Names.quote(refusal.profile()))
                    .append(",\"reason\":").append(Names.quote(refusal.reason()))
                    .append('}');
        }
        return out.append("]}").toString();
    }

    /** One object held out for conversion elsewhere, with the version it was read at. */
    public record Held(String id, long version, byte[] payload) {}

    /** A page of stock handed out, and where to ask for the next one. */
    public record Claim(List<Held> held, String cursor) {

        public Claim {
            held = List.copyOf(held);
        }
    }

    /**
     * A page of stock behind the bound, for a converter that is not this
     * store's (REQ-DBO-SHAPE-HANDBACK-CLAIMS-WITHOUT-LOCKING).
     *
     * <p><b>Nothing is held and nothing is written.</b> A claim is a query:
     * the guard is the version check when a converted form comes back, not a
     * lock taken here. That is what makes an abandoned claim harmless —
     * there is nothing to expire, nothing to strand, and the next claim
     * simply returns the same stock. Two consumers claiming the same page is
     * duplicated work, never corruption: one write wins the version check
     * and the other is refused by name, after which the object is no longer
     * behind the bound.
     *
     * <p>A lease would buy only duplicate-work avoidance, and would pay for
     * it with persistent state that can leak and expire wrongly — which is
     * the failure it would exist to prevent.
     */
    public static Claim claim(ObjectStore store, String typeName, String profile,
            int targetMajor, int pageSize, String cursor) {
        var chunk = store.page(Criteria.of(typeName)
                .shapeBelow(profile, targetMajor)
                .limit(pageSize), cursor);
        List<Held> held = new ArrayList<>();
        for (StoredObject stored : chunk.items()) {
            held.add(new Held(stored.id(), stored.versionId(), stored.payload()));
        }
        return new Claim(held, chunk.drained() ? null : chunk.nextCursor());
    }

    /**
     * Converted forms handed back, re-accepted through the face
     * (REQ-DBO-SHAPE-HANDBACK-KEEPS-THE-DISCIPLINE).
     *
     * <p>The same accounting as the in-process lane, deliberately: an
     * escape hatch that bypassed the loop would run the HARDEST conversions
     * with the LEAST discipline, which is backwards. So a handed-back form
     * is validated by the pack, re-stamped by it, and version-checked
     * against what was claimed — a form built from stock that has since
     * moved is refused by name rather than overwriting the newer version.
     */
    public static Run apply(Accept accept, String typeName, String profile,
            List<Held> converted) {
        int applied = 0;
        List<Refusal> refused = new ArrayList<>();
        for (Held one : converted) {
            try {
                accept.reaccept(typeName, one.id(), one.version(), one.payload());
                applied++;
            } catch (RuntimeException rejected) {
                refused.add(new Refusal(one.id(), profile,
                        "the store refused the converted form: " + rejected.getMessage()));
            }
        }
        // No cursor: the caller decided what this batch was, so there is no
        // walk position to resume — asking again is a fresh claim.
        return new Run(applied, refused, null);
    }

    /**
     * Converts objects of {@code typeName} stamped below {@code targetMajor}
     * for {@code profile}, up to {@code maxPages} pages of {@code pageSize}.
     *
     * <p>The bound is the whole selection rule: an object at or above the
     * target is not behind, and an unstamped object is not claimed to be
     * either — the walk converts what is demonstrably old, never what is
     * merely unlabelled.
     *
     * @param cursor where a previous run left off, or null to start
     */
    public static Run run(ObjectStore store, ShapeConversion conversion, Accept accept,
            String typeName, String profile, int targetMajor, int pageSize, int maxPages,
            String cursor) {
        int converted = 0;
        List<Refusal> refused = new ArrayList<>();
        String at = cursor;
        for (int page = 0; page < maxPages; page++) {
            var chunk = store.page(Criteria.of(typeName)
                    .shapeBelow(profile, targetMajor)
                    .limit(pageSize), at);
            for (StoredObject stored : chunk.items()) {
                if (convertOne(accept, conversion, typeName, profile, targetMajor,
                        stored, refused)) {
                    converted++;
                }
            }
            at = chunk.nextCursor();
            if (chunk.drained() || at == null) {
                return new Run(converted, refused, null);
            }
        }
        return new Run(converted, refused, at);
    }

    /** @return whether this object was rewritten; refusals are collected, never thrown */
    private static boolean convertOne(Accept accept, ShapeConversion conversion,
            String typeName, String profile, int targetMajor, StoredObject stored,
            List<Refusal> refused) {
        Optional<byte[]> converted;
        try {
            converted = conversion.convert(typeName, stored.payload(), profile, targetMajor);
        } catch (RuntimeException failed) {
            refused.add(new Refusal(stored.id(), profile, String.valueOf(failed.getMessage())));
            return false;
        }
        if (converted.isEmpty()) {
            refused.add(new Refusal(stored.id(), profile,
                    "no converter covers this hop to major " + targetMajor));
            return false;
        }
        try {
            // Through ACCEPT, and version-checked: the pack re-validates the
            // converted form and re-stamps it, which is what makes the new
            // stamp true rather than asserted here, and a concurrent writer
            // loses the race rather than being overwritten.
            accept.reaccept(typeName, stored.id(), stored.versionId(), converted.get());
            return true;
        } catch (RuntimeException rejected) {
            refused.add(new Refusal(stored.id(), profile,
                    "the store refused the converted form: " + rejected.getMessage()));
            return false;
        }
    }
}
