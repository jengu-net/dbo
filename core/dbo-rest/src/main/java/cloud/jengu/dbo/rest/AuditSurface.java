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

    /** A searchset Bundle of rendered AuditEvents. */
    String search(Map<String, String> query, String baseUrl);

    Optional<String> read(String id);

    /** @return the created entry, rendered — agent/recorded are the machinery's */
    String create(String auditEventJson);
}
