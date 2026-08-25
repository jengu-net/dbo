package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.IContextResourceLoader;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.DomainResource;
import org.hl7.fhir.r5.model.Resource;
import org.hl7.fhir.utilities.npm.NpmPackage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * The carried definitions, minus the documentation nobody here reads (#128).
 *
 * <p>Every StructureDefinition, ValueSet and CodeSystem in a published FHIR
 * package carries {@code text.div} — the human-readable rendering, tables of
 * elements and prose about intent. A specification browser needs it. A store
 * validating a write does not: nothing in this face consults narrative, and
 * these definitions are never served to a client, which is checked rather than
 * assumed — the pack is reached only to fetch a base StructureDefinition for
 * validation and to enumerate SearchParameters for the capability statement.
 *
 * <p>Measured on the reference Pi, a loaded R5 context held <b>519,300</b>
 * {@code XhtmlNode} and the same number of {@code XhtmlNode$Location} — the
 * latter recording the line and column each fragment of documentation was
 * parsed from, which is useless the instant parsing ends — inside a 520MB live
 * heap.
 *
 * <p>Stripped at load rather than after: the context registers resources as
 * lazily-parsed proxies, so there is no reliable moment afterwards at which
 * every one of them is a live object to reach into.
 */
final class WithoutNarrative implements IContextResourceLoader {

    private final IContextResourceLoader delegate;

    WithoutNarrative(IContextResourceLoader delegate) {
        this.delegate = delegate;
    }

    private static <T extends Resource> T stripped(T resource) {
        if (resource instanceof DomainResource domain && domain.hasText()) {
            domain.setText(null);
        }
        return resource;
    }

    @Override
    public Bundle loadBundle(InputStream stream, boolean isJson) throws IOException {
        Bundle bundle = delegate.loadBundle(stream, isJson);
        if (bundle != null) {
            stripped(bundle);
            for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                if (entry.hasResource()) {
                    stripped(entry.getResource());
                }
            }
        }
        return bundle;
    }

    @Override
    public Resource loadResource(InputStream stream, boolean isJson) throws IOException {
        return stripped(delegate.loadResource(stream, isJson));
    }

    @Override
    public IContextResourceLoader getNewLoader(NpmPackage npm) throws IOException {
        // The context asks for a loader per package; each has to keep stripping
        // or the second package arrives with its narrative intact.
        return new WithoutNarrative(delegate.getNewLoader(npm));
    }

    // ---- everything else is the delegate's, unchanged -------------------

    @Override
    public Set<String> getTypes() {
        return delegate.getTypes();
    }

    @Override
    public String getResourcePath(Resource resource) {
        return delegate.getResourcePath(resource);
    }

    @Override
    public List<org.hl7.fhir.r5.model.CodeSystem> getCodeSystems() {
        return delegate.getCodeSystems();
    }

    @Override
    public void setPatchUrls(boolean patch) {
        delegate.setPatchUrls(patch);
    }

    @Override
    public String patchUrl(String url, String type) {
        return delegate.patchUrl(url, type);
    }

    @Override
    public IContextResourceLoader setLoadProfiles(boolean profiles) {
        delegate.setLoadProfiles(profiles);
        return this;
    }

    @Override
    public org.hl7.fhir.r5.terminologies.client.TerminologyClientManager
            .ITerminologyClientFactory txFactory() {
        return delegate.txFactory();
    }

    @Override
    public boolean wantLoad(NpmPackage npm, NpmPackage.PackageResourceInformation info) {
        return delegate.wantLoad(npm, info);
    }

    @Override
    public Set<String> reviewActualTypes(Set<String> types) {
        return delegate.reviewActualTypes(types);
    }

    @Override
    public SimpleWorkerContext.PackageResourceLoader editInfo(
            SimpleWorkerContext.PackageResourceLoader loader) {
        return delegate.editInfo(loader);
    }
}
