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
