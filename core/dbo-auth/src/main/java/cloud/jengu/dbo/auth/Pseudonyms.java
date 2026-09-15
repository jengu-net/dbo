package cloud.jengu.dbo.auth;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.identity.PseudonymResolution;

import java.nio.charset.StandardCharsets;

/**
 * The trail of pseudonyms turned back into people.
 *
 * <p>Separate from the bindings, though both de-anonymise, because they are
 * answerable to different questions: a binding says who a subject <em>is</em>
 * and stands until it is withdrawn, while this says somebody <em>asked</em>
 * once and changes nothing. Folding them together would make "how many times
 * was I looked up" require telling two kinds of row apart afterwards.
 */
public final class Pseudonyms {

    private Pseudonyms() {
    }

    /**
     * Records that the question was asked, and what it reached.
     *
     * <p>Reading them back needs nothing of its own. They are records of a
     * registered type in the tenant's own store, indexed by the person they
     * reached, so "who has turned a pseudonym of mine back into me" is the
     * ordinary surface asked an ordinary question — which is what audit being
     * records rather than a log beside them is for.
     */
    public static String record(ObjectStore store, PseudonymResolution resolution) {
        return store.put(PutRequest.create("PseudonymResolution", render(resolution))).id();
    }

    private static byte[] render(PseudonymResolution resolution) {
        StringBuilder json = new StringBuilder("{\"scope\":")
                .append(Json.quote(resolution.scope()))
                .append(",\"found\":").append(resolution.found());
        if (resolution.found()) {
            json.append(",\"personId\":").append(Json.quote(resolution.personId()));
        }
        json.append(",\"actor\":").append(Json.quote(resolution.actor()))
                .append(",\"at\":\"").append(resolution.at())
                .append("\",\"purpose\":").append(Json.quote(resolution.purpose()));
        if (resolution.because() != null) {
            json.append(",\"because\":").append(Json.quote(resolution.because()));
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
