package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.rest.WorkSurface;

import cloud.jengu.dbo.core.api.ObjectStore;
import cloud.jengu.dbo.core.api.StoredObject;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.RecordProjection;
import cloud.jengu.dbo.core.process.StepDeclaration;
import cloud.jengu.dbo.core.process.Steps;
import cloud.jengu.dbo.work.Run;
import cloud.jengu.dbo.work.RunKind;
import cloud.jengu.dbo.work.Runs;
import cloud.jengu.dbo.work.WorkModel;

import java.util.List;
import java.util.Optional;

/**
 * Runs behind the surface: what a posted document authors, and how a run is
 * shown. The face reads the document and renders the run; this decides
 * nothing about either shape, only that a run is minted through the same
 * door every other run is, so every rule a run obeys already holds.
 */
/*
 * Beside the tenant rather than in the REST bundle: the surface bundle knows
 * documents and not runs, and a run is the work bundle's; the tenant is what
 * holds both.
 */
final class WorkProjection implements WorkSurface {

    /**
     * What the run is called on the surface, which is not what it is called in
     * the store. WorkModel.TYPE is "Run" — the store's own word — and a link a
     * client is meant to follow has to say the word the surface answers to.
     */
    private static final String SURFACE_TYPE = "Task";

    private final ObjectStore store;
    private final Runs runs;
    private final Steps steps;
    private final DomainFace face;

    WorkProjection(ObjectStore store, Runs runs, Steps steps, DomainFace face) {
        this.store = store;
        this.runs = runs;
        this.steps = steps;
        this.face = face;
    }

    /** The result parameters a search of runs takes beside its narrowings. */
    private static final java.util.Set<String> RESULT_PARAMETERS =
            java.util.Set.of("_count", "_summary", "_sort", "_cursor");

    /** The system a run's correlation is rendered under. */
    private static final String CORRELATION_SYSTEM = "urn:dbo:correlation";

    /** A key no run has: what a part-of naming no run narrows to. */
    private static final String NO_RUN = "";

    /** The same question, from where this page ended. */
    private static String nextQuery(java.util.Map<String, String> query, String cursor) {
        StringBuilder next = new StringBuilder();
        query.forEach((name, value) -> {
            if (!"_cursor".equals(name)) {
                next.append(next.isEmpty() ? "" : "&").append(encoded(name)).append('=')
                        .append(encoded(value));
            }
        });
        return next.append(next.isEmpty() ? "" : "&").append("_cursor=").append(encoded(cursor))
                .toString();
    }

