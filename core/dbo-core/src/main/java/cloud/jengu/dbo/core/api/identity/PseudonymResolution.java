package cloud.jengu.dbo.core.api.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * Somebody asked which person a pseudonym belongs to, and what came back.
 *
 * <p><b>The pseudonym itself is not here, and its absence is the design.</b> A
 * trail entry pairing a pseudonym with the person it resolved to would be the
 * stored mapping the derivation exists not to have: it would accumulate one
 * durable correlatable link per question asked, and an erasure would have to
 * remember to go and delete them. Recording the scope and the person instead
 * loses nothing while that person exists — anybody entitled to ask can derive
 * the pseudonym back from those two — and loses exactly the right thing once
 * their key is destroyed.
 *
 * <p>It is also the shape the question is actually asked in. "Who looked me
 * up" is asked about a person, and this is keyed by one.
 */
public record PseudonymResolution(String scope, String personId, String actor, Instant at,
                                  String purpose, String because) {

    public PseudonymResolution {
        Objects.requireNonNull(at, "at");
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException(
                    "a pseudonym is for a scope, and so is resolving one back");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException(
                    "resolving a pseudonym names who did it — turning one back into a person is "
                            + "the de-anonymising act, and an untraceable one is the failure this "
                            + "record exists to prevent");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException(
                    "resolving a pseudonym states its purpose: it is a disclosure, and 'why' is "
                            + "what an audit is asked for");
        }
    }

    /** It reached somebody. */
    public static PseudonymResolution to(String scope, String personId, String actor, Instant at,
            String purpose, String because) {
        return new PseudonymResolution(scope, Objects.requireNonNull(personId, "personId"),
                actor, at, purpose, because);
    }

    /**
     * It reached nobody — recorded all the same, because the asking is the
     * disclosure attempt and a trail that held only the successful ones would
     * answer "who looked for me" with silence.
     */
    public static PseudonymResolution nobody(String scope, String actor, Instant at,
            String purpose, String because) {
        return new PseudonymResolution(scope, null, actor, at, purpose, because);
    }

    /** Whether it reached a person at all. */
    public boolean found() {
        return personId != null;
    }
}
