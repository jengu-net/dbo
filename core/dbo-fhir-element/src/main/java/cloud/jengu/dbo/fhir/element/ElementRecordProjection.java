package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.RecordProjection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    /**
     * The codes a run's output carries.
     *
     * <p>Its own URI, and not {@code urn:dbo:run}, which is the identifier
     * system of a run's KEY. One URI meaning both a namespace and a code
     * system left a client meeting it unable to tell which it had met, and
     * publishing both a NamingSystem and a CodeSystem there described the
     * ambiguity rather than resolving it (#91). Named like its siblings —
     * {@code :run:holder}, {@code :run:tally} — which were never ambiguous
     * because they were never the run's own identifier.
     */
    private static final String RUN_OUTPUT = "urn:dbo:run:output";
    private static final String HOLDER = "urn:dbo:run:holder";
    private static final String PROCESS = "urn:dbo:process";
    private static final String STEP = "urn:dbo:step";
    private static final String TALLY = "urn:dbo:run:tally";
    private static final String CORRELATION = "urn:dbo:correlation";
    private static final String EXECUTOR = "urn:dbo:executor";
    private static final String AUDIT_CODE_SYSTEM = "urn:dbo:audit";
    private static final String AUTH_CLIENT_ID = "urn:dbo:auth:client-id";

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

    /**
     * What dbo says that FHIR has no word for, defined where a client can
     * fetch it (#91).
     *
     * <p>Two resources for two kinds of thing, because conflating them would
     * be the same imprecision this is fixing: a set of codes is a
     * {@code CodeSystem}, and a namespace whose values are identifiers — a
     * run's key, an executor's name — is a {@code NamingSystem}. Where the
     * codes are dbo's own and closed, they are listed; where a module or an
     * application supplies them, the definition says {@code not-present}
     * rather than pretending to an enumeration it does not have.
     */
    @Override
    public List<String> vocabularies() {
        return List.of(
                codeSystem(AUDIT_CODE_SYSTEM, "DboAuditEventCodes",
                        "What an audited interaction was. dbo records its own "
                                + "(create, update, delete, read) and applications contribute "
                                + "business-level codes of their own.",
                        null),
                codeSystem(HOLDER, "DboRunHolder",
                        "Whose a run is right now: nobody's while it executes, "
                                + "nobody's while a retry is scheduled, and a person's "
                                + "when it needs somebody.",
                        List.of("AUTOMATION", "RETRY", "PERSON", "NOBODY")),
                codeSystem(PROCESS, "DboProcess",
                        "The process a run belongs to, named by the module that declares it.",
                        null),
                codeSystem(STEP, "DboStep",
                        "The step a run is at, named by the module that declares it.", null),
                codeSystem(TALLY, "DboRunTally",
                        "What a step counted — the names are the step's own.", null),
                codeSystem(RUN_OUTPUT, "DboRunOutput",
                        "What a run carries beside its tally.", List.of("outcome")),
                // Meta.security stamps carry this system on every served
                // resource (#109), and a system on the wire must resolve
                // (#91). The seven codes are exactly the seven handling
                // classes a tenant spec may declare -- the classification the
                // reader is being governed by, said where FHIR says it.
                codeSystem("urn:dbo:handling", "DboHandling",
                        "The handling class this store enforces on a record: who may "
                                + "write it, whether it may change, whether it is kept, and "
                                + "whether it may leave.",
                        List.of("operational", "projected-config", "replicated", "mirrored",
                                "store-authored", "audit", "ephemeral")),
                // The IDENTIFIER namespaces, answered the way FHIR answers
                // "what is this identifier system" — with a NamingSystem, not
                // with a CodeSystem it is not (#91). A client that meets
                // urn:dbo:run as an identifier's system finds this by asking
                // the tenant it came from: NamingSystem?value=urn:dbo:run,
                // which is a search parameter every version defines.
                namingSystem(RUN, "DboRunKey",
                        "A run's own key, minted by the store that runs it."),
                namingSystem(CORRELATION, "DboCorrelation",
                        "What ties one caller's related work together across runs."),
                namingSystem(EXECUTOR, "DboExecutor",
                        "Which executor claimed and ran a step."),
                namingSystem(AUTH_CLIENT_ID, "DboAuthClientId",
                        "The client an access token was issued to, as the store knows it."),
                // The one extension this face puts on a served resource. A dbo
                // concept that rides in a resource is described by a definition
                // a client can fetch, or it is a convention somebody has to be
                // told about (#91).
                originalContentExtension());
    }

    /**
     * An identifier namespace, said in FHIR's own words.
     *
     * <p>No {@code url}: the element does not exist in R4, and one document
     * that every served version accepts is worth more than three that differ
     * by a field nobody reads. Identity comes from the {@code uniqueId} of
     * type {@code uri} instead, which is the namespace itself and is the same
     * value in every version.
     *
     * <p>The date is fixed rather than the current one. It is required, and a
     * definition whose content changed on every boot would republish forever
     * and defeat the check that asks whether anything moved.
     */
    private static String namingSystem(String namespace, String name, String description) {
        return "{\"resourceType\":\"NamingSystem\",\"name\":" + Json.quoted(name)
                + ",\"status\":\"active\",\"kind\":\"identifier\""
                + ",\"date\":\"2026-01-01\",\"description\":" + Json.quoted(description)
                + ",\"uniqueId\":[{\"type\":\"uri\",\"value\":" + Json.quoted(namespace)
                + ",\"preferred\":true}]}";
    }

    /**
     * The definition of the extension a stored CodeSystem shell carries.
     *
     * <p>A shell says {@code content: not-present} because its concepts live
     * in the native form, and this extension remembers what the publisher
     * actually declared so the resource can be handed back as it arrived. A
     * client meets it on every read of a CodeSystem, and until now had nowhere
     * to look it up.
     *
     * <p>Differential only, as an extension definition normally is: the face
     * generates the snapshot when it caches the profile (#87), the same as for
     * a tenant's own.
     */
    private static String originalContentExtension() {
        return "{\"resourceType\":\"StructureDefinition\",\"url\":"
                + Json.quoted(ElementTerminology.ORIGINAL_CONTENT_EXT)
                + ",\"name\":\"DboOriginalContent\",\"status\":\"active\""
                + ",\"kind\":\"complex-type\",\"abstract\":false,\"type\":\"Extension\""
                + ",\"description\":\"What a CodeSystem declared as its content mode before "
                + "this store held its concepts natively.\""
                + ",\"baseDefinition\":\"http://hl7.org/fhir/StructureDefinition/Extension\""
                + ",\"derivation\":\"constraint\""
                + ",\"context\":[{\"type\":\"element\",\"expression\":\"CodeSystem\"}]"
                + ",\"differential\":{\"element\":["
                + "{\"id\":\"Extension\",\"path\":\"Extension\",\"max\":\"1\"},"
                + "{\"id\":\"Extension.url\",\"path\":\"Extension.url\",\"fixedUri\":"
                + Json.quoted(ElementTerminology.ORIGINAL_CONTENT_EXT) + "},"
                + "{\"id\":\"Extension.value[x]\",\"path\":\"Extension.value[x]\""
                + ",\"min\":1,\"type\":[{\"code\":\"code\"}]}]}}";
    }

    /** Which content mode a definition declares. */
    private static String content(List<String> codes) {
        return codes == null ? "not-present" : "complete";
    }

    /** A dbo code system: listed where the codes are dbo's, not-present where they are not. */
    private static String codeSystem(String url, String name, String description,
            List<String> codes) {
        // A version that changes when the VOCABULARY changes and not
        // otherwise, so a bring-up can tell "already published" from
        // "published something else" with one read (#98). Derived from what
        // the definition says rather than from dbo's release number, which
        // moves for reasons a code system does not care about.
        String version = Integer.toHexString(
                (url + '|' + content(codes) + '|' + (codes == null ? "" : String.join(",", codes)))
                        .hashCode());
        StringBuilder json = new StringBuilder("{\"resourceType\":\"CodeSystem\",\"url\":")
                .append(Json.quoted(url)).append(",\"version\":").append(Json.quoted(version))
                .append(",\"name\":").append(Json.quoted(name))
                .append(",\"status\":\"active\",\"description\":")
                .append(Json.quoted(description))
                .append(",\"caseSensitive\":true,\"content\":")
                .append(codes == null ? "\"not-present\"" : "\"complete\"");
        if (codes != null) {
            json.append(",\"count\":").append(codes.size()).append(",\"concept\":[");
            for (int i = 0; i < codes.size(); i++) {
                json.append(i > 0 ? "," : "").append("{\"code\":")
                        .append(Json.quoted(codes.get(i))).append('}');
            }
            json.append(']');
        }
        return json.append('}').toString();
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
        //
        // Everything ELSE the document said travels whole. A face that reduced
        // it to a code and a target would be answering an R4 client in dbo's
        // vocabulary instead of its own — the poster's coding systems, source,
        // entity and extensions are what makes the answer an AuditEvent it
        // recognises (#90, #91). The engine carries these bytes and never
        // reads them; reading them is this class's, on the way back out.
        return Optional.of(new Posted(firstCode(posted), targetType, targetId,
                document.getBytes(StandardCharsets.UTF_8), forwardedId(posted)));
    }

    /**
     * The stable id the poster gave this event, from {@code meta.tag} (#120).
     *
     * <p>R4 gives {@code AuditEvent} no {@code identifier} element, and
     * stamping one anyway is not a workaround: the version's own parser drops
     * an element the resource does not define, so the write succeeds, the id
     * is silently absent, and the second delivery lands as a duplicate with
     * nothing saying so. {@code meta.tag} is where an id can actually live on
     * this resource, so that is where it is read from.
     *
     * <p>The first tag only. A resource carries tags for several reasons and
     * treating all of them as identity would make two events that merely share
     * a label into one event.
     */
    private static String forwardedId(Map<?, ?> posted) {
        if (!(posted.get("meta") instanceof Map<?, ?> meta)
                || !(meta.get("tag") instanceof List<?> tags) || tags.isEmpty()
                || !(tags.get(0) instanceof Map<?, ?> tag)) {
            return null;
        }
        Object system = tag.get("system");
        Object code = tag.get("code");
        if (code == null || String.valueOf(code).isBlank()) {
            return null;
        }
        // system|code, the token spelling a client would have written the
        // condition in — so what the store dedupes on and what the poster
        // thinks it asked for are the same string.
        return (system == null ? "" : system) + "|" + code;
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
        owner(run, holder, json);
        note(run, json);
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
     * because nobody has taken it.
     *
     * <p>{@code Device}-shaped, and named by what resolution chose: the name
     * identifies it, and the display carries the version and the provider,
     * because a provider can be withdrawn and "which behaviour was that" is the
     * question a year later.
     */
    private static void owner(Map<?, ?> run, String holder, StringBuilder json) {
        if (!"automation".equals(holder) && !"retry".equals(holder)) {
            return;
        }
        json.append(",\"owner\":{\"type\":\"Device\"");
        if (run.get("executor") instanceof Map<?, ?> executor) {
            json.append(",\"identifier\":{\"system\":\"").append(EXECUTOR).append("\",\"value\":")
                    .append(Json.quoted(String.valueOf(executor.get("name")))).append('}')
                    .append(",\"display\":").append(Json.quoted(
                            executor.get("name") + " " + executor.get("version")
                                    + " (" + executor.get("provider") + ")"));
        } else {
            json.append(",\"display\":\"dbo\"");
        }
        json.append('}');
    }

    /**
     * What resolution had to say, where a person reads it: why nothing
     * automated took this, or what was refused on the way to the one that did.
     */
    private static void note(Map<?, ?> run, StringBuilder json) {
        if (run.get("note") != null) {
            json.append(",\"note\":[{\"text\":")
                    .append(Json.quoted(String.valueOf(run.get("note")))).append("}]");
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
                    .append("{\"type\":{\"coding\":[{\"system\":\"").append(RUN_OUTPUT)
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
    /**
     * An entry a domain contributed, given back in the domain's own words.
     *
     * <p>The poster's document is the answer — its coding systems, its
     * {@code source}, its {@code entity}, its extensions — with the fields the
     * container OWNS stamped over whatever it claimed: {@code id},
     * {@code recorded} and {@code agent}. So a client reads an AuditEvent it
     * recognises, and the two facts it may not assert are visibly the store's
     * (REQ-DBO-POL-ACTOR-FROM-AUTHORITY).
     *
     * <p>Decoded here and nowhere earlier: the bytes are base64 in the record
     * because the engine carries them without a shape, and this is the one
     * place that knows what shape they have.
     */
    @SuppressWarnings("unchecked")
    private String contributedAuditEvent(Map<?, ?> entry, String id) {
        Map<String, Object> document = (Map<String, Object>) Json.parse(new String(
                java.util.Base64.getDecoder().decode(String.valueOf(entry.get("contributed"))),
                StandardCharsets.UTF_8));
        document.put("id", id);
        document.put("recorded", entry.get("at"));
        document.put("agent", agents(entry));
        return Json.render(document);
    }

    /** Who the container observed, and the human it was acting for. */
    private List<Object> agents(Map<?, ?> entry) {
        List<Object> agents = new ArrayList<>();
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("system", AUTH_CLIENT_ID);
        who.put("value", String.valueOf(entry.get("actor")));
        Map<String, Object> identifier = new LinkedHashMap<>();
        identifier.put("identifier", who);
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("who", identifier);
        agent.put("requestor", true);
        agents.add(agent);
        if (entry.get("onBehalfOf") != null) {
            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("reference", String.valueOf(entry.get("onBehalfOf")));
            Map<String, Object> behalf = new LinkedHashMap<>();
            behalf.put("who", reference);
            behalf.put("requestor", false);
            agents.add(behalf);
        }
        return agents;
    }

    private String auditEvent(Record record) {
        Map<?, ?> entry = (Map<?, ?>) Json.parse(
                new String(record.payload(), StandardCharsets.UTF_8));
        if (entry.get("contributed") != null) {
            return contributedAuditEvent(entry, record.id());
        }
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
