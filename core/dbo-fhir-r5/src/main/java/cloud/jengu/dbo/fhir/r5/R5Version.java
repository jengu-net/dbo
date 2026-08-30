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
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import cloud.jengu.dbo.fhir.common.FhirFace;

import java.nio.charset.StandardCharsets;

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
                    local = withTccl(() -> {
                        FhirContext fresh = FhirContext.forR5();
                        withoutNarrative(fresh);
                        return fresh;
                    });
                    context = local;
                }
            }
        }
        return local;
    }

    /**
     * R5's own profiles, minus the documentation nobody here reads.
     *
     * <p>Asking a {@code FhirContext} for anything that consults definitions —
     * {@code newFhirPath()}, which the envelope extractor calls for every
     * stored resource — makes it build a {@link DefaultProfileValidationSupport},
     * and R5's shipped {@code profiles-resources.xml} carries narrative where
     * R4's does not. Measured on this laptop: <b>515MB</b> live for R5 against
     * <b>83MB</b> for R4, the difference being 969,201 {@code XhtmlNode} and
     * their parse locations. Dropping the narrative leaves <b>225MB</b>, and
     * FhirPath answers identically — the profiles are load-bearing, the prose
     * about them is not. (An empty support is not the cheaper answer: the
     * engine dereferences {@code fetchResourcesByType} in its own constructor.)
     *
     * <p>Done HERE rather than at each consumer because this is the one place
     * the context is built, so a caller cannot get an unprepared one. The
     * support is installed on the context, which means it is also what the
     * validator below chains onto.
     *
     * <p>Stripped after the parse rather than before it, which lowers what is
     * HELD and not what is touched: the 515MB is still reached while the
     * package is being read. That peak is the one an appliance-sized heap
     * notices, and removing it means never parsing the div at all — the
     * byte-level treatment the element face already gives its carried packages.
     */
    private static void withoutNarrative(FhirContext ctx) {
        DefaultProfileValidationSupport support = new DefaultProfileValidationSupport(ctx);
        for (IBaseResource resource : support.fetchAllConformanceResources()) {
            if (resource instanceof org.hl7.fhir.r5.model.DomainResource domain
                    && domain.hasText()) {
                domain.setText(null);
            }
        }
        ctx.setValidationSupport(support);
    }

    static FhirValidator validator() {
        FhirValidator local = validator;
        if (local == null) {
            synchronized (R5Version.class) {
                local = validator;
                if (local == null) {
                    local = withTccl(() -> {
                        FhirContext c = context();
                        // The context's OWN support, not a second one: a fresh
                        // DefaultProfileValidationSupport here would parse R5's
                        // profiles again, narrative and all, and put back the
                        // 291MB that context() just dropped.
                        ValidationSupportChain chain = new ValidationSupportChain(
                                c.getValidationSupport(),
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

    /** Reads of a payload, so a test can hold this to its word. */
    static final java.util.concurrent.atomic.AtomicLong READS =
            new java.util.concurrent.atomic.AtomicLong();

    static IBaseResource parse(String resourceJson) {
        READS.incrementAndGet();
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

}
