package cloud.jengu.dbo.fhir.validate;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.Finding;
import cloud.jengu.dbo.fhir.index.BoundCodes;
import cloud.jengu.dbo.fhir.index.DefinitionIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A face's payloads, read and judged against the definition index.
 *
 * <p>What item 025 has been building towards: everything the write path asks
 * of a document, answered without a worker context. The toolchain's reader
 * parses a document into an {@code elementmodel.Element}, and that needs a
 * version's whole definition corpus in heap whether or not anything
 * validates — 225 MB for a face's first tenant. This reads the same document
 * as a tree and asks the index instead.
 *
 * <p><b>It is the same contract, not a smaller one.</b> Reading, the type,
 * the checks, writing back, and the shape stamp a document was accepted
 * under: a face that answered four of the five would be a face that loses
 * something quietly, which is what every defect this item found looked like.
 *
 * <p><b>What it does not do is decide anything the index cannot see.</b> A
 * rule the reader cannot run, a value set the tenant does not hold, a
 * parameter whose path it cannot evaluate — each is reported by nobody rather
 * than guessed at, which is the rule {@code dbo.validate} already follows and
 * the reason the two agree.
 */
public final class IndexPayloads implements Payloads<IndexPayloads.Document> {

    private static final String PREFIX = "http://hl7.org/fhir/StructureDefinition/";

    /**
     * One document, as the tree it was read into.
     *
     * <p>The tree and not the bytes, because what is written back has to
     * reflect whatever the store did to it — and because holding both would
     * be two answers to what the document is.
     */
    public record Document(Map<String, Object> tree) {
    }

    private final DefinitionIndex index;
    private final BoundCodes codes;

    /**
     * @param codes the codes a required binding is decided against, or null
     *              where a deployment leaves terminology to the database —
     *              which gets every check but that one rather than a broken
     *              one
     */
    public IndexPayloads(DefinitionIndex index, BoundCodes codes) {
        this.index = index;
        this.codes = codes;
    }

    @Override
    public Document read(String typeName, byte[] payload) {
        Map<String, Object> tree = JsonDocument.of(payload);
        if (tree == null) {
            // As the element reader does, and for the same reason: the bytes
            // are in memory, so answering a malformed body with anything but
            // a bad-request tells a caller the server broke when their
            // request did.
            throw new IllegalArgumentException("body is not parseable FHIR JSON");
        }
        return new Document(tree);
    }

    @Override
    public String typeOf(Document document) {
        Object type = document.tree().get("resourceType");
        return type instanceof String named ? named : null;
    }

    @Override
    public byte[] write(Document document) {
        return JsonDocument.compose(document.tree());
    }

    @Override
    public List<String> validate(String typeName, Document document) {
        List<String> refusals = new ArrayList<>();
        for (Issue issue : check(typeName, document, null)) {
            if (issue.refuses()) {
                refusals.add(issue.message());
            }
        }
        return refusals;
    }

    /**
     * Everything the index had to say, at every severity.
     *
     * <p>All of it, and not the refusals alone: an extensible binding
     * violated is advice and a preferred one a suggestion, and reporting
     * those as errors would be worse than reporting none while discarding
     * them teaches a caller nothing. The write path still refuses on errors.
     */
    @Override
    public List<Issue> check(String typeName, Document document, String shapeReference) {
        String canonical = shapeReference != null ? shapeReference : PREFIX + typeName;
        if (!index.holds(canonical)) {
            // A shape this tenant does not hold is not a document that is
            // wrong. Unresolvable is not invalid, and a face that refused
            // what it merely does not hold would refuse a tenant's own
            // profile the moment it arrived late.
            return List.of();
        }
        List<Issue> issues = new ArrayList<>();
        for (Finding finding : ElementChecks.over(index, codes, canonical,
                document.tree()).findings()) {
            issues.add(new Issue(
                    finding.refuses() ? Issue.ERROR : finding.severity(),
                    finding.path(), finding.says()));
        }
        return List.copyOf(issues);
    }

    /**
     * The shape stamp of one accepted document.
     *
     * <p>For each profile the document claims that this tenant holds a
     * version for, {@code shape|version}. A profile the tenant does not hold
     * is not stamped, because a stamp names what the document was actually
     * judged against.
     */
    @Override
    public List<String> writtenUnder(Document document) {
        List<String> stamps = new ArrayList<>();
        Object meta = document.tree().get("meta");
        if (!(meta instanceof Map<?, ?> object)) {
            return List.of();
        }
        for (Object claimed : values(object.get("profile"))) {
            if (!(claimed instanceof String url) || url.isBlank()) {
                continue;
            }
            String version = index.versionOf(url);
            if (version != null && !version.isBlank()) {
                stamps.add(url + "|" + version);
            }
        }
        return List.copyOf(stamps);
    }

    private static List<Object> values(Object held) {
        if (held == null) {
            return List.of();
        }
        if (held instanceof List<?> many) {
            return List.copyOf(many);
        }
        return List.of(held);
    }
}
