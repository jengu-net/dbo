package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.IdentityRef;
import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.PutRequest;
import cloud.jengu.dbo.core.api.PutResult;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.api.feed.FeedChunk;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirOperation;
import cloud.jengu.dbo.fhir.common.FhirStoreFacade;
import cloud.jengu.dbo.fhir.common.FhirTypeConfig;
import cloud.jengu.dbo.fhir.common.ValidationFailedException;
import org.hl7.fhir.r5.model.SearchParameter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The FHIR-facing facade over one tenant's store, driven entirely by what the
 * face declares.
 *
 * <p>There is one of these rather than one per version, and that is the point:
 * validating a write, framing a page, following an {@code _include} and putting
 * the ancestors back are the shape of the operations rather than knowledge
 * about a version. What differs between versions — how a payload parses, what
 * is valid, what a parameter means — is asked of the face, which asks the
 * definitions.
 */
public final class ElementStore implements FhirStoreFacade {

    private final ObjectStore store;
    private final ElementVersion version;
    private final List<FhirTypeConfig> types;
    private final String baseUrl;
    /**
     * Not final: a tenant that writes a StructureDefinition has changed what
     * validation means for it, and the next write is held to the new shape
     * rather than to whatever was true when the tenant came up.
     */
    private volatile Payloads<Object> payloads;
    private final PayloadFraming framing;
    /** Null when this store validates against carried definitions alone. */
    private final Terms terms;
    /**
     * The canonical urls the validation view was built from.
     *
     * <p>Held because the store can carry a profile the view does not: a shape
     * arrives by replication, by restore, or by a lane, and the view is built
     * from what was there when it was built. Knowing which is which is what
     * lets a claim on a held-but-unloaded shape be answered by loading it,
     * rather than by telling its author the tenant does not have a profile it
     * is holding.
     */
    private volatile java.util.Set<String> shapesInView = java.util.Set.of();
    /**
     * What this TENANT has authored, by type — empty for almost every tenant.
     *
     * <p>Not final, for the same reason {@link #payloads} is not: a tenant that
     * writes a SearchParameter has changed what its own data can be asked, and
     * the next search is answered under the new set rather than the one that
     * was true at bring-up.
     *
     * <p>Published only once the reindex behind it has finished. A parameter
     * that answered before its rows were extracted would return the handful
     * written since it arrived and silently omit the rest, which is a worse
     * answer than the refusal it replaced.
     */
    private volatile Map<String, List<SearchParameter>> authoredHere = Map.of();

