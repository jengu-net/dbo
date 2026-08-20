package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.RecordProjection;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The engine's own records in FHIR's words: a run as a {@code Task}, an audit
 * entry as an {@code AuditEvent}.
 *
 * <p>One implementation for every version this face serves. R4, R5 and R6 do
 * not spell {@code AuditEvent} identically, and where they differ the
 * difference is a line here rather than a second implementation — three copies
 * of a projection drift the moment one of them is fixed.
 *
 * <p><b>Generated, never round-tripped.</b> A document built here carries no
 * stored payload, so the rule that forbids re-rendering stored bytes through a
 * model does not bite: nothing a caller sent is being rewritten. What it does
 * carry is the run's own account of itself, which is the engine's.
 */
final class ElementRecordProjection implements RecordProjection {

    /** The run vocabulary, in one place, because a system URL typed twice is two systems. */
    private static final String RUN = "urn:dbo:run";
    private static final String HOLDER = "urn:dbo:run:holder";
    private static final String PROCESS = "urn:dbo:process";
    private static final String STEP = "urn:dbo:step";
    private static final String TALLY = "urn:dbo:run:tally";
    private static final String CORRELATION = "urn:dbo:correlation";
    private static final String AUDIT_CODE_SYSTEM = "urn:dbo:audit";

    private final String domain;
    private final boolean auditIsCategorised;
    private final boolean focusIsABackbone;

    /**
     * @param domain the storage domain this face claims — a run over any other
     *               renders nowhere here
     * @param code   the version code, which decides the shapes that differ
     */
    ElementRecordProjection(String domain, String code) {
        this.domain = domain;
        // R4 says type/subtype and a string outcome; R5 recast it as
        // category/code with a coded one, and R6 kept R5's shape.
        this.auditIsCategorised = !"r4".equals(code);
        // R6 recast Task.focus as a repeating backbone with a required
        // value[x]; before it, focus was one Reference.
        this.focusIsABackbone = "r6".equals(code);
    }

    @Override
    public Set<String> projects() {
        return Set.of("Run", "AuditEntry");
    }

