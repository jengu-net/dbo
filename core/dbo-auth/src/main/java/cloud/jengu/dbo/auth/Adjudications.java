package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.identity.Adjudication;
import cloud.jengu.dbo.core.api.identity.IdentityClaim;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decisions about who somebody is, written down and found again (dbo#39).
 *
 * <p>Recording the decision is only half of it. The half that pays is asking,
 * next time the same claim turns up, whether anybody already looked at this
 * and said no — otherwise the record is an archive nobody consults and every
 * near-match is adjudicated from nothing, forever.
 */
public final class Adjudications {

    private Adjudications() {
    }

    /** Writes the decision. Append-only: a revision is a new decision, never an edit. */
    public static String record(ObjectStore store, Adjudication decision) {
        // TENANT_USERS is the default caller, which is what a receptionist is.
        // Declaring it explicitly is not possible through the interface — see
        // dbo#41 — and would be a no-op here in any case.
        return store.put(PutRequest.create("Adjudication", render(decision))).id();
    }

    /**
     * The subjects somebody has already judged <b>not</b> to be the person
     * behind these claims.
     *
     * <p>Feeds {@code Resolution.of}, which marks them rather than hiding them:
     * a past decision informs the next one and does not replace it.
     */
    public static Set<String> priorRejections(ObjectStore store, List<IdentityClaim> presented) {
        Set<String> rejected = new LinkedHashSet<>();
        for (IdentityClaim claim : presented) {
            for (StoredObject decision : store.getByIdentifier("Adjudication",
                    List.of(new Identifier(claim.system(), claim.value())))) {
                rejected.addAll(rejectedIn(new String(decision.payload(), StandardCharsets.UTF_8)));
            }
        }
        return rejected;
    }

    private static byte[] render(Adjudication decision) {
        StringBuilder json = new StringBuilder("{\"outcome\":\"")
                .append(decision.outcome()).append('"');
        if (decision.subjectId() != null) {
            json.append(",\"subjectId\":\"").append(decision.subjectId()).append('"');
        }
        if (decision.decidedBy() != null) {
            json.append(",\"decidedBy\":\"").append(decision.decidedBy()).append('"');
        }
        if (decision.decidedAt() != null) {
            json.append(",\"decidedAt\":\"").append(decision.decidedAt()).append('"');
        }
        if (decision.because() != null) {
            json.append(",\"because\":").append(Json.quote(decision.because()));
        }
        json.append(",\"rejected\":[");
        for (int i = 0; i < decision.rejected().size(); i++) {
            json.append(i > 0 ? "," : "").append('"').append(decision.rejected().get(i)).append('"');
        }
        json.append("],\"presented\":[");
        for (int i = 0; i < decision.presented().size(); i++) {
            IdentityClaim claim = decision.presented().get(i);
            json.append(i > 0 ? "," : "")
                    .append("{\"system\":").append(Json.quote(claim.system()))
                    .append(",\"value\":").append(Json.quote(claim.value()))
                    .append(",\"verification\":\"").append(claim.verification())
                    .append("\",\"status\":\"").append(claim.status()).append("\"}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> rejectedIn(String json) {
        Object node = Json.parse(json);
        return Json.strings(node, "rejected");
    }
}
