package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;
import cloud.jengu.dbo.core.api.identity.Resolution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolving presented claims against what the store actually holds.
 *
 * <p>{@link Resolution#of} takes the matches as a parameter, which kept the
 * confidence rules testable without a database while they were being settled.
 * It also means every caller does its own lookup — and, more to the point,
 * every caller has to remember to fold in what somebody already decided.
 * Forgetting that is silent: resolution simply offers a candidate a person
 * examined and rejected last week, as though for the first time.
 *
 * <p>So the lookup lives here, once, with the recall attached to it.
 *
 * <p>The identity type is a parameter because naming it would be knowing a
 * domain: the FHIR face keeps identities on {@code Person}, another face
 * elsewhere. Under PDI the lookup routes through the vault's index, so a claim
 * is matched without its value being disclosed to the matching.
 */
public final class Identities {

    private Identities() {
    }

    /**
     * @param identityType the type holding identity records — {@code Person}
     *                     for the FHIR face
     * @param presented    the claims somebody offered
     */
    public static Resolution resolve(ObjectStore store, String identityType,
            List<IdentityClaim> presented) {
        if (presented.isEmpty()) {
            return new Resolution(List.of());
        }
        Map<IdentityClaim, List<String>> matches = new LinkedHashMap<>();
        for (IdentityClaim claim : presented) {
            List<String> holders = store.getByIdentifier(identityType,
                            List.of(new Identifier(claim.system(), claim.value())))
                    .stream().map(StoredObject::id).toList();
            if (!holders.isEmpty()) {
                matches.put(claim, holders);
            }
        }
        return Resolution.of(presented, matches,
                Adjudications.priorRejections(store, presented));
    }
}
