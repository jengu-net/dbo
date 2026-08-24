package cloud.jengu.dbo.rest;

import java.util.Map;
import java.util.Optional;

/**
 * The audit trail's FHIR face (§15.1): served when the tenant's policy
 * layer provides one. Read renders the native truth-form records as
 * AuditEvent; create maps a posted AuditEvent INTO a native custom entry
 * (never stored raw — the machinery re-stamps actor and time). Update and
 * delete never exist here: the trail is unconditionally append-only.
 */
public interface AuditSurface {

    /**
     * The search parameters this surface actually honours — declared by the
     * thing that implements them, so the capability statement cannot drift
     * from the filtering (#90).
     *
     * <p>The trail is searched over the store's OWN facts about an
     * interaction. What a domain contributed rides opaquely and is not
     * indexed, so it is not searchable, and saying so is the point: a
     * parameter advertised and then ignored answers 200 with rows nobody
     * asked for, which is worse than refusing.
     */
    default java.util.Set<String> searchParameters() {
        return java.util.Set.of("agent", "entity", "action", "date");
    }

    /** A searchset Bundle of rendered AuditEvents. */
    String search(Map<String, String> query, String baseUrl);

    Optional<String> read(String id);

    /** @return the created entry, rendered — agent/recorded are the machinery's */
    String create(String auditEventJson);

    /**
     * The same, saying whether this posting created the entry or found one
     * already recorded under the id the poster gave it (#120).
     *
     * @param rendered the entry, whichever of the two it is
     * @param created  false when an earlier delivery of the same event had
     *                 already landed — the difference between at-least-once
     *                 delivery and at-least-once receipt
     */
    record Recorded(String rendered, boolean created) {
    }

    /**
     * Post an event, effectively-once when the document carries a stable id.
     *
     * <p>Default: a surface that only appends says so by reporting every
     * posting as a creation, which is what it is.
     */
    default Recorded record(String auditEventJson) {
        return new Recorded(create(auditEventJson), true);
    }
}