    @Override
    public Optional<String> project(Record record) {
        if (!record.domains().isEmpty() && !record.domains().contains(domain)) {
            return Optional.empty();
        }
        return switch (record.typeName()) {
            case "Run" -> Optional.of(runDocument(record));
            case "AuditEntry" -> Optional.of(auditEvent(record));
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<Posted> readPosted(String typeName, String document) {
        if (!"AuditEntry".equals(typeName)) {
            return Optional.empty();
        }
        Map<?, ?> posted = (Map<?, ?>) Json.parse(document);
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
        // agent and recorded are DELIBERATELY not read: the machinery stamps
        // who and when, and a posted claim about either is not evidence.
        return Optional.of(new Posted(firstCode(posted), targetType, targetId));
    }

    // ------------------------------------------------------------------ runs

    /**
     * A run and what it did, as one collection.
     *
     * <p>Items are their own {@code Task}s rather than lines inside the
     * parent's: a person fixes one thing at a time, and a single card saying
     * "two problems" cannot be half done. They are linked by the run's own key
     * rather than by a resolved reference, because the key is what the record
     * is identified by and a document is read where no server may be listening.
     */
    private String runDocument(Record record) {
        StringBuilder json = new StringBuilder(1024)
                .append("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[")
                .append(entry(record, record.id()));
        for (Record child : record.children()) {
            json.append(',').append(entry(child, record.id()));
        }
        return json.append("]}").toString();
    }

    /**
     * One entry, identified by the record's own id.
     *
     * <p>A {@code urn:uuid} rather than a server address: this document is read
     * where no server may be listening, and an entry without an identity is
     * refused by the version's own validator anyway.
     */
    private String entry(Record record, String parentId) {
        return "{\"fullUrl\":\"urn:uuid:" + record.id() + "\",\"resource\":"
                + task(record, parentId) + "}";
    }

    /**
     * A run as a {@code Task}.
     *
     * <p>Holder is the load-bearing field, so it lands twice: as the status a
     * FHIR client already understands, and as {@code businessStatus}, which
     * keeps the distinction FHIR's four words lose — automation running and
     * automation with a retry pending are both "not finished" to a client and
     * are not the same thing to an operator.
     */
    private String task(Record record, String parentId) {
        Map<?, ?> run = (Map<?, ?>) Json.parse(new String(record.payload(), StandardCharsets.UTF_8));
        String holder = String.valueOf(run.get("holder"));
        StringBuilder json = new StringBuilder(512)
                .append("{\"resourceType\":\"Task\",\"id\":").append(Json.quoted(record.id()))
                .append(",\"meta\":{\"versionId\":").append(Json.quoted(String.valueOf(record.versionId())))
                .append('}');
        contained(record, json);
        json.append(",\"identifier\":[{\"system\":\"").append(RUN).append("\",\"value\":")
                .append(Json.quoted(String.valueOf(run.get("key")))).append('}');
        if (run.get("correlation") != null) {
            // echoed, never parsed — and queryable from the other side for it
            json.append(",{\"system\":\"").append(CORRELATION).append("\",\"value\":")
                    .append(Json.quoted(String.valueOf(run.get("correlation")))).append('}');
        }
        json.append(']');
        json.append(",\"status\":\"").append(status(holder)).append('"')
                .append(",\"businessStatus\":{\"coding\":[{\"system\":\"").append(HOLDER)
                .append("\",\"code\":\"").append(holder).append("\"}]}")
                .append(",\"intent\":\"order\"")
                .append(",\"code\":{\"coding\":[{\"system\":\"").append(PROCESS)
                .append("\",\"code\":").append(Json.quoted(String.valueOf(run.get("process"))))
                .append("},{\"system\":\"").append(STEP).append("\",\"code\":")
                .append(Json.quoted(String.valueOf(run.get("step")))).append("}]}");
        if (run.get("parent") != null) {
            // items are children; a subprocess elsewhere is a reference, and
            // never arrives here as one
            // both, and for different readers: the reference resolves inside
            // this document, the identifier still means something outside it
            json.append(",\"partOf\":[{\"reference\":\"urn:uuid:").append(parentId)
                    .append("\",\"identifier\":{\"system\":\"").append(RUN)
                    .append("\",\"value\":").append(Json.quoted(String.valueOf(run.get("parent"))))
                    .append("}}]");
        }
        owner(holder, json);
        focus(run, json);
        output(run, record, json);
        return json.append('}').toString();
    }

    /**
     * FHIR's four words for a state this engine keeps in one field. A person
     * holding a run is {@code ready}: the work is waiting for a performer, and
     * what went wrong is in the outcome rather than in the status.
     */
    private static String status(String holder) {
        return switch (holder) {
            case "nobody" -> "completed";
            case "retry" -> "on-hold";
            case "person" -> "ready";
            default -> "in-progress";
        };
    }

    /**
     * Automation owns what it is running; a person-held run names no owner,
     * because nobody has taken it. Which automation is {@code Device}-shaped
     * and unnamed until an executor has an identity to give.
     */
    private static void owner(String holder, StringBuilder json) {
        if ("automation".equals(holder) || "retry".equals(holder)) {
            json.append(",\"owner\":{\"type\":\"Device\",\"display\":\"dbo\"}");
        }
    }

    /**
     * What the item was about, as the run named it — an id, a URL, a file.
     *
     * <p>Displayed rather than resolved: an item reference is whatever the work
     * was over, and most of what work is over is not a resource in this store.
     */
    private void focus(Map<?, ?> run, StringBuilder json) {
        if (!(run.get("item") instanceof Map<?, ?> item) || item.get("reference") == null) {
            return;
        }
        String display = Json.quoted(String.valueOf(item.get("reference")));
        json.append(focusIsABackbone
                ? ",\"focus\":[{\"valueReference\":{\"display\":" + display + "}}]"
                : ",\"focus\":{\"display\":" + display + "}");
    }

    /** The failure, as an outcome the run points at rather than repeats. */
    private static void contained(Record record, StringBuilder json) {
        Map<?, ?> item = itemOf(record);
        if (item == null) {
            return;
        }
        boolean aPersonsJob = "record".equals(String.valueOf(item.get("failure")));
        json.append(",\"contained\":[{\"resourceType\":\"OperationOutcome\",\"id\":\"outcome\"")
                .append(",\"issue\":[{\"severity\":\"")
                .append(aPersonsJob ? "error" : "warning")
                .append("\",\"code\":\"").append(aPersonsJob ? "processing" : "transient")
                .append("\",\"diagnostics\":")
                .append(Json.quoted(String.valueOf(item.get("message")))).append("}]}]");
    }

    private static Map<?, ?> itemOf(Record record) {
        Map<?, ?> run = (Map<?, ?>) Json.parse(
                new String(record.payload(), StandardCharsets.UTF_8));
        return run.get("item") instanceof Map<?, ?> item ? item : null;
    }

    /**
     * The tally and the outcome, as outputs. What everything did is counts;
     * what somebody must act on is the outcome the item points at, and a child
     * run carries its own — a parent saying "two problems" cannot be half done.
     */
    private static void output(Map<?, ?> run, Record record, StringBuilder json) {
        StringBuilder outputs = new StringBuilder();
        if (run.get("tally") instanceof Map<?, ?> tally) {
            tally.forEach((name, count) -> {
                if (count instanceof Number number) {
                    outputs.append(outputs.isEmpty() ? "" : ",")
                            .append("{\"type\":{\"coding\":[{\"system\":\"").append(TALLY)
                            .append("\",\"code\":").append(Json.quoted(String.valueOf(name)))
                            .append("}]},\"valueInteger\":").append(number.longValue()).append('}');
                }
            });
        }
        if (itemOf(record) != null) {
            outputs.append(outputs.isEmpty() ? "" : ",")
                    .append("{\"type\":{\"coding\":[{\"system\":\"").append(RUN)
                    .append("\",\"code\":\"outcome\"}]},")
                    .append("\"valueReference\":{\"reference\":\"#outcome\"}}");
        }
        if (!outputs.isEmpty()) {
            json.append(",\"output\":[").append(outputs).append(']');
        }
    }

    // ----------------------------------------------------------------- audit

    /**
     * The native audit record as {@code AuditEvent} (§15.1): the entries are
     * the truth form and this is a view of them, so no personality validation
     * applies and nothing here is ever stored.
     */
    private String auditEvent(Record record) {
        Map<?, ?> entry = (Map<?, ?>) Json.parse(
                new String(record.payload(), StandardCharsets.UTF_8));
        String interaction = String.valueOf(entry.get("interaction"));
        String code = entry.get("code") != null ? String.valueOf(entry.get("code")) : interaction;
        String action = switch (interaction) {
            case "create" -> "C";
            case "update" -> "U";
            case "delete", "retention-remove" -> "D";
            case "read", "history" -> "R";
            default -> "E";
        };
        StringBuilder json = new StringBuilder("{\"resourceType\":\"AuditEvent\",\"id\":")
                .append(Json.quoted(record.id()));
        String coding = "{\"coding\":[{\"system\":\"" + AUDIT_CODE_SYSTEM + "\",\"code\":\""
                + code + "\"}]}";
        if (auditIsCategorised) {
            json.append(",\"category\":[").append(coding).append(']')
                    .append(",\"code\":").append(coding)
                    .append(",\"action\":\"").append(action).append('"')
                    .append(",\"recorded\":\"").append(entry.get("at")).append('"')
                    .append(",\"outcome\":{\"code\":{\"system\":")
                    .append("\"http://terminology.hl7.org/CodeSystem/audit-event-outcome\"")
                    .append(",\"code\":\"0\"}}");
        } else {
            json.append(",\"type\":{\"system\":\"").append(AUDIT_CODE_SYSTEM)
                    .append("\",\"code\":\"").append(code).append("\"}")
                    .append(",\"action\":\"").append(action).append('"')
                    .append(",\"recorded\":\"").append(entry.get("at")).append('"')
                    .append(",\"outcome\":\"0\"");
        }
        json.append(",\"agent\":[{\"who\":{\"identifier\":{\"system\":\"urn:dbo:auth:client-id\"")
                .append(",\"value\":\"").append(entry.get("actor")).append("\"}},\"requestor\":true}");
        if (entry.get("onBehalfOf") != null) {
            // §16.4: the human the process acted for — the Provenance twin
            json.append(",{\"who\":{\"reference\":\"").append(entry.get("onBehalfOf"))
                    .append("\"},\"requestor\":false}");
        }
        json.append(']').append(",\"source\":{\"observer\":{\"display\":\"dbo\"}}");
        StringBuilder entities = new StringBuilder();
        if (entry.get("targetId") != null) {
            entities.append("{\"what\":{\"reference\":\"").append(entry.get("targetType"))
                    .append('/').append(entry.get("targetId")).append("\"}}");
        }
        if (entry.get("run") != null) {
            // the run this interaction was part of, by the run's own key: what
            // a reader follows back from a record to the work that made it
            entities.append(entities.isEmpty() ? "" : ",")
                    .append("{\"what\":{\"identifier\":{\"system\":\"").append(RUN)
                    .append("\",\"value\":")
                    .append(Json.quoted(String.valueOf(entry.get("run")))).append("}}}");
        }
        if (!entities.isEmpty()) {
            json.append(",\"entity\":[").append(entities).append(']');
        }
        return json.append('}').toString();
    }

    private static String firstCode(Map<?, ?> posted) {
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
}