    @SuppressWarnings("unchecked")
    /**
     * What is declared here, so a step id can be asked about.
     *
     * <p>Given rather than discovered. A face inside a bundle scanning the
     * classpath finds whatever is on it and cannot load half of it, which is
     * exactly how this arrived: a ServiceLoader in a field initialiser turned a
     * container's tenant bring-up into "not a subtype".
     */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ElementStore.class);

    private final cloud.jengu.dbo.core.process.Steps steps;

    ElementStore(ObjectStore store, ElementVersion version, List<FhirTypeConfig> types,
            String baseUrl) {
        this(store, version, types, baseUrl, cloud.jengu.dbo.core.process.Steps.of());
    }

    ElementStore(ObjectStore store, ElementVersion version, List<FhirTypeConfig> types,
            String baseUrl, cloud.jengu.dbo.core.process.Steps steps) {
        this(store, version, types, baseUrl, steps, null);
    }

    /**
     * The tenant-terminology form: validation consults {@code terms} for
     * systems the carried definitions do not answer. Null keeps the
     * shared, definitions-only payloads — the engine's own extraction path
     * and every caller with no tenant database stay exactly as they were.
     */
    @SuppressWarnings("unchecked")
    ElementStore(ObjectStore store, ElementVersion version, List<FhirTypeConfig> types,
            String baseUrl, cloud.jengu.dbo.core.process.Steps steps, Terms terms) {
        this.steps = steps;
        this.store = store;
        this.version = version;
        this.types = List.copyOf(types);
        this.baseUrl = baseUrl;
        this.terms = terms;
        this.shapesInView = canonicalsOf(terms == null ? List.of() : storedProfiles(store));
        this.payloads = terms == null
                ? (Payloads<Object>) version.face().require(Payloads.class)
                : (Payloads<Object>) (Payloads<?>) version.payloadsFor(terms, storedProfiles(store), storedMaps(store));
        this.framing = version.face().require(PayloadFraming.class);
    }

    /**
     * What a type may be searched by here: the version's own, plus this
     * tenant's. One view, handed to the search compiler and to the capability
     * statement alike — REQ-DBO-SRCH-HONEST-CAPABILITY is the promise that
     * those two can never be given different answers.
     */
    private ParametersInForce inForce() {
        Map<String, List<SearchParameter>> tenants = authoredHere;
        return typeName -> ElementVersion.union(version.parametersFor(typeName),
                tenants.getOrDefault(typeName, List.of()));
    }

    // ------------------------------------------------------------- writing

    /**
     * What one read of a body says about it, the bytes it was read from, and
     * the shape stamp the validation resolved
     * (REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED).
     */
    record Accepted(String type, byte[] payload, java.util.List<String> shape) {}

    /**
     * The type and the verdict from one read, and the bytes carried to the
     * write so the engine's envelope extraction is that same read
     * (REQ-DBO-VER-ONE-READ-PER-REQUEST).
     */
    Accepted accepted(String resourceJson) {
        return accepted(resourceJson, null);
    }

    /**
     * The same, with a resolver consulted BEFORE the store: a
     * transaction's entries may answer a conditional reference the store
     * cannot — the referent is being created a few lines further down the
     * document — so the bundle path passes its own claims here. A reference
     * neither answers reaches the same refusal it always did, just later.
     */
    Accepted accepted(String resourceJson, ElementReferences.Resolver first) {
        byte[] payload = resourceJson.getBytes(StandardCharsets.UTF_8);
        Object document = payloads.read(null, payload);
        String type = payloads.typeOf(document);
        ElementReferences.Resolver resolver = first == null ? this::identified
                : (typeName, query) -> {
                    java.util.Optional<String> inBundle = first.resolve(typeName, query);
                    return inBundle.isPresent() ? inBundle : identified(typeName, query);
                };
        // A reference that is a question is answered HERE, in the tree that
        // was already read. Re-reading would be a second read of one
        // payload, and keeping the original bytes would store a document the
        // validator never saw. Composed once, and only when something moved.
        boolean moved = false;
        if (document instanceof org.hl7.fhir.r5.elementmodel.Element element) {
            // The engine's own stamp never enters as content: urn:dbo:shape
            // is re-stated from the store's fact on every serve, so an echoed
            // copy is dropped HERE — before validation litigates an
            // engine-authored extension, and before it pollutes the stored
            // bytes, which stay the author's own claims
            // (REQ-DBO-SHAPE-SERVED-BESIDE-THE-CLAIM).
            for (org.hl7.fhir.r5.elementmodel.Element meta : element.getChildrenByName("meta")) {
                moved |= meta.getChildren().removeIf(child ->
                        "extension".equals(child.getName())
                                && ElementAncestors.SHAPE_URL.equals(
                                        child.getNamedChildValue("url")));
            }
            moved |= ElementReferences.resolve(element, resolver);
        }
        if (moved) {
            payload = payloads.write(document);
        }
        loadClaimedShapesThisTenantHolds(document);
        List<String> issues = payloads.validate(type, document);
        // The ordering rule's own door (REQ-DBO-SHAPE-UNPARSEABLE-VERSION-
        // REFUSED): dbo's pack is data, so "refused at pack load" means
        // refused HERE, when a shape arrives. A version whose leading
        // segment is not an integer has no major to order by — accepting it
        // would plant a stamp no bound can ever match, discovered
        // mid-migration instead of now. Joined to the validation issues so a
        // replicated copy is warned-and-held like any other finding while an
        // authored write refuses.
        if ("StructureDefinition".equals(type)
                && document instanceof org.hl7.fhir.r5.elementmodel.Element sd) {
            String declared = sd.getNamedChildValue("version");
            if (declared != null && !declared.isBlank()
                    && !declared.split("\\.", 2)[0].matches("[0-9]+")) {
                issues = new java.util.ArrayList<>(issues);
                issues.add("version '" + declared + "' has no leading integer major — "
                        + "the ordering rule compares majors, and a shape without one "
                        + "would stamp objects no version bound can ever match");
            }
        }
        // A parameter this store could never evaluate, refused where a person
        // is standing. The version's own are evaluable by construction and a
        // test holds that; a tenant's expression is arbitrary input, and the
        // only alternative to refusing it here is a reindex failing later
        // about a document nobody is watching. Joined to the findings rather
        // than thrown, so a replicated copy is held-and-warned like any other
        // arrival while an authored write refuses.
        if (SEARCH_PARAMETER.equals(type)) {
            java.util.Optional<String> why = version.whyNotEvaluable(
                    Json.str(Json.parse(new String(payload, StandardCharsets.UTF_8)),
                            "expression"));
            if (why.isPresent()) {
                issues = new java.util.ArrayList<>(issues);
                issues.add("the expression cannot be evaluated, so this parameter would be "
                        + "stored and then never answer anything: " + why.get());
            }
        }
        if (!issues.isEmpty()) {
            if (!authoredElsewhere(type)) {
                throw new ValidationFailedException(type, issues);
            }
            // Held, and said out loud. Silence would read as cleanliness, and
            // the point of accepting this is that somebody can still see what
            // arrived imperfect — including the authority that published it.
            LOG.warn("accepted with findings: type={} identity={} findings={} first={}",
                    type, identityFor(type, document), issues.size(), issues.get(0));
        }
        return new Accepted(type, payload, payloads.writtenUnder(document));
    }

    /**
     * Whether this type's content is somebody else's publication, replicated.
     *
     * <p>A type nothing declares is NOT treated as replicated: an unknown type
     * has no handling to consult, and defaulting to the permissive answer would
     * turn a missing declaration into a silently unvalidated write.
     */
    private boolean authoredElsewhere(String typeName) {
        return types.stream()
                .filter(t -> t.typeName().equals(typeName))
                .findFirst()
                .map(t -> t.handling().authoredElsewhere())
                .orElse(false);
    }

    /** Whatever names this document in a log line — its url, or its id. */
    private String identityFor(String typeName, Object document) {
        try {
            String url = version.canonicalUrlOf(document);
            if (url != null && !url.isBlank()) {
                return url;
            }
        } catch (RuntimeException e) {
            // not a canonical type, or no url on it: the id will do
        }
        return document instanceof org.hl7.fhir.r5.elementmodel.Element element
                ? String.valueOf(element.getNamedChildValue("id")) : "(unnamed)";
    }

    /**
     * What a conditional reference points at, by the type's own identity.
     *
     * <p>Held to the same rule as a conditional create
     * (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS): a reference may ask by the
     * identity a type is claimed under, never by general search. A store that
     * resolved references by arbitrary criteria would make a write's meaning
     * depend on what else happens to match today.
     *
     * <p>Several matches refuse rather than choose. Which one is the writer's
     * to say, and picking here would attach the record to the wrong subject —
     * the failure a conditional reference is used to avoid.
     */
    java.util.Optional<String> identified(String typeName, String query) {
        List<StoredObject> found = store.getByIdentifier(typeName,
                java.util.List.of(identityOf(typeName, query)));
        if (found.size() > 1) {
            throw new IllegalArgumentException("the reference '" + typeName + "?" + query
                    + "' matches " + found.size() + " records — which one is the writer's to "
                    + "say, and choosing here would attach this to the wrong one");
        }
        return found.stream().findFirst().map(StoredObject::id);
    }

    /**
     * The identity a conditional names, parsed the one way: the bundle
     * path keys its own entries' claims by exactly this, so a reference and
     * the entry it points at agree on what the identity IS however the query
     * spells it.
     */
    cloud.jengu.dbo.core.api.Identifier identityOf(String typeName, String query) {
        java.util.Map<String, String> condition = conditionOf(typeName, query);
        java.util.Map.Entry<String, String> only = condition.entrySet().iterator().next();
        cloud.jengu.dbo.core.api.Identifier identifier = switch (only.getKey()) {
            case "identifier" -> {
                int pipe = only.getValue().indexOf('|');
                if (pipe <= 0 || pipe == only.getValue().length() - 1) {
                    throw new IllegalArgumentException("a conditional reference's identifier "
                            + "must be system|value: " + only.getValue());
                }
                yield new cloud.jengu.dbo.core.api.Identifier(only.getValue().substring(0, pipe),
                        only.getValue().substring(pipe + 1));
            }
            case "url" -> new cloud.jengu.dbo.core.api.Identifier(
                    cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, only.getValue());
            default -> throw new IllegalArgumentException("a reference may ask by the identity "
                    + "its type is claimed under (identifier=, url=), not by '" + only.getKey()
                    + "' — a write whose meaning depends on general search means something "
                    + "different tomorrow");
        };
        return identifier;
    }

    /** The one condition a conditional reference may carry. */
    private static java.util.Map<String, String> conditionOf(String typeName, String query) {
        java.util.Map<String, String> condition = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                condition.put(
                        java.net.URLDecoder.decode(pair.substring(0, equals),
                                StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1),
                                StandardCharsets.UTF_8));
            }
        }
        if (condition.size() != 1) {
            throw new IllegalArgumentException("the reference '" + typeName + "?" + query
                    + "' must ask by exactly one identity condition, got: "
                    + condition.keySet());
        }
        return condition;
    }

    @Override
    public PutResult create(String resourceJson) {
        Accepted accepted = accepted(resourceJson);
        PutResult result = store.put(PutRequest.create(accepted.type(), accepted.payload()).stamped(accepted.shape()));
        rebuiltIfShapesMoved(accepted.type());
        return result;
    }

    @Override
    public PutResult update(String id, Long expectedVersion, String resourceJson) {
        Accepted accepted = accepted(resourceJson);
        PutResult result = store.put(
                new PutRequest(accepted.type(), id, expectedVersion, accepted.payload())
                        .stamped(accepted.shape()));
        rebuiltIfShapesMoved(accepted.type());
        return result;
    }

    /**
     * FHIR conditional create (If-None-Exist). Per
     * REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS the condition must be the type's
     * primary identity: a search that is not an identity would make the answer
     * depend on a race.
     */
    @Override
    public PutResult conditionalCreate(String resourceJson, Map<String, String> condition) {
        Accepted accepted = accepted(resourceJson);
        PutResult result = store.putIfAbsent(identityOf(condition),
                PutRequest.create(accepted.type(), accepted.payload()).stamped(accepted.shape()));
        // Like its three neighbours, and for the same reason: a shape that
        // lands here is a shape the view has to know about. This door is the
        // one that publishes a definition BY CANONICAL URL — the face's own
        // vocabulary and anything identity-keyed arrive through it — so it was
        // the likeliest of the four to carry a StructureDefinition and the
        // only one that said nothing.
        //
        // Conditioned on `created` where the others are unconditional, because
        // this is the only door that can decline to write: a condition that
        // matched changed nothing, and rebuilding a view over what it already
        // holds is work with no reader.
        if (result.created()) {
            rebuiltIfShapesMoved(accepted.type());
        }
        return result;
    }

    /**
     * Conditional update: absent it is created, present it is replaced.
     *
     * <p>R4 says several matches answer 412. Under the identity-only rule that
     * cannot arise: an identity resolves through a unique index, so a
     * condition either names one object or none. The branch is absent because
     * the state is, not because it is unhandled.
     */
    @Override
    public PutResult conditionalUpdate(String resourceJson, Map<String, String> condition) {
        Accepted accepted = accepted(resourceJson);
        PutResult result = store.putConditional(identityOf(condition),
                PutRequest.create(accepted.type(), accepted.payload()).stamped(accepted.shape()));
        rebuiltIfShapesMoved(accepted.type());
        return result;
    }

    /** The identity a conditional write is keyed on, and nothing else. */
    private static IdentityRef identityOf(Map<String, String> condition) {
        if (condition.size() != 1) {
            throw new IllegalArgumentException(
                    "a conditional write requires exactly one identity condition, got: "
                            + condition.keySet());
        }
        Map.Entry<String, String> only = condition.entrySet().iterator().next();
        return switch (only.getKey()) {
            case "identifier" -> {
                int pipe = only.getValue().indexOf('|');
                if (pipe <= 0 || pipe == only.getValue().length() - 1) {
                    throw new IllegalArgumentException(
                            "conditional identifier must be system|value: " + only.getValue());
                }
                yield IdentityRef.identifier(only.getValue().substring(0, pipe),
                        only.getValue().substring(pipe + 1));
            }
            case "url" -> IdentityRef.canonical(only.getValue());
            default -> throw new IllegalArgumentException(
                    "a conditional write accepts only identity conditions (identifier=, url=), got: "
                            + only.getKey());
        };
    }

    /**
     * Conditional upsert of a canonical artifact by its url.
     *
     * <p>Not on the facade: a canonical resource is one whose identity is its
     * {@code url}, and the caller that writes one — terminology ingest — knows
     * that about it. A caller that does not know cannot use this correctly.
     */
    public PutResult putCanonical(String resourceJson) {
        Accepted accepted = accepted(resourceJson);
        Object document = payloads.read(null, accepted.payload());
        String url = version.canonicalUrlOf(document);
        return store.putConditional(IdentityRef.canonical(url),
                PutRequest.create(accepted.type(), accepted.payload()).stamped(accepted.shape()));
    }

    @Override
    public void delete(String typeName, String id, Long expectedVersion) {
        store.delete(typeName, id, expectedVersion);
    }

    // ------------------------------------------------------------- reading

    /**
     * Rendered rather than served raw: the payload is the truth and carries no
     * id, so a direct read would hand back a resource the client cannot
     * reference — while the same object in a search hit has one.
     */
    @Override
    public String read(String typeName, String id) {
        return store.get(typeName, id).map(this::rendered).orElse(null);
    }

    @Override
    public ReadResult readForServing(String typeName, String id) {
        return store.get(typeName, id)
                .map(o -> new ReadResult(rendered(o), o.versionId(), o.lastUpdated()))
                .orElse(null);
    }

    /**
     * Refuses an object written under a shape newer than the pack carries
     * (REQ-DBO-SHAPE-NEWER-DATA-REFUSED).
     *
     * <p>At the SERVING seam rather than at ingress, and deliberately: the
     * accept path strips and re-stamps, so an authored write cannot carry a
     * newer stamp, while the paths that can — sync apply, restore — bypass
     * the face entirely and must not grow pack knowledge. Every arrival path
     * converges here, so one rule in one place covers all of them, including
     * the ones not yet invented.
     *
     * <p>Too-new data may therefore be STORED; it may not be read. That is
     * honest rather than lax: the stock is countable in the inventory and
     * cleared by upgrading the pack or converting it, and nothing silently
     * operates on it in the meantime.
     */
    private void refuseIfTooNew(StoredObject stored) {
        if (stored.shape() == null || !(((Object) payloads) instanceof ElementPayloads pack)) {
            return;
        }
        for (String entry : stored.shape()) {
            int bar = entry.lastIndexOf('|');
            if (bar <= 0) {
                continue;
            }
            String profile = entry.substring(0, bar);
            String stamped = entry.substring(bar + 1);
            String declared = pack.declaredVersionOf(profile);
            // A shape the pack no longer carries is not a conflict — the
            // stamp outlives its pack. Only a pack that declares an
            // OLDER major than the stamp is one.
            if (declared != null && majorOf(stamped) > majorOf(declared)) {
                throw new cloud.jengu.dbo.core.api.ShapeTooNewException(
                        stored.typeName(), stored.id(), profile, stamped, declared);
            }
        }
    }

    /** A version's leading major, or -1 when it has none to compare. */
    private static int majorOf(String version) {
        String major = version == null ? "" : version.split("\\.", 2)[0];
        return major.matches("[0-9]+") ? Integer.parseInt(major) : -1;
    }

    private String rendered(StoredObject stored) {
        refuseIfTooNew(stored);
        return new String(ElementAncestors.rendered(version.context(), stored.payload(),
                stored.id(), stored.versionId(), null, stampsFor(stored)), StandardCharsets.UTF_8);
    }

    /**
     * The engine's claims about this record, for {@code meta}: the
     * upstream a streamed copy came from, and the handling class the tenant
     * declared for its type — which is the classification a reader is being
     * governed by and was, until now, never told.
     */
    private ElementAncestors.Stamps stampsFor(StoredObject stored) {
        return new ElementAncestors.Stamps(
                ElementAncestors.sourceUri(stored.origin()),
                handlingWireFor(stored.typeName()),
                stored.shadowing() != null ? ElementAncestors.SHADOWS : null,
                stored.shape());
    }

    private String handlingWireFor(String typeName) {
        return types.stream()
                .filter(t -> t.typeName().equals(typeName))
                .findFirst()
                .map(t -> t.handling().wire())
                .orElse(null);
    }

    /**
     * A written profile takes effect at once, for this tenant only.
     *
     * <p>Rebuilt from the write rather than from a timer or a global refresh:
     * the tenant that changed its shapes is the tenant whose view moves, and
     * nobody else pays for it. The shared per-version context — seconds to
     * build — is untouched; what is rebuilt is the copy over it, which costs
     * about a tenth of a second.
     *
     * <p>A profile the tenant just wrote and cannot be snapshotted leaves the
     * view as it was and says so, rather than dropping the tenant's whole
     * validation on the floor over one bad document.
     */
    @SuppressWarnings("unchecked")
    /**
     * Rebuild because somebody else wrote a profile here.
     *
     * <p>Unconditional where {@link #rebuiltIfShapesMoved} is conditional: the
     * caller has already established that a StructureDefinition moved, and it
     * knows things this facade cannot see — a sync lane's write, a restore.
     */
    @Override
    public void shapesChanged() {
        rebuiltIfShapesMoved("StructureDefinition");
    }

    /**
     * The type a tenant authors its own search parameters as.
     *
     * <p>An ordinary canonical resource. A tenant that does not serve it has
     * authored none and never can, so the whole of this is skipped for it —
     * which is nearly every tenant.
     */
    private static final String SEARCH_PARAMETER = "SearchParameter";

    /**
     * A tenant has authored, changed or withdrawn a search parameter.
     *
     * <p>What happens is the whole of REQ-DBO-SRCH-CUSTOM-PARAMETERS: the
     * affected types are re-registered so extraction knows the new expression,
     * every existing row is rebuilt so the answer covers the tenant's history
     * rather than only what arrived since, the declared index is created on the
     * way through, and only then does the parameter start being advertised and
     * accepted.
     *
     * <p>That order is the honest one and it is not free — a large type is a
     * real reindex, which is why this is called from a round that can report
     * how long it took rather than from the write that caused it. The
     * alternative sequencing advertises a parameter that answers about a
     * fraction of the data, and a search that quietly omits most of the
     * matches is worse than one that refuses.
     *
     * <p>Unconditional, like {@link #shapesChanged()}: the caller has already
     * established that a SearchParameter moved, and it can see paths this
     * facade cannot — a lane's write, a restore, a zone's replication.
     *
     * @return how many objects were reindexed, across every affected type
     */
    @Override
    public int searchParametersChanged() {
        if (types.stream().noneMatch(t -> SEARCH_PARAMETER.equals(t.typeName()))) {
            return 0;
        }
        Map<String, List<SearchParameter>> authored = new java.util.LinkedHashMap<>();
        for (StoredObject stored : store.select(
                cloud.jengu.dbo.core.api.Criteria.of(SEARCH_PARAMETER))) {
            SearchParameter parameter = readParameter(stored);
            if (parameter == null) {
                continue;
            }
            for (String base : basesOf(stored)) {
                if (types.stream().anyMatch(t -> t.typeName().equals(base))) {
                    authored.computeIfAbsent(base, ignored -> new ArrayList<>()).add(parameter);
                }
            }
        }
        int rebuilt = 0;
        // Every type this tenant serves, not only the ones with parameters
        // now: a withdrawn parameter has to take its extraction with it, and a
        // type that dropped to none is exactly the case a loop over the new
        // map would skip.
        Set<String> touched = new java.util.LinkedHashSet<>(authored.keySet());
        touched.addAll(authoredHere.keySet());
        for (String typeName : touched) {
            List<SearchParameter> mine = authored.getOrDefault(typeName, List.of());
            if (codesOf(mine).equals(codesOf(authoredHere.getOrDefault(typeName, List.of())))) {
                continue; // this type's set is what it already was
            }
            cloud.jengu.dbo.core.api.TypeRegistration current = store.registrationOf(typeName);
            rebuilt += store.reindexUnder(new cloud.jengu.dbo.core.api.TypeRegistration(
                    current.typeName(), current.domain(), current.identityClass(),
                    current.identitySystems(), current.handling(),
                    version.extractor(typeName,
                            current.identityClass()
                                    == cloud.jengu.dbo.core.api.IdentityClass.CANONICAL,
                            mine),
                    ElementVersion.indexesFor(ElementVersion.union(
                            version.parametersFor(typeName), mine)),
                    current.payloadVersion()));
            LOG.info("tenant search parameters changed: type={} authored={} reindexed={}",
                    typeName, codesOf(mine), rebuilt);
        }
        // Published last: until here the parameter is neither advertised nor
        // accepted, which is the only state in which the statement and the
        // surface agree.
        authoredHere = Map.copyOf(authored);
        return rebuilt;
    }

    private static Set<String> codesOf(List<SearchParameter> parameters) {
        Set<String> codes = new java.util.TreeSet<>();
        parameters.forEach(p -> codes.add(p.getCode() + "=" + p.getExpression()));
        return codes;
    }

    /** The types a stored parameter says it is about. */
    private List<String> basesOf(StoredObject stored) {
        return Json.strings(Json.parse(new String(stored.payload(), StandardCharsets.UTF_8)),
                "base");
    }

    /**
     * One stored parameter as the extraction engine needs it.
     *
     * <p>Read off the JSON rather than through the element model, because the
     * fields that matter — code, type, expression — are spelled the same in
     * every version this store serves, while the typed model is R5's. A
     * conversion here would be a whole converter chain to read four strings.
     *
     * <p>Null for one this store cannot use, said out loud. The door refuses an
     * unevaluable expression, but a parameter can arrive without passing a
     * door: replicated from a zone, restored from an archive, applied by a
     * lane. Dropping it silently would leave a tenant holding a definition
     * that does nothing, with nothing anywhere saying why.
     */
    private SearchParameter readParameter(StoredObject stored) {
        Object node = Json.parse(new String(stored.payload(), StandardCharsets.UTF_8));
        String code = Json.str(node, "code");
        String expression = Json.str(node, "expression");
        String type = Json.str(node, "type");
        if (code == null || code.startsWith("_") || type == null) {
            LOG.warn("a stored SearchParameter names no usable code or type and is not in "
                    + "force: id={} code={} type={}", stored.id(), code, type);
            return null;
        }
        java.util.Optional<String> why = version.whyNotEvaluable(expression);
        if (why.isPresent()) {
            LOG.warn("a stored SearchParameter is not evaluable and is not in force: "
                    + "id={} code={} reason={}", stored.id(), code, why.get());
            return null;
        }
        org.hl7.fhir.r5.model.Enumerations.SearchParamType kind;
        try {
            kind = org.hl7.fhir.r5.model.Enumerations.SearchParamType.fromCode(type);
        } catch (Exception unknownType) {
            LOG.warn("a stored SearchParameter declares an unknown type and is not in force: "
                    + "id={} code={} type={}", stored.id(), code, type);
            return null;
        }
        SearchParameter parameter = new SearchParameter();
        parameter.setCode(code);
        parameter.setExpression(expression);
        parameter.setType(kind);
        return parameter;
    }

    /** A converter arriving out of band — a sync lane's write, a restore. */
    public void convertersChanged() {
        rebuiltIfShapesMoved("StructureMap");
    }

    private void rebuiltIfShapesMoved(String typeName) {
        // A converter moving matters exactly as much as a shape moving: a map
        // written into the pack must take effect without a restart, or a
        // reshape answers "no converter" about one the tenant is holding
        // (found by the test, not by review).
        if (terms == null
                || !("StructureDefinition".equals(typeName) || "StructureMap".equals(typeName))) {
            return;
        }
        try {
            List<String> profiles = storedProfiles(store);
            payloads = (Payloads<Object>) (Payloads<?>)
                    version.payloadsFor(terms, profiles, storedMaps(store));
            shapesInView = canonicalsOf(profiles);
        } catch (RuntimeException e) {
            throw new cloud.jengu.dbo.fhir.common.ValidationFailedException("StructureDefinition",
                    List.of("the profile was stored, and this tenant's validation still uses "
                            + "the shapes it had: " + e.getMessage()));
        }
    }

    /**
     * The tenant's own StructureDefinitions, read once at construction.
     *
     * <p>Read HERE rather than by the face: a face capability is a pure
     * transformation and never reaches the store, so what a tenant defined
     * arrives as data the facade fetched. The facade may — it is the thing
     * that acts.
     *
     * <p>A tenant with no StructureDefinition type declared has none, which
     * is an ordinary answer: it validates against the carried pack alone,
     * exactly as before.
     */
    @Override
    public java.util.Optional<cloud.jengu.dbo.core.face.ShapeConversion> shapeConversion() {
        Object view = payloads;
        return view instanceof ElementPayloads tenant
                ? java.util.Optional.of(new ElementShapeConversion(tenant))
                : java.util.Optional.empty();
    }

    /** The tenant's own converters, alongside its own profiles. */
    private static List<String> storedMaps(ObjectStore engine) {
        try {
            return engine.select(cloud.jengu.dbo.core.api.Criteria.of("StructureMap")
                            .limit(500)).stream()
                    .map(stored -> new String(stored.payload(), StandardCharsets.UTF_8))
                    .toList();
        } catch (RuntimeException e) {
            // A tenant that does not register StructureMap has no converters,
            // which is a shape of tenant, not a broken one.
            return List.of();
        }
    }

    /**
     * A document claiming a profile this tenant holds but has not loaded gets
     * the profile loaded, once, before it is judged.
     *
     * <p>The validation view is built from what the store held when it was
     * built, and a shape can arrive afterwards by a path the facade never
     * served — replicated from a zone, restored from an archive, applied by a
     * lane. The runtime watches for that and rebuilds, which is the right
     * place for it and is not the only place it can be needed: a watch reads a
     * feed, and whether every arrival reaches that feed is a property of every
     * writer rather than of this store. So the claim itself is also an
     * occasion — the one moment where being wrong about it is visible to
     * somebody, as a refusal naming a profile they can see in the store.
     *
     * <p>It costs one indexed lookup, and only for a claim the view cannot
     * already answer: a claim on a shape nobody here has is a lookup and the
     * refusal it always got.
     */
    private void loadClaimedShapesThisTenantHolds(Object document) {
        if (terms == null
                || !(document instanceof org.hl7.fhir.r5.elementmodel.Element element)) {
            return;
        }
        for (org.hl7.fhir.r5.elementmodel.Element meta : element.getChildrenByName("meta")) {
            for (org.hl7.fhir.r5.elementmodel.Element claimed
                    : meta.getChildrenByName("profile")) {
                String url = claimed.primitiveValue();
                if (url == null || url.isBlank() || shapesInView.contains(url)) {
                    continue;
                }
                boolean held = !store.getByIdentifier("StructureDefinition",
                        List.of(new cloud.jengu.dbo.core.api.Identifier(
                                cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url)))
                        .isEmpty();
                if (held) {
                    // One rebuild answers every claim in this document, and a
                    // second claim on the same pass would find it loaded.
                    rebuiltIfShapesMoved("StructureDefinition");
                    return;
                }
            }
        }
    }

    /**
     * The canonical url of each stored profile, read from the payload the way
     * the rest of this store reads a declared field — enough to know which
     * shapes the view was built from, without parsing them a second time.
     */
    private static java.util.Set<String> canonicalsOf(List<String> profiles) {
        java.util.Set<String> urls = new java.util.HashSet<>();
        for (String profile : profiles) {
            int at = profile.indexOf("\"url\"");
            if (at < 0) {
                continue;
            }
            int from = profile.indexOf('"', profile.indexOf(':', at)) + 1;
            int to = from <= 0 ? -1 : profile.indexOf('"', from);
            if (from > 0 && to > from) {
                urls.add(profile.substring(from, to));
            }
        }
        return java.util.Set.copyOf(urls);
    }

    private static List<String> storedProfiles(ObjectStore engine) {
        try {
            return engine.select(cloud.jengu.dbo.core.api.Criteria.of("StructureDefinition")
                            .limit(500)).stream()
                    .map(stored -> new String(stored.payload(), StandardCharsets.UTF_8))
                    .toList();
        } catch (RuntimeException e) {
            // A tenant that does not register StructureDefinition is not a
            // tenant whose bring-up should fail over profiles it never had.
            return List.of();
        }
    }

    /** The engine, for the one caller that writes several entries as one unit. */
    cloud.jengu.dbo.core.api.ObjectStore engine() {
        return store;
    }

    @Override
    public String bundle(String bundleJson) {
        Object document = payloads.read(null, bundleJson.getBytes(StandardCharsets.UTF_8));
        return new ElementBundles(this, version.context())
                .process((org.hl7.fhir.r5.elementmodel.Element) document);
    }

    @Override
    public String historyBundle(String typeName, String id) {
        List<StoredObject> history = store.history(typeName, id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write("history", new PayloadFraming.Facts((long) history.size(),
                baseUrl + "/" + typeName + "/" + id + "/_history", null),
                history, null, null, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------- searching

    @Override
    public String search(String typeName, Map<String, String> params, String cursor) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            search(typeName, params, cursor, out);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write a page into memory", e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * A page is written as it is produced: the frame first, then each
     * member as the cursor reaches it. Memory is one member rather than one
     * page, and a reader sees the first byte before the last row is read.
     */
    @Override
    public void search(String typeName, Map<String, String> params, String cursor,
            OutputStream out) throws IOException {
        ElementSearch.Compiled compiled = ElementSearch.compile(inForce(), typeName, params);

        if (compiled.byId() != null) {
            List<StoredObject> hit = store.get(typeName, compiled.byId())
                    .map(List::of).orElse(List.of());
            write("searchset", new PayloadFraming.Facts((long) hit.size(), selfUrl(typeName, params),
                    null), hit, null, compiled.elements(), out);
            return;
        }
        if (compiled.countOnly()) {
            out.write(("{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":"
                    + store.count(compiled.criteria()) + "}").getBytes(StandardCharsets.UTF_8));
            return;
        }

        FeedChunk<StoredObject> chunk = store.page(compiled.criteria(), cursor);
        List<StoredObject> included = included(compiled, chunk);
        write("searchset", new PayloadFraming.Facts(null, selfUrl(typeName, params),
                nextUrl(typeName, params, chunk)), chunk.items(), included, compiled.elements(),
                out);
    }

    /**
     * The objects an {@code _include} asks for, gathered before the page is
     * written.
     *
     * <p>They cannot stream: an included object is found by following a
     * reference out of a member, so the members have to have been read. It is
     * bounded by the page, and only when the caller asked for it.
     */
    private List<StoredObject> included(ElementSearch.Compiled compiled,
            FeedChunk<StoredObject> chunk) {
        if (compiled.includeRefParams().isEmpty()) {
            return null;
        }
        List<StoredObject> included = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StoredObject item : chunk.items()) {
            Object document = payloads.read(null, item.payload());
            for (String refParam : compiled.includeRefParams()) {
                for (String[] target : version.referencedTargets(document, item.typeName(), refParam)) {
                    if (seen.add(target[0] + "/" + target[1])) {
                        store.get(target[0], target[1]).ifPresent(included::add);
                    }
                }
            }
        }
        return included;
    }

    private void write(String frameType, PayloadFraming.Facts facts, List<StoredObject> members,
            List<StoredObject> included, List<String> elements, OutputStream out) {
        try {
            // BEFORE a byte is written: a document is streamed, so a refusal
            // discovered mid-stream cannot become a status code — it would
            // arrive as a 200 with a truncated body, which is precisely the
            // half-answer this refusal exists to prevent (found by the test).
            for (StoredObject member : members) {
                refuseIfTooNew(member);
            }
            if (included != null) {
                for (StoredObject member : included) {
                    refuseIfTooNew(member);
                }
            }
            PayloadFraming.Frame frame = framing.frame(frameType, facts);
            out.write(frame.prologue());
            boolean first = true;
            for (StoredObject member : members) {
                if (!first) {
                    out.write(frame.separator());
                }
                first = false;
                framing.member(member(member, PayloadFraming.Member.MATCHED, elements), out);
            }
            if (included != null) {
                for (StoredObject member : included) {
                    if (!first) {
                        out.write(frame.separator());
                    }
                    first = false;
                    framing.member(member(member, PayloadFraming.Member.INCLUDED, elements), out);
                }
            }
            out.write(frame.epilogue());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write a document to the reader", e);
        }
    }

    private PayloadFraming.Member member(StoredObject stored, String role,
            List<String> elements) {
        // A search whose answer would contain a too-new object is refused
        // WHOLE, naming it. Quietly omitting it is the failure this store
        // refuses everywhere: a short answer looks like an answer.
        refuseIfTooNew(stored);
        ElementAncestors.Stamps stamps = stampsFor(stored);
        return new PayloadFraming.Member(stored.typeName(), stored.id(), stored.versionId(),
                stored.payload(), baseUrl + "/" + stored.typeName() + "/" + stored.id(), role,
                elements, stamps.source(), stamps.handling(), stamps.tag(), stamps.shape());
    }

    private String selfUrl(String typeName, Map<String, String> params) {
        return baseUrl + "/" + typeName + query(params);
    }

    private String nextUrl(String typeName, Map<String, String> params,
            FeedChunk<StoredObject> chunk) {
        if (chunk.drained() || chunk.nextCursor() == null) {
            // Drained matters as much as the cursor: a page that emptied the
            // selection still has a position after its last row, and offering
            // it sends a reader round again for nothing.
            return null;
        }
        String query = query(params);
        return baseUrl + "/" + typeName + (query.isEmpty() ? "?" : query + "&")
                + "_cursor=" + chunk.nextCursor();
    }

    private static String query(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder("?");
        params.forEach((name, value) -> {
            if (query.length() > 1) {
                query.append('&');
            }
            query.append(name).append('=').append(value);
        });
        return query.toString();
    }

    // ------------------------------------------------------------- telling

    /**
     * What this store answers: {@code $validate}, on every type it
     * serves, on every version this face serves.
     *
     * <p>It lives here rather than in each personality because the face is one
     * implementation for all three versions — and because a store that declared
     * no operations was how {@code $validate} came to be announced by two
     * personalities and reachable through neither: the runtime builds this
     * store, and this store returned an empty list.
     */
    @Override
    public List<FhirOperation> operations() {
        return List.of(new FhirOperation() {
            @Override
            public String name() {
                return "validate";
            }

            @Override
            public String definition() {
                return "http://hl7.org/fhir/OperationDefinition/Resource-validate";
            }

            @Override
            public java.util.Set<String> types() {
                return types.stream().map(FhirTypeConfig::typeName)
                        .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            }

            @Override
            public Answer answer(String typeName, Map<String, String> query, String body) {
                String mode = query.getOrDefault("mode", "create");
                if (!"create".equals(mode) && !"update".equals(mode)) {
                    return Answer.status(400, operationOutcome("invalid",
                            "unsupported $validate mode: " + mode));
                }
                // 200 whatever the verdict: a caller who asked correctly did
                // not make a bad request, and the outcome carries the answer.
                return Answer.ok(validationOutcome(body, query.get("profile")));
            }
        });
    }

    @Override
    public String validationOutcome(String resourceJson) {
        Object document = payloads.read(null, resourceJson.getBytes(StandardCharsets.UTF_8));
        // Everything the face has to say, not only what would refuse the write
        //: a caller asking whether this is acceptable is also asking what
        // is questionable about it.
        return ElementOutcomes.issues(payloads.check(payloads.typeOf(document), document, null),
                null);
    }

    /**
     * Against a named shape: a canonical this face carries, or a declared
     * step's input shape.
     *
     * <p>A step id is accepted where a profile is expected because that is the
     * question a caller actually has — <em>would this be accepted as the input
     * to this step</em> — and making them look the canonical up first would be
     * asking them to know something the catalogue already knows.
     *
     * <p><b>The refusal names the profile.</b> Told only "invalid" against an
     * unnamed shape, a caller cannot tell whether they used the wrong shape or
     * the wrong data, and those have different fixes.
     */
    @Override
    public String validationOutcome(String resourceJson, String profile) {
        if (profile == null || profile.isBlank()) {
            return validationOutcome(resourceJson);
        }
        String shape = profile;
        if (profile.matches("[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*")) {
            java.util.Optional<cloud.jengu.dbo.core.process.StepDeclaration> step =
                    steps.byId(profile);
            if (step.isEmpty()) {
                return ElementOutcomes.outcome("not-found",
                        "no step '" + profile + "' is declared here, so there is no shape to "
                                + "validate against");
            }
            if (step.get().consumes().isEmpty()) {
                return ElementOutcomes.outcome("not-supported",
                        "step '" + profile + "' declares no input shape, so there is nothing "
                                + "for a resource to conform to");
            }
            shape = step.get().consumes().get();
        }
        Object document = payloads.read(null, resourceJson.getBytes(StandardCharsets.UTF_8));
        return ElementOutcomes.issues(payloads.check(payloads.typeOf(document), document, shape),
                shape);
    }

    @Override
    public String operationOutcome(String issueCode, String diagnostics) {
        return ElementOutcomes.outcome(issueCode, diagnostics);
    }

    @Override
    public String internalFault(String diagnostics) {
        return ElementOutcomes.fault(diagnostics);
    }

    @Override
    public String capabilityStatement(String base) {
        return capabilityStatement(base, List.of());
    }

    @Override
    public String capabilityStatement(String base, Collection<FhirOperation> served) {
        return capabilityStatement(base, served, java.util.Map.of());
    }

    @Override
    public String capabilityStatement(String base, Collection<FhirOperation> served,
            java.util.Map<String, java.util.Set<String>> narrowedSearch) {
        return ElementCapability.statement(version, inForce(), types, base, served, narrowedSearch);
    }

    @Override
    public boolean knowsType(String typeName) {
        return types.stream().anyMatch(t -> t.typeName().equals(typeName));
    }
}
