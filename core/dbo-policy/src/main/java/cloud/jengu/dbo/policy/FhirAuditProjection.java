package cloud.jengu.dbo.policy;

import cloud.jengu.dbo.core.api.Criteria;
import cloud.jengu.dbo.core.api.EnvelopeValue;

import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.rest.AuditSurface;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AuditEvent as a PROJECTION (§15.1, the terminology pattern): the native
 * audit records are the truth form; this renders them per personality on
 * read and maps posted AuditEvents into native custom entries. R4 and R5
 * differ in shape (type/subtype vs category/code, outcome coding) — both
 * renderings are hand-built here; the projection is a view, never a store,
 * so personality validation does not apply.
 */
public final class FhirAuditProjection implements AuditSurface {

    private static final String CODE_SYSTEM = "urn:dbo:audit";

    private final PolicyObjectStore store;
    private final boolean r5;

    public FhirAuditProjection(PolicyObjectStore store, String fhirVersion) {
        this.store = store;
        this.r5 = "r5".equals(fhirVersion);
    }

    @Override
    public String search(Map<String, String> query, String baseUrl) {
        Criteria criteria = Criteria.of("AuditEntry").sortByLastUpdated(false).limit(100);
        if (query.get("agent") != null) {
            criteria.eq("actor", EnvelopeValue.of(query.get("agent")));
        }
        if (query.get("entity") != null) {
            criteria.eq("targetId", EnvelopeValue.of(query.get("entity")));
        }
        if (query.get("action") != null) {
            criteria.eq("interaction", EnvelopeValue.of(switch (query.get("action")) {
                case "C" -> "create";
                case "U" -> "update";
                case "D" -> "delete";
                case "R" -> "read";
                case "E" -> "custom";
                default -> query.get("action");
            }));
        }
        if (query.get("date") != null) {
            String date = query.get("date");
            if (date.startsWith("ge")) {
                criteria.lastUpdated(Criteria.RangeOp.GE, Instant.parse(normalized(date.substring(2))));
            } else if (date.startsWith("le")) {
                criteria.lastUpdated(Criteria.RangeOp.LE, Instant.parse(normalized(date.substring(2))));
            }
        }
        List<StoredObject> entries = store.select(criteria);
        StringBuilder sb = new StringBuilder(
                "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":")
                .append(entries.size()).append(",\"entry\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"fullUrl\":\"").append(baseUrl).append("/AuditEvent/")
                    .append(entries.get(i).id()).append("\",\"resource\":")
                    .append(render(entries.get(i))).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String normalized(String date) {
        return date.length() == 10 ? date + "T00:00:00Z" : date;
    }

    @Override
    public Optional<String> read(String id) {
        return store.get("AuditEntry", id).map(this::render);
    }

    @Override
    @SuppressWarnings("unchecked")
    public String create(String auditEventJson) {
        Map<String, Object> posted = (Map<String, Object>) Json.parse(auditEventJson);
        String code = firstCode(posted);
        String targetType = null;
        String targetId = null;
        if (posted.get("entity") instanceof List<?> entities && !entities.isEmpty()
                && entities.get(0) instanceof Map<?, ?> entity
                && entity.get("what") instanceof Map<?, ?> what
                && what.get("reference") != null) {
            String reference = String.valueOf(what.get("reference"));
            int slash = reference.indexOf('/');
            if (slash > 0) {
                targetType = reference.substring(0, slash);
                targetId = reference.substring(slash + 1);
            } else {
                targetType = reference;
            }
        }
        // agent/recorded in the posted body are DELIBERATELY ignored — the
        // machinery stamps who and when (§15.1)
        String id = store.recordCustom(code, targetType, targetId, Map.of());
        return read(id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static String firstCode(Map<String, Object> posted) {
        for (String field : List.of("subtype", "category")) {
            if (posted.get(field) instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> concept) {
                String code = codingCode(concept);
                if (code != null) {
                    return code;
                }
            }
        }
        for (String field : List.of("type", "code")) {
            if (posted.get(field) instanceof Map<?, ?> concept) {
                String code = codingCode(concept);
                if (code != null) {
                    return code;
                }
            }
        }
        return "custom";
    }

    private static String codingCode(Map<?, ?> concept) {
        if (concept.get("code") != null) {
            return String.valueOf(concept.get("code"));
        }
        if (concept.get("coding") instanceof List<?> codings && !codings.isEmpty()
                && codings.get(0) instanceof Map<?, ?> coding && coding.get("code") != null) {
            return String.valueOf(coding.get("code"));
        }
        return null;
    }

    // ------------------------------------------------------------- rendering

    @SuppressWarnings("unchecked")
    private String render(StoredObject entry) {
        Map<String, Object> n = (Map<String, Object>) Json.parse(
                new String(entry.payload(), StandardCharsets.UTF_8));
        String interaction = String.valueOf(n.get("interaction"));
        String code = n.get("code") != null ? String.valueOf(n.get("code")) : interaction;
        String action = switch (interaction) {
            case "create" -> "C";
            case "update" -> "U";
            case "delete", "retention-remove" -> "D";
            case "read", "history" -> "R";
            default -> "E";
        };
        StringBuilder sb = new StringBuilder("{\"resourceType\":\"AuditEvent\",\"id\":\"")
                .append(entry.id()).append("\"");
        String coding = "{\"coding\":[{\"system\":\"" + CODE_SYSTEM + "\",\"code\":\"" + code + "\"}]}";
        if (r5) {
            sb.append(",\"category\":[").append(coding).append(']')
                    .append(",\"code\":").append(coding)
                    .append(",\"action\":\"").append(action).append("\"")
                    .append(",\"recorded\":\"").append(n.get("at")).append("\"")
                    .append(",\"outcome\":{\"code\":{\"system\":\"http://terminology.hl7.org/CodeSystem/audit-event-outcome\",\"code\":\"0\"}}");
        } else {
            sb.append(",\"type\":{\"system\":\"").append(CODE_SYSTEM)
                    .append("\",\"code\":\"").append(code).append("\"}")
                    .append(",\"action\":\"").append(action).append("\"")
                    .append(",\"recorded\":\"").append(n.get("at")).append("\"")
                    .append(",\"outcome\":\"0\"");
        }
        sb.append(",\"agent\":[{\"who\":{\"identifier\":{\"system\":\"urn:dbo:auth:client-id\"")
                .append(",\"value\":\"").append(n.get("actor")).append("\"}},\"requestor\":true}]");
        sb.append(",\"source\":{\"observer\":{\"display\":\"dbo\"}}");
        if (n.get("targetId") != null) {
            sb.append(",\"entity\":[{\"what\":{\"reference\":\"")
                    .append(n.get("targetType")).append('/').append(n.get("targetId"))
                    .append("\"}}]");
        }
        return sb.append('}').toString();
    }
}
