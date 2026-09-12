package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.Domains;
import cloud.jengu.dbo.core.api.TypeRegistration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which of a face's types are its definitions, and where they live.
 *
 * <p>These are the types a face publishes and a tenant takes as its version —
 * what a subscriber drains before it may serve, what a root loads from the
 * packages, what the database's own checks are compiled from. Every other type
 * a tenant declares is a record: a patient, an observation, a run.
 *
 * <p>They are a domain of their own rather than rows among the records, and
 * that follows from what is done with them rather than from what they are.
 * A face is cut once per release and handed to every tenant that comes up on
 * it, so the definitions have to be separable — hence {@link Domains}, a
 * schema, and a feed nobody's clinical traffic passes through. A subscriber to
 * a root that also holds records would otherwise read every record entry on
 * the way to the next profile.
 *
 * <p><b>Which types, stated rather than guessed.</b> A face publishes more
 * conformance resources than these, and a tenant may hold any of them. What is
 * listed here is what this store treats as its version: the set the readiness
 * criterion names, the set the expander expands, the set the search compiler
 * reads. A type outside it is a record however conformance-shaped it looks,
 * because nothing here would do anything with it.
 */
public final class FaceDefinitions {

    /**
     * The types that are a face's definitions.
     *
     * <p>The five the definition-parameter reader already treats as the
     * version's own — which is the point, one answer to "what is a
     * definition" in the place everything else asks — and the naming systems
     * beside them. Those are here because the runtime writes them itself: a
     * face's own vocabulary is registered whether or not a tenant asked for
     * it, so a naming system is never the tenant's record to begin with.
     */
    public static final Set<String> TYPES = Set.of(
            "StructureDefinition", "SearchParameter", "ValueSet", "CodeSystem", "StructureMap",
            "NamingSystem");

    private FaceDefinitions() {}

    public static boolean isDefinition(String typeName) {
        return TYPES.contains(typeName);
    }

    /** Where a type's rows belong: the definitions domain, or the face's own. */
    public static String domainOf(String typeName, String recordDomain) {
        return isDefinition(typeName) ? Domains.DEFINITIONS : recordDomain;
    }

    /**
     * The registrations with each type put in the domain it belongs to.
     *
     * <p>Applied by every personality rather than by each one's own loop, so
     * a face added later cannot quietly disagree about where a profile lives.
     */
    public static List<TypeRegistration> placed(List<TypeRegistration> registrations,
            String recordDomain) {
        List<TypeRegistration> out = new ArrayList<>(registrations.size());
        for (TypeRegistration r : registrations) {
            String belongs = domainOf(r.typeName(), recordDomain);
            out.add(belongs.equals(r.domain()) ? r : new TypeRegistration(r.typeName(), belongs,
                    r.identityClass(), r.identitySystems(), r.handling(), r.extractor(),
                    r.indexes(), r.payloadVersion()));
        }
        return List.copyOf(out);
    }

    /**
     * Refuses a set of registrations that puts a definition among the records,
     * or a record among the definitions.
     *
     * <p>Checked at bring-up rather than trusted, because nothing about a
     * misplaced type fails on its own: its rows are written, read and fed back
     * exactly as they would be from the right domain. What breaks is later and
     * elsewhere — the schema a face is cut from is missing a profile, or
     * carries a patient — and by then the image has been taken and handed out.
     */
    public static void refuseIfMisplaced(List<TypeRegistration> registrations,
            String recordDomain) {
        for (TypeRegistration r : registrations) {
            String belongs = domainOf(r.typeName(), recordDomain);
            if (!belongs.equals(r.domain())) {
                throw new IllegalArgumentException(r.typeName() + " is registered in domain '"
                        + r.domain() + "' and belongs in '" + belongs + "': "
                        + (isDefinition(r.typeName())
                                ? "a definition travels with the face and is cut into its image, "
                                        + "so it cannot live among the tenant's records"
                                : "a record is the tenant's own and must not be cut into an "
                                        + "image handed to every other tenant on this face"));
            }
        }
    }
}
