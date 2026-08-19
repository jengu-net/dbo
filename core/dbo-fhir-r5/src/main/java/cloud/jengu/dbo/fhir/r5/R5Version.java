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

    /**
     * Reading and writing a payload: what only a version knows how to do, and
     * so what only a version declares. The document is HAPI's; nothing above
     * this bundle names it, which is what a wildcard on the engine's side buys.
     */
    private static final Payloads<IBaseResource> PAYLOADS =
            new cloud.jengu.dbo.core.face.ReadOnce<>(new Payloads<IBaseResource>() {

        @Override
        public IBaseResource read(String typeName, byte[] payload) {
            return parse(new String(payload, StandardCharsets.UTF_8));
        }

        @Override
        public String typeOf(IBaseResource document) {
            return document.fhirType();
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
    });

    /**
     * The document behind a payload, for this version's own code — the engine's
     * envelope extraction, which is handed bytes because
     * {@code EnvelopeExtractor} is the engine's and knows no document.
     *
     * <p>Asked of the same capability the write path asked, so a payload the
     * face has already read is not read again
     * (REQ-DBO-VER-ONE-READ-PER-REQUEST), and a payload a decorator rewrote on
     * the way in is read as it now stands.
     */
    static IBaseResource document(String typeName, byte[] payload) {
        return PAYLOADS.read(typeName, payload);
    }


    /**
     * A stored payload with the engine's own facts put back — the one place
     * that happens, so serving, export and framing cannot drift apart.
     *
     * @param elements when given, the only elements encoded (id and meta are
     *                 always kept), which is what a caller asking for a subset
     *                 of a resource gets
     */
    static String rendered(byte[] payload, String id, long versionId,
            String typeName, List<String> elements) {
        return withTccl(() -> {
            org.hl7.fhir.r5.model.Resource resource = (org.hl7.fhir.r5.model.Resource)
                    context().newJsonParser()
                            .parseResource(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            resource.setId(id);
            resource.getMeta().setVersionId(Long.toString(versionId));
            var parser = context().newJsonParser();
            if (elements != null && typeName != null) {
                java.util.Set<String> encode = new java.util.LinkedHashSet<>();
                encode.add(typeName + ".id");
                encode.add(typeName + ".meta");
                for (String el : elements) {
                    encode.add(typeName + "." + el.trim());
                }
                parser.setEncodeElements(encode);
            }
            return parser.encodeResourceToString(resource);
        });
    }

    /**
     * Framing a document around members the engine already holds as bytes.
     *
     * <p>The members' payloads are rendered rather than passed through, which
     * is what today's assembly does too — the ancestors have to be put back and
     * nothing here can do that without reading the resource. What changes when
     * a face gains a schema-aware normaliser is this method and nothing else.
     */
    private static final PayloadFraming FRAMING = new PayloadFraming() {

        @Override
        public Frame frame(String frameType, Facts facts) {
            StringBuilder head = new StringBuilder(128)
                    .append("{\"resourceType\":\"Bundle\",\"type\":\"").append(frameType).append('"');
            if (facts.total() != null) {
                head.append(",\"total\":").append(facts.total());
            }
            StringBuilder links = new StringBuilder();
            if (facts.selfUrl() != null) {
                links.append("{\"relation\":\"self\",\"url\":").append(quoted(facts.selfUrl())).append('}');
            }
            if (facts.nextUrl() != null) {
                links.append(links.isEmpty() ? "" : ",")
                        .append("{\"relation\":\"next\",\"url\":").append(quoted(facts.nextUrl())).append('}');
            }
            if (!links.isEmpty()) {
                head.append(",\"link\":[").append(links).append(']');
            }
            head.append(",\"entry\":[");
            return new Frame(bytes(head.toString()), bytes(","), bytes("]}"));
        }

        @Override
        public void member(Member member, OutputStream out) throws IOException {
            out.write(bytes("{\"fullUrl\":" + quoted(member.url()) + ",\"resource\":"));
            out.write(bytes(rendered(member.payload(), member.id(), member.versionId(),
                    member.typeName(), null)));
            out.write(bytes(",\"search\":{\"mode\":\""
                    + (Member.INCLUDED.equals(member.role()) ? "include" : "match") + "\"}}"));
        }
    };

    static PayloadFraming framing() {
        return FRAMING;
    }

    /** One face for the version, not one per tenant that happens to be on it. */
    private static final DomainFace FACE = FhirFace.describing("r5")
            .providing(Payloads.class, PAYLOADS)
            .providing(PayloadFraming.class, FRAMING)
            .build();

    static DomainFace face() {
        return FACE;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** JSON string literal — the only escaping a frame does, since payloads pass whole. */
    private static String quoted(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
