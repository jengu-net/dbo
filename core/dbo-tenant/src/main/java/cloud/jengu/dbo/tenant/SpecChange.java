package cloud.jengu.dbo.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a redeclared tenant is asking for, and whether it can be had while it
 * serves.
 *
 * <p>A tenant's declaration was read once, at mount, and never again: editing
 * a live tenant's spec did nothing at all — not applied, not refused, not
 * reported — and the only way to change one was to withdraw it and declare it
 * again, which drops its surfaces, its lanes and its dependents' streams to
 * alter one field. A lifecycle whose only change is a retraction is why every
 * change looked like a bring-up.
 *
 * <p>Noticing is the first half, and classifying is what makes it useful,
 * because the three answers are genuinely different work:
 *
 * <ul>
 *   <li><b>HOT</b> — the tenant absorbs it while serving. What it says about
 *       itself changes; nothing it is made of does.</li>
 *   <li><b>REWIRE</b> — surfaces or wiring have to be rebuilt, in place and
 *       additively. A tenant that has to stop serving to gain a type is a
 *       tenant nobody will edit.</li>
 *   <li><b>COLD</b> — it cannot be had while the tenant serves at all: the
 *       face it speaks, or whether its identifying data is encrypted, moves
 *       the shape of what is already stored. These are refused by name rather
 *       than half-applied, and refusing is not the same as ignoring, which is
 *       what happens today.</li>
 * </ul>
 *
 * <p>Every field of the declaration is classified here, deliberately: a field
 * nobody thought about would fall through as "nothing changed", which is the
 * silence this exists to end.
 */
public record SpecChange(Kind kind, List<String> fields) {

    public enum Kind {

        /** Absorbed while serving. */
        HOT,

        /** Rebuilt in place, additively, without a retraction. */
        REWIRE,

        /** Not while it serves. Refused by name, never half-applied. */
        COLD
    }

    public SpecChange {
        fields = List.copyOf(fields);
    }

    /** Nothing was redeclared. */
    public static final SpecChange NONE = new SpecChange(Kind.HOT, List.of());

    public boolean any() {
        return !fields.isEmpty();
    }

    /** What it would take, in the words an operator's card needs. */
    public String says() {
        return switch (kind) {
            case HOT -> "applied while it serves: " + String.join(", ", fields);
            case REWIRE -> "rebuilt in place: " + String.join(", ", fields);
            case COLD -> "cannot be applied to a serving tenant: " + String.join(", ", fields)
                    + " — it moves what is already stored, so the tenant has to be "
                    + "retracted and declared again deliberately";
        };
    }

    /**
     * What changed between the declaration a runtime was built from and the
     * one that is declared now.
     *
     * <p>The kind is the coldest of what changed: a redeclaration that adds a
     * type and changes the face is a cold change, because the half that can be
     * had does not make the half that cannot any warmer.
     */
    public static SpecChange between(TenantSpec serving, TenantSpec declared) {
        List<String> cold = new ArrayList<>();
        List<String> rewire = new ArrayList<>();
        List<String> hot = new ArrayList<>();
        // The face decides how everything stored is read, and pdi decides
        // whether identifying elements are ciphertext. Both reach behind the
        // records that already exist.
        if (!Objects.equals(serving.face(), declared.face())) {
            cold.add("face");
        }
        if (serving.pdi() != declared.pdi()) {
            cold.add("pdi");
        }
        // A zone is where identification happens and which broker performed
        // it; moving it re-parents sessions that already accumulated.
        if (!Objects.equals(serving.zone(), declared.zone())) {
            cold.add("zone");
        }
        if (!Objects.equals(serving.types(), declared.types())) {
            rewire.add("types");
        }
        if (!Objects.equals(serving.dependencies(), declared.dependencies())) {
            rewire.add("dependencies");
        }
        if (!Objects.equals(serving.scim(), declared.scim())) {
            rewire.add("scim");
        }
        if (!Objects.equals(serving.policies(), declared.policies())) {
            rewire.add("policies");
        }
        if (!Objects.equals(serving.broker(), declared.broker())
                || !Objects.equals(serving.acceptedBrokers(), declared.acceptedBrokers())) {
            rewire.add("brokers");
        }
        if (!Objects.equals(serving.managedBy(), declared.managedBy())) {
            rewire.add("managedBy");
        }
        // Mandatory steps classify incidents and gate nothing, so a tenant
        // reads the new list the moment it holds it.
        if (!Objects.equals(serving.mandatorySteps(), declared.mandatorySteps())) {
            hot.add("mandatorySteps");
        }
        if (serving.faceRoot() != declared.faceRoot()) {
            // Hot both ways. Becoming a root loads the version into a tenant
            // that keeps serving — an arrival like any profile's, rebuilding
            // the view once. Ceasing to be one changes nothing it holds:
            // the records stay, and what they were for is not the store's to
            // guess.
            hot.add("faceRoot");
        }
        if (!cold.isEmpty()) {
            return new SpecChange(Kind.COLD, join(cold, rewire, hot));
        }
        if (!rewire.isEmpty()) {
            return new SpecChange(Kind.REWIRE, join(rewire, hot));
        }
        return hot.isEmpty() ? NONE : new SpecChange(Kind.HOT, hot);
    }

    @SafeVarargs
    private static List<String> join(List<String>... parts) {
        List<String> all = new ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }
}
