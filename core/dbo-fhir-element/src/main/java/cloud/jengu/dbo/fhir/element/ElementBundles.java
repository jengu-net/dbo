package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.HandlingRefusedException;
import cloud.jengu.dbo.core.api.IdentityConflictException;
import cloud.jengu.dbo.core.api.PolicyViolationException;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.VersionConflictException;
import cloud.jengu.dbo.core.UuidV7;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.formats.IParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A posted Bundle, answered entry by entry (#86).
 *
 * <p>Two request kinds, two promises. A <b>batch</b>'s entries are
 * independent: each is applied exactly as the standalone request would be,
 * each answers with its own status, and one entry failing says nothing about
 * the others (REQ-DBO-CORE-BATCH-ANSWERS-PER-ENTRY). A <b>transaction</b> is
 * one unit: every entry lands or none does, carried by the engine's own
 * multi-write transaction — data, history and outbox for all entries commit
 * together (REQ-DBO-CORE-ATOMIC-TRANSACTION-BUNDLE).
 *
 * <p>Entries go through the SAME facade calls the typed routes use — a
 * bundle interpreter beside the write path would be a second write path, and
 * the two would drift. What a standalone create validates, an entry
 * validates; what it refuses, an entry refuses.
 *
 * <p>What this does not do, it refuses by name: an unsupported bundle type,
 * an entry without a request, a method this store does not serve in this
 * kind of bundle. The 500 this class replaced told a caller nothing except
 * to retry, which is the one wrong answer.
 */
final class ElementBundles {

    private final ElementStore store;
    private final SimpleWorkerContext context;

    ElementBundles(ElementStore store, SimpleWorkerContext context) {
        this.store = store;
        this.context = context;
    }

    /** One entry as received: where it sits, what it asks, what it carries. */
    private record Entry(int index, String fullUrl, Element resource,
            String method, String url, String ifNoneExist, Long ifMatchVersion) {}

    String process(Element bundle) {
        if (!"Bundle".equals(bundle.fhirType())) {
            throw new IllegalArgumentException("the base endpoint accepts a Bundle; this is a "
                    + bundle.fhirType());
        }
        String type = bundle.getNamedChildValue("type");
        List<Entry> entries = entries(bundle);
        return switch (type == null ? "" : type) {
            case "batch" -> batch(entries);
            case "transaction" -> transaction(entries);
            default -> throw new IllegalArgumentException("bundle type '" + type
                    + "' is not a request this endpoint serves — it accepts "
                    + "'transaction' and 'batch'");
        };
    }

    private List<Entry> entries(Element bundle) {
        List<Entry> out = new ArrayList<>();
        List<Element> elements = bundle.getChildrenByName("entry");
        for (int i = 0; i < elements.size(); i++) {
            Element entry = elements.get(i);
            Element request = entry.getNamedChild("request");
            if (request == null) {
                throw new IllegalArgumentException("entry[" + i + "] has no request — "
                        + "a request bundle says what to do with every entry");
            }
            String method = request.getNamedChildValue("method");
            String url = request.getNamedChildValue("url");
            if (method == null || url == null) {
                throw new IllegalArgumentException("entry[" + i + "] request needs both "
                        + "method and url");
            }
            out.add(new Entry(i, entry.getNamedChildValue("fullUrl"),
                    entry.getNamedChild("resource"), method, url,
                    request.getNamedChildValue("ifNoneExist"),
                    version(request.getNamedChildValue("ifMatch"))));
        }
        return out;
    }

    private static Long version(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String v = ifMatch.replace("W/", "").replace("\"", "").trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ifMatch must carry a version ETag, not '"
                    + ifMatch + "'");
        }
    }

    // ---------------------------------------------------------------- batch

    /** Every entry on its own: the standalone call, the standalone verdict. */
    private String batch(List<Entry> entries) {
        List<String> responses = new ArrayList<>();
        for (Entry entry : entries) {
            try {
                responses.add(apply(entry));
            } catch (Exception e) {
                responses.add(failed(e));
            }
        }
        return bundle("batch-response", responses);
    }

    /** The one entry, through the same facade call the typed route makes. */
    private String apply(Entry entry) {
        String[] segments = entry.url().split("/");
        String type = segments[0].contains("?")
                ? segments[0].substring(0, segments[0].indexOf('?')) : segments[0];
        switch (entry.method()) {
            case "POST" -> {
                if (segments.length != 1) {
                    throw new IllegalArgumentException("entry[" + entry.index()
                            + "]: POST goes to a type, not '" + entry.url() + "'");
                }
                PutResult result = entry.ifNoneExist() != null
                        ? store.conditionalCreate(json(required(entry)),
                                query(entry.ifNoneExist()))
                        : store.create(json(required(entry)));
                return succeeded(result.created() ? "201 Created" : "200 OK", type, result);
            }
            case "PUT" -> {
                // Conditional update (R4 §3.1.0.7.1): PUT [type]?[search] is
                // "this resource, identified by its canonical, should exist
                // with these contents". Conditional CREATE cannot stand in —
                // it is a no-op when the resource is present, so a definition
                // changed upstream keeps its old contents and the caller is
                // told it worked (#99).
                int question = entry.url().indexOf('?');
                if (question > 0) {
                    PutResult result = store.conditionalUpdate(json(required(entry)),
                            query(entry.url().substring(question + 1)));
                    return succeeded(result.created() ? "201 Created" : "200 OK",
                            entry.url().substring(0, question), result);
                }
                if (segments.length != 2) {
                    throw new IllegalArgumentException("entry[" + entry.index()
                            + "]: PUT goes to an instance or a condition, not '"
                            + entry.url() + "'");
                }
                PutResult result = store.update(segments[1], entry.ifMatchVersion(),
                        json(required(entry)));
                return succeeded(result.created() ? "201 Created" : "200 OK", type, result);
            }
            case "DELETE" -> {
                if (segments.length != 2) {
                    throw new IllegalArgumentException("entry[" + entry.index()
                            + "]: DELETE goes to an instance, not '" + entry.url() + "'");
                }
                store.delete(type, segments[1], entry.ifMatchVersion());
                return "{\"response\":{\"status\":\"204 No Content\"}}";
            }
            case "GET" -> {
                if (segments.length != 2 || entry.url().contains("?")) {
                    throw new IllegalArgumentException("entry[" + entry.index()
                            + "]: GET in a bundle reads one instance — searches go to "
                            + "the search endpoint");
                }
                var read = store.readForServing(type, segments[1]);
                if (read == null) {
                    return "{\"response\":{\"status\":\"404 Not Found\"}}";
                }
                return "{\"response\":{\"status\":\"200 OK\",\"etag\":\"W/\\\""
                        + read.versionId() + "\\\"\"},\"resource\":" + read.resourceJson() + "}";
            }
            default -> throw new IllegalArgumentException("entry[" + entry.index()
                    + "]: method '" + entry.method() + "' is not served in a bundle");
        }
    }

    /**
     * One spelling for one identity, however the query writes it: parsed by
     * the same rules a reference is (#129), so an entry claiming
     * {@code ?identifier=a%7Cb} and a reference asking {@code ?identifier=a|b}
     * meet at the same key.
     */
    private String claimKey(String conditionalUrl) {
        int q = conditionalUrl.indexOf('?');
        String type = conditionalUrl.substring(0, q);
        cloud.jengu.dbo.core.api.Identifier identity =
                store.identityOf(type, conditionalUrl.substring(q + 1));
        return type + "\u0000" + identity.system() + "\u0000" + identity.value();
    }

    // ---------------------------------------------------------- transaction

    /**
     * All or none. Everything that can refuse does so BEFORE the engine is
     * asked — ids allocated, references resolved, every entry validated — so
     * the one transaction the engine runs contains only writes already found
     * acceptable, and a refusal leaves the store exactly as it was.
     */
    private String transaction(List<Entry> entries) {
        Map<String, String> resolved = new HashMap<>();
        Map<Integer, String> allocated = new HashMap<>();
        // The document's own claims (#129): identity -> the id it will have.
        // Collected across EVERY entry before any reference resolves, which is
        // what makes a parent declared after its child the same tree.
        Map<String, String> claimed = new HashMap<>();
        Map<String, Integer> claimants = new HashMap<>();
        for (Entry entry : entries) {
            switch (entry.method()) {
                case "POST" -> {
                    if (entry.ifNoneExist() != null) {
                        throw new IllegalArgumentException("entry[" + entry.index()
                                + "]: ifNoneExist inside a transaction is not served yet — "
                                + "issue a conditional create on its own, or ask for it");
                    }
                    String id = UuidV7.newId();
                    allocated.put(entry.index(), id);
                    if (entry.fullUrl() != null) {
                        resolved.put(entry.fullUrl(),
                                entry.url().split("/")[0] + "/" + id);
                    }
                }
                case "PUT" -> {
                    if (entry.url().indexOf('?') >= 0) {
                        // A conditional PUT: the entry claims an identity
                        // (REQ-DBO-CORE-CONDITIONAL-UPSERT, whose "and inside
                        // a bundle" this path never honoured before). The id
                        // is the store's where the record exists — the entry
                        // becomes an update onto it — and minted where it does
                        // not, exactly as POSTs are minted above. The identity
                        // unique index backstops the race: another writer
                        // claiming it between here and commit fails the whole
                        // transaction, and the retry converges.
                        String key = claimKey(entry.url());
                        Integer other = claimants.putIfAbsent(key, entry.index());
                        if (other != null) {
                            throw new IllegalArgumentException("entry[" + entry.index()
                                    + "] and entry[" + other + "] both claim '" + entry.url()
                                    + "' — one document may claim an identity once "
                                    + "(REQ-DBO-CORE-NO-IMPLICIT-MERGE)");
                        }
                        String type = entry.url().substring(0, entry.url().indexOf('?'));
                        String query = entry.url().substring(entry.url().indexOf('?') + 1);
                        String id = store.identified(type, query).orElseGet(UuidV7::newId);
                        allocated.put(entry.index(), id);
                        claimed.put(key, id);
                        if (entry.fullUrl() != null) {
                            resolved.put(entry.fullUrl(), type + "/" + id);
                        }
                    } else if (entry.fullUrl() != null) {
                        resolved.put(entry.fullUrl(), entry.url());
                    }
                }
                default -> throw new IllegalArgumentException("entry[" + entry.index()
                        + "]: a transaction serves POST and PUT — '" + entry.method()
                        + "' entries are refused by name rather than half-applied; "
                        + "use a batch where entries stand alone");
            }
        }
        for (Entry entry : entries) {
            resolveReferences(required(entry), resolved, entry.index());
        }
        // The entries' claims answer a conditional reference before the store
        // (#129, REQ-DBO-CORE-CONDITIONAL-REFERENCES as amended): the referent
        // may be a few lines further down this document. A question neither
        // the document nor the store answers refuses exactly as it always did.
        ElementReferences.Resolver inDocument = (type, query) ->
                java.util.Optional.ofNullable(claimed.get(claimKey(type + "?" + query)));
        List<PutRequest> requests = new ArrayList<>();
        for (Entry entry : entries) {
            String resourceJson = json(entry.resource());
            var accepted = store.accepted(resourceJson, inDocument);
            String id = allocated.containsKey(entry.index())
                    ? allocated.get(entry.index()) : entry.url().split("/")[1];
            requests.add(new PutRequest(accepted.type(), id,
                    entry.method().equals("POST") ? null : entry.ifMatchVersion(),
                    accepted.payload()).stamped(accepted.shape()));
        }
        List<PutResult> results = store.engine().transact(requests);
        List<String> responses = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            responses.add(succeeded(results.get(i).created() ? "201 Created" : "200 OK",
                    requests.get(i).typeName(), results.get(i)));
        }
        return bundle("transaction-response", responses);
    }

    /**
     * A {@code urn:uuid} reference points at another entry; stored, it would
     * point at nothing. Resolved in the element tree — the document is edited
     * where it was read, composed once on its way to the engine — and an urn
     * no entry answers refuses the whole bundle, because a transaction that
     * stored a dangling reference would have manufactured exactly the broken
     * record the caller used a transaction to avoid.
     */
    private void resolveReferences(Element element, Map<String, String> resolved, int index) {
        if ("reference".equals(element.getName()) && element.primitiveValue() != null
                && element.primitiveValue().startsWith("urn:uuid:")) {
            String target = resolved.get(element.primitiveValue());
            if (target == null) {
                throw new IllegalArgumentException("entry[" + index + "] references "
                        + element.primitiveValue() + ", and no entry in this bundle "
                        + "has that fullUrl");
            }
            element.setValue(target);
        }
        for (Element child : element.getChildren()) {
            resolveReferences(child, resolved, index);
        }
    }

    // ------------------------------------------------------------- plumbing

    private Element required(Entry entry) {
        if (entry.resource() == null) {
            throw new IllegalArgumentException("entry[" + entry.index() + "]: a "
                    + entry.method() + " entry carries the resource it writes");
        }
        String urlType = entry.url().split("[/?]")[0];
        if (!urlType.equals(entry.resource().fhirType())) {
            throw new IllegalArgumentException("entry[" + entry.index() + "]: the request "
                    + "url names " + urlType + " and the resource is a "
                    + entry.resource().fhirType() + " — stored as asked, that entry would "
                    + "answer a location its resource does not live at");
        }
        return entry.resource();
    }

    /** The entry's subtree, composed once — values keep their lexical forms. */
    private String json(Element resource) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Manager.compose(context, resource, out, Manager.FhirFormat.JSON,
                    IParser.OutputStyle.NORMAL, null);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot compose a bundle entry", e);
        }
    }

    private static Map<String, String> query(String ifNoneExist) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : ifNoneExist.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private String succeeded(String status, String type, PutResult result) {
        return "{\"response\":{\"status\":\"" + status + "\",\"location\":\""
                + type + "/" + result.id() + "/_history/" + result.versionId()
                + "\",\"etag\":\"W/\\\"" + result.versionId() + "\\\"\"}}";
    }

    /**
     * The refusal a standalone request would have answered with, as this
     * entry's response — the same mapping the HTTP surface uses, so a batch
     * caller reads the same statuses either way.
     */
    private String failed(Exception e) {
        int status;
        String code;
        String diagnostics = String.valueOf(e.getMessage());
        switch (e) {
            case ValidationFailedException v -> {
                status = 422; code = "invalid"; diagnostics = String.join("; ", v.issues());
            }
            case VersionConflictException x -> { status = 412; code = "conflict"; }
            case IdentityConflictException x -> { status = 409; code = "duplicate"; }
            case HandlingRefusedException x -> { status = 403; code = "forbidden"; }
            case PolicyViolationException x -> { status = 409; code = "business-rule"; }
            case IllegalArgumentException x -> { status = 400; code = "invalid"; }
            default -> { status = 500; code = "exception"; }
        }
        return "{\"response\":{\"status\":\"" + status + "\",\"outcome\":"
                + store.operationOutcome(code, diagnostics) + "}}";
    }

    private static String bundle(String type, List<String> entries) {
        return "{\"resourceType\":\"Bundle\",\"type\":\"" + type + "\",\"entry\":["
                + String.join(",", entries) + "]}";
    }
}
