package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.IWorkerContext;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.model.CanonicalResource;
import org.hl7.fhir.r5.model.CanonicalType;
import org.hl7.fhir.r5.utils.validation.IResourceValidator;
import org.hl7.fhir.r5.utils.validation.IValidatorResourceFetcher;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the validator may fetch while checking a resource: definitions, and
 * nothing else.
 *
 * <p>A reference is <b>not</b> followed. Checking that a referenced object
 * exists on the accept path would make a write depend on what else is in the
 * store at that instant: the same two writes would be accepted in one order and
 * refused in the other, and a resource pointing at something arriving a second
 * later would be refused for being early. Referential closure is the engine's,
 * checked where it can be checked consistently
 * (REQ-DBO-CORE-REFERENCE-EDGES).
 *
 * <p>Present rather than absent, though, because the two are not the same: with
 * no fetcher at all the validator does not skip the check, it fails with
 * "Resource resolution services not provided" and every resource carrying a
 * reference is refused as if the store were broken.
 *
 * <p>Canonical resources — profiles, value sets, code systems — <em>are</em>
 * resolved, from the definitions the face carries. They are what validation
 * validates against, and they never come from the network.
 */
final class ElementFetcher implements IValidatorResourceFetcher {

    private final SimpleWorkerContext context;

    ElementFetcher(SimpleWorkerContext context) {
        this.context = context;
    }

    @Override
    public Element fetch(IResourceValidator validator, Object appContext, String url) {
        return null;
    }

    @Override
    public boolean resolveURL(IResourceValidator validator, Object appContext, String path,
            String url, IWorkerContext.VersionResolutionRules rules, String version,
            boolean canonical, List<CanonicalType> targets) {
        // Whether a URL is legitimate to reference, not whether it resolves: a
        // store holds what it was given and does not police the web.
        return true;
    }

    @Override
    public byte[] fetchRaw(IResourceValidator validator, String url) {
        throw new UnsupportedOperationException("a store does not fetch " + url
                + " while validating — definitions travel with the face, and nothing else "
                + "is fetched at all");
    }

    @Override
    public IValidatorResourceFetcher setLocale(Locale locale) {
        return this;
    }

    @Override
    public CanonicalResource fetchCanonicalResource(IResourceValidator validator,
            Object appContext, String url) {
        return context.fetchResource(CanonicalResource.class, url);
    }

    @Override
    public boolean fetchesCanonicalResource(IResourceValidator validator, String url) {
        return true;
    }

    @Override
    public Set<ResourceVersionInformation> fetchCanonicalResourceVersions(
            IResourceValidator validator, Object appContext, String url) {
        return Set.of();
    }
}