    private static String encoded(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Optional<String> read(String id) {
        return runs.byId(id).map(this::rendered);
    }

    /**
     * What is outstanding, whose it is, what step it is of — asked of the
     * tenant rather than read one run at a time.
     *
     * <p>The narrowings are the ones the store already answers about a run: its
     * status, who may take it, its step and the key it was correlated under
     * are envelope facts, and everything else a run's document carries belongs
     * to the step that wrote it.
     */
    @Override
    public String search(java.util.Map<String, String> query, String baseUrl) {
        // Refused, never ignored. A parameter quietly dropped answers 200 with
        // every run in the tenant, which a caller cannot tell from the answer
        // they asked for (REQ-DBO-SRCH-HONEST-CAPABILITY).
        for (String parameter : query.keySet()) {
            if (!searchParameters().contains(parameter) && !"part-of:missing".equals(parameter)
                    && !RESULT_PARAMETERS.contains(parameter)) {
                throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                        WorkModel.TYPE, parameter);
            }
        }
        boolean countOnly = false;
        if (query.get("_summary") != null) {
            if (!"count".equals(query.get("_summary"))) {
                throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                        WorkModel.TYPE, "_summary=" + query.get("_summary"));
            }
            countOnly = true;
        }
        cloud.jengu.dbo.core.api.Criteria criteria =
                cloud.jengu.dbo.core.api.Criteria.of(WorkModel.TYPE)
                        .limit(cloud.jengu.dbo.fhir.common.ResultParameters
                                .count(query.get("_count"), WorkModel.TYPE, 100, 10_000));
        if (query.get("status") != null) {
            // Any of them, as owner is: "still owed" is three statuses.
            criteria.anyOf("status", java.util.Arrays.stream(query.get("status").split(","))
                    .map(String::trim).filter(one -> !one.isEmpty())
                    .map(cloud.jengu.dbo.core.api.EnvelopeValue::of).toList());
        }
        if (query.get("performer-type") != null) {
            // Who may perform it, as the widest kind it admits: a person may
            // take every open task, so "person" asks for the tasks open to
            // people alone — the list an operator opens — and "automation"
            // for those a machine may take as well.
            criteria.eq("eligible",
                    cloud.jengu.dbo.core.api.EnvelopeValue.of(query.get("performer-type")));
        }
        if (query.get("code") != null) {
            criteria.eq("step", cloud.jengu.dbo.core.api.EnvelopeValue.of(query.get("code")));
        }
        if (query.get("identifier") != null) {
            // A run is identified twice: by its own key, and by the case it is
            // filed under. Named with the run's system it is the key; bare, or
            // named with the correlation's, the case — as it always was.
            String identifier = query.get("identifier");
            if (identifier.startsWith(WorkModel.KEY_SYSTEM + "|")) {
                criteria.eq("key", cloud.jengu.dbo.core.api.EnvelopeValue.of(
                        identifier.substring(WorkModel.KEY_SYSTEM.length() + 1)));
            } else {
                criteria.eq("correlation", cloud.jengu.dbo.core.api.EnvelopeValue.of(
                        identifier.startsWith(CORRELATION_SYSTEM + "|")
                                ? identifier.substring(CORRELATION_SYSTEM.length() + 1)
                                : identifier));
            }
        }
        if (query.get("part-of") != null) {
            // Named as a Task is, by id; a run's parent is kept as its key,
            // so the id is read once into the key. A Task that is no run has
            // no parts, and is answered as none rather than refused.
            String named = query.get("part-of");
            String id = named.startsWith(SURFACE_TYPE + "/")
                    ? named.substring(SURFACE_TYPE.length() + 1) : named;
            criteria.eq("parent", cloud.jengu.dbo.core.api.EnvelopeValue.of(
                    runs.byId(id).map(Run::key).orElse(NO_RUN)));
        }
        if (query.get("part-of:missing") != null) {
            String missing = query.get("part-of:missing");
            if (!"true".equals(missing) && !"false".equals(missing)) {
                throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                        WorkModel.TYPE, "part-of:missing=" + missing);
            }
            criteria.missing("parent", Boolean.parseBoolean(missing));
        }
        if (query.get("_sort") != null) {
            // The one order a screen of work asks for: when each run last
            // moved. Anything else would be an order this surface does not
            // keep, and answering in some other one would look like it did.
            switch (query.get("_sort")) {
                case "_lastUpdated" -> criteria.sortByLastUpdated(true);
                case "-_lastUpdated" -> criteria.sortByLastUpdated(false);
                default -> throw new cloud.jengu.dbo.fhir.common.UnknownSearchParameterException(
                        WorkModel.TYPE, "_sort=" + query.get("_sort"));
            }
        }

        long total = store.count(criteria);
        // A page, and where the next one starts: a search that stopped at its
        // count without saying so read as every run there was.
        cloud.jengu.dbo.core.api.feed.FeedChunk<StoredObject> page = countOnly ? null
                : store.page(criteria, query.get("_cursor"));
        List<StoredObject> found = page == null ? List.of() : page.items();
        String next = page == null || page.drained() || page.nextCursor() == null ? null
                : baseUrl + "/" + SURFACE_TYPE + "?" + nextQuery(query, page.nextCursor());
        cloud.jengu.dbo.core.face.PayloadFraming framing =
                face.require(cloud.jengu.dbo.core.face.PayloadFraming.class);
        cloud.jengu.dbo.core.face.PayloadFraming.Frame frame = framing.frame("searchset",
                new cloud.jengu.dbo.core.face.PayloadFraming.Facts(
                        total, baseUrl + "/" + SURFACE_TYPE, next));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(512);
        try {
            out.write(frame.prologue());
            for (int i = 0; i < found.size(); i++) {
                if (i > 0) {
                    out.write(frame.separator());
                }
                StoredObject one = found.get(i);
                framing.member(new cloud.jengu.dbo.core.face.PayloadFraming.Member(
                        SURFACE_TYPE, one.id(), one.versionId(),
                        rendered(Run.of(one)).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        baseUrl + "/" + SURFACE_TYPE + "/" + one.id(),
                        cloud.jengu.dbo.core.face.PayloadFraming.Member.MATCHED), out);
            }
            out.write(frame.epilogue());
        } catch (java.io.IOException cannotWrite) {
            throw new java.io.UncheckedIOException("a page of runs could not be written",
                    cannotWrite);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Authored create(String document) {
        RecordProjection projection = face.require(RecordProjection.class);
        RecordProjection.PostedRun posted = projection.readPostedRun(document).orElseThrow(
                () -> new Refused(422, "this face renders runs as another document, and the "
                        + "one posted is not it"));
        if (posted.stepId() == null) {
            throw new Refused(422, "a run is of a declared step, and the document names none");
        }
        StepDeclaration step = steps.byId(posted.stepId()).orElseThrow(() -> new Refused(422,
                "no step '" + posted.stepId() + "' is declared here; declared: " + steps.ids()));
        if (posted.scope() == null || posted.scope().isBlank()) {
            throw new Refused(422, "a run has a name of its own under its step, and the "
                    + "document gives none");
        }
        String key = step.id() + "/" + posted.scope();
        if (runs.byKey(key).isPresent()) {
            // Found rather than started is right for a sweep; an author
            // posting a name already used is told, because a second posting
            // that quietly became the first would double whatever it counted.
            throw new Refused(409, "a run '" + key + "' already exists");
        }
        Run run;
        try {
            run = runs.of(step, RunKind.PIPELINE, posted.scope(), posted.inputs());
        } catch (IllegalArgumentException refused) {
            throw new Refused(422, refused.getMessage());
        }
        return new Authored(run.id(), run.versionId(), rendered(run));
    }

    /**
     * The run and its items as the face renders them. Domains are left off
     * the record on purpose: the face declines runs of other domains in a
     * listing, and a run addressed through this surface by the document it
     * was authored as is rendered whatever its step writes.
     */
    private String rendered(Run run) {
        RecordProjection.Record whole = runs.asRecord(run);
        return face.require(RecordProjection.class)
                .project(new RecordProjection.Record(whole.typeName(), whole.id(),
                        whole.versionId(), whole.payload(), whole.children(), List.of()))
                .orElseThrow(() -> new IllegalStateException(
                        "face '" + face.name() + "' renders no " + WorkModel.TYPE));
    }
}
