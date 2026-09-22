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

    @Override
    public Optional<String> read(String id) {
        return runs.byId(id).map(this::rendered);
    }

    /**
     * What is outstanding, whose it is, what step it is of — asked of the
     * tenant rather than read one run at a time.
     *
     * <p>The narrowings are the ones the store already answers about a run,
     * which is why they are these three: a run's holder, its step and the key
     * it was correlated under are envelope facts, and everything else a run's
     * document carries belongs to the step that wrote it.
     */
    @Override
    public String search(java.util.Map<String, String> query, String baseUrl) {
        // Refused, never ignored. A parameter quietly dropped answers 200 with
        // every run in the tenant, which a caller cannot tell from the answer
        // they asked for (REQ-DBO-SRCH-HONEST-CAPABILITY).
        for (String parameter : query.keySet()) {
            if (!searchParameters().contains(parameter)
                    && !"_count".equals(parameter) && !"_summary".equals(parameter)) {
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
        if (query.get("owner") != null) {
            // A comma is any of them, which is how "everything still owed by
            // somebody" is said without a negation the surface does not take.
            criteria.anyOf("holder", java.util.Arrays.stream(query.get("owner").split(","))
                    .map(String::trim).filter(one -> !one.isEmpty())
                    .map(cloud.jengu.dbo.core.api.EnvelopeValue::of).toList());
        }
        if (query.get("code") != null) {
            criteria.eq("step", cloud.jengu.dbo.core.api.EnvelopeValue.of(query.get("code")));
        }
        if (query.get("identifier") != null) {
            criteria.eq("correlation",
                    cloud.jengu.dbo.core.api.EnvelopeValue.of(query.get("identifier")));
        }

        long total = store.count(criteria);
        List<StoredObject> found = countOnly ? List.of() : store.select(criteria);
        cloud.jengu.dbo.core.face.PayloadFraming framing =
                face.require(cloud.jengu.dbo.core.face.PayloadFraming.class);
        cloud.jengu.dbo.core.face.PayloadFraming.Frame frame = framing.frame("searchset",
                new cloud.jengu.dbo.core.face.PayloadFraming.Facts(
                        total, baseUrl + "/" + SURFACE_TYPE, null));
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
