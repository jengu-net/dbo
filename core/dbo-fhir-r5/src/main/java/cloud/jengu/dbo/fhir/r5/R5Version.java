package cloud.jengu.dbo.fhir.r5;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;

import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.fhir.common.FhirFace;

import java.nio.charset.StandardCharsets;

import java.util.List;
import java.util.function.Supplier;

/**
 * What R5 defines, built once for the node rather than once per tenant.
 *
 * <p>A {@code FhirContext} is knowledge about a version — how a payload parses,
 * what a resource definition is, what a search parameter means — and nothing in
 * it is any tenant's. It was nonetheless held per personality, and a personality
 * is per tenant, so a node serving twenty tenants built twenty of the most
 * expensive object in the runtime, and built two for every tenant besides.
 *
 * <p>Founding requirement R6 asks for versions to be plural and for a face per
 * version. This is the half of a personality that is genuinely per version,
 * held apart from the half that is per tenant — the declared types and what is
 * derived from them.
 */
final class R5Version {

    private static volatile FhirContext context;
    private static volatile FhirValidator validator;

    private R5Version() {
    }

    static FhirContext context() {
        FhirContext local = context;
        if (local == null) {
            synchronized (R5Version.class) {
                local = context;
                if (local == null) {
                    local = withTccl(FhirContext::forR5);
                    context = local;
                }
            }
        }
        return local;
    }

    static FhirValidator validator() {
        FhirValidator local = validator;
        if (local == null) {
            synchronized (R5Version.class) {
                local = validator;
                if (local == null) {
                    local = withTccl(() -> {
                        FhirContext c = context();
                        ValidationSupportChain chain = new ValidationSupportChain(
                                new DefaultProfileValidationSupport(c),
                                new InMemoryTerminologyServerValidationSupport(c),
                                new CommonCodeSystemsTerminologyService(c));
                        FhirValidator v = c.newValidator();
                        v.registerValidatorModule(new FhirInstanceValidator(chain));
                        return v;
                    });
                    validator = local;
                }
            }
        }
        return local;
    }

    /**
     * HAPI landmine: service discovery is TCCL-based; pin the loader that owns
     * the stack. Asked for by way of a HAPI class rather than this one, because
     * the engine lives in a bundle of its own: the ServiceLoader lookup behind
     * CacheFactory only finds its provider from the loader carrying the engine's
     * META-INF/services, and that is no longer a personality's loader.
     */
    static <T> T withTccl(Supplier<T> body) {
        Thread t = Thread.currentThread();
        ClassLoader old = t.getContextClassLoader();
        t.setContextClassLoader(FhirContext.class.getClassLoader());
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(old);
        }
    }

    /** A regex that ran out of wall clock found nothing; see validated(). */
    private static final String REGEX_TIMED_OUT = "Regex evaluation timed out";

    static IBaseResource parse(String resourceJson) {
        try {
            return context().newJsonParser().parseResource(resourceJson);
        } catch (ca.uhn.fhir.parser.DataFormatException e) {
            throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                    + e.getMessage(), e);
        }
    }

    static ValidationResult validated(IBaseResource resource) {
        ValidationResult result = validator().validateWithResult(resource);
        if (issuesFrom(result).stream().anyMatch(i -> i.contains(REGEX_TIMED_OUT))) {
            // A regex that ran out of wall clock found nothing; asking again is
            // the only answer that is not a guess.
            result = validator().validateWithResult(resource);
        }
        List<String> issues = issuesFrom(result);
        if (issues.stream().anyMatch(i -> i.contains(REGEX_TIMED_OUT))) {
            // Twice is not a busy moment, and it is still not a finding.
            throw new cloud.jengu.dbo.fhir.common.ValidationUnavailableException(
                    resource.fhirType(),
                    issues.stream().filter(i -> i.contains(REGEX_TIMED_OUT))
                            .findFirst().orElse(REGEX_TIMED_OUT));
        }
        return result;
    }

    static List<String> issuesFrom(ValidationResult result) {
        return result.getMessages().stream()
                .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR
                        || m.getSeverity() == ResultSeverityEnum.FATAL)
                .map(m -> m.getSeverity() + " " + m.getLocationString() + ": " + m.getMessage())
                .toList();
    }

    /**
     * Reading and writing a payload: what only a version knows how to do, and
     * so what only a version declares. The document is HAPI's; nothing above
     * this bundle names it, which is what a wildcard on the engine's side buys.
     */
    private static final Payloads<IBaseResource> PAYLOADS = new Payloads<>() {

        @Override
        public IBaseResource read(String typeName, byte[] payload) {
            return parse(new String(payload, StandardCharsets.UTF_8));
        }

        @Override
        public List<String> validate(String typeName, IBaseResource document) {
            return withTccl(() -> issuesFrom(validated(document)));
        }

        @Override
        public byte[] write(IBaseResource document) {
            return withTccl(() -> context().newJsonParser().encodeResourceToString(document))
                    .getBytes(StandardCharsets.UTF_8);
        }
    };

    /** One face for the version, not one per tenant that happens to be on it. */
    private static final DomainFace FACE = FhirFace.describing("r5")
            .providing(Payloads.class, PAYLOADS)
            .build();

    static DomainFace face() {
        return FACE;
    }
}
