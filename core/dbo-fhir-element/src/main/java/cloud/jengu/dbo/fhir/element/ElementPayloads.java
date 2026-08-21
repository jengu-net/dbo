package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.Payloads.Issue;
import cloud.jengu.dbo.core.face.ReadOnce;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.elementmodel.Manager;
import org.hl7.fhir.r5.formats.IParser;
import org.hl7.fhir.r5.utils.validation.ValidatorSession;
import org.hl7.fhir.r5.utils.xver.XVerExtensionManagerFactory;
import org.hl7.fhir.utilities.validation.ValidationMessage;
import org.hl7.fhir.validation.ValidatorSettings;
import org.hl7.fhir.validation.instance.InstanceValidator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reading, checking and writing a payload against a version's definitions.
 *
 * <p>The document is an {@link Element} — the shape the definitions describe,
 * not a generated class — which is what lets one implementation serve a version
 * that has no generated model. The engine holds a {@code Payloads<?>} and never
 * names it, so nothing above this bundle learns that.
 */
final class ElementPayloads implements Payloads<Element> {

    /** Reads of a payload, so a test can hold this to its word. */
    static final java.util.concurrent.atomic.AtomicLong READS =
            new java.util.concurrent.atomic.AtomicLong();

    private final SimpleWorkerContext context;
    private final Terms terms;
    private final Payloads<Element> reading;

    ElementPayloads(SimpleWorkerContext context) {
        this(context, null);
    }

    /**
     * The tenant form: {@code terms} answers for systems the definitions do
     * not carry. Two consequences, split by their nature (#50):
     * value-set membership rides the validator through {@link TenantContext},
     * where binding strength decides severity — and system membership is
     * checked here, after the validator, because a code claiming a system the
     * tenant holds either exists in it or the document is wrong, regardless
     * of how strongly any binding felt about it.
     */
    ElementPayloads(SimpleWorkerContext context, Terms terms) {
        this.terms = terms;
        this.context = context;
        // One read per request, the same way every face gets it: the engine's
        // envelope extraction asks this face about the bytes the write path
        // already read (REQ-DBO-VER-ONE-READ-PER-REQUEST).
        this.reading = new ReadOnce<>(new Parsing());
    }

    private final class Parsing implements Payloads<Element> {

        @Override
        public Element read(String typeName, byte[] payload) {
            READS.incrementAndGet();
            try {
                return Manager.parseSingle(context, new ByteArrayInputStream(payload),
                        Manager.FhirFormat.JSON);
            } catch (IOException | RuntimeException e) {
                // IOException included, and deliberately: the bytes are in
                // memory, so there is no I/O here to fail — a JSON syntax error
                // arrives as one because the parser reads through a stream.
                // Letting it travel as an I/O failure answers a malformed body
                // with 500, telling a caller the server broke when their
                // request did.
                throw new IllegalArgumentException("body is not parseable FHIR JSON: "
                        + e.getMessage(), e);
            }
        }

        @Override
        public String typeOf(Element document) {
            return document.fhirType();
        }

        @Override
        public List<String> validate(String typeName, Element document) {
            return refusals(check(typeName, document, null));
        }

        /**
         * Everything the validator had to say, at every severity (#50).
         *
         * <p>Only the errors used to survive, which threw away the half of the
         * answer this store is best placed to give: an <b>extensible</b>
         * binding violated is advice, a <b>preferred</b> one is a suggestion,
         * and a code from a system nothing here carries is <b>unresolvable</b>
         * rather than wrong — a different fact, with a different fix, in
         * somebody else's hands. Reporting all three as errors would be worse
         * than reporting none, because callers learn to ignore an outcome that
         * cries wolf; discarding them, which is what happened, taught callers
         * nothing at all.
         *
         * <p>The write path still refuses on errors alone. One evaluation, two
         * readings.
         */
        @Override
        public List<Issue> check(String typeName, Element document, String shapeReference) {
            List<ValidationMessage> messages = new ArrayList<>();
            // A profile the document CLAIMS and nothing here carries is an
            // answer, not a crash. HAPI resolves meta.profile itself and
            // throws an Error the request thread does not survive — the
            // caller gets no bytes at all, which is the one response that
            // tells them nothing (#87).
            for (Element claimed : document.getChildrenByName("meta").stream()
                    .flatMap(meta -> meta.getChildrenByName("profile").stream()).toList()) {
                String url = claimed.primitiveValue();
                if (url != null && context.fetchResource(
                        org.hl7.fhir.r5.model.StructureDefinition.class, url) == null) {
                    return List.of(new Issue(Issue.ERROR, document.fhirType(),
                            "the resource claims the profile '" + url + "', which this tenant "
                                    + "does not have — nothing was checked against it"));
                }
            }
            // The path a message is reported against: the document's own type,
            // since a caller reading "Patient.name[0]" can find it and a caller
            // reading "[0]" cannot.
            if (shapeReference == null || shapeReference.isBlank()) {
                validator().validate(null, messages, document.fhirType(), document);
            } else {
                if (context.fetchResource(org.hl7.fhir.r5.model.StructureDefinition.class,
                        shapeReference) == null) {
                    // Checked here rather than left to the validator, which
                    // throws for a profile it cannot locate — an exception the
                    // caller sees as a broken server rather than as an answer
                    // about a shape nobody here carries.
                    return List.of(new Issue(Issue.ERROR, document.fhirType(),
                            "the shape '" + shapeReference + "' is not one this face carries, "
                                    + "so nothing was checked against it"));
                }
                validator().validate(null, messages, document.fhirType(), document,
                        shapeReference);
            }
            List<Issue> issues = new ArrayList<>(messages.stream()
                    .map(m -> new Issue(severityOf(m),
                            m.getLocation() == null ? document.fhirType() : m.getLocation(),
                            m.getMessage()))
                    .toList());
            issues.addAll(heldSystemMembership(document, document.fhirType()));
            return issues;
        }

        /**
         * Every coding in the document whose system the tenant holds and the
         * definitions do not: the code exists in that system or the element
         * is in error. Strength-independent by design — binding strength
         * governs whether a coding must come from a VALUE SET, not whether a
         * code is real in the system it claims.
         */
        private List<Issue> heldSystemMembership(Element element, String path) {
            if (terms == null) {
                return List.of();
            }
            List<Issue> issues = new ArrayList<>();
            if ("Coding".equals(element.fhirType())) {
                String system = element.getNamedChildValue("system");
                String code = element.getNamedChildValue("code");
                if (system != null && code != null
                        && context.fetchResource(
                                org.hl7.fhir.r5.model.CodeSystem.class, system) == null) {
                    terms.membership(system, code)
                            .filter(m -> !m.present())
                            .ifPresent(m -> issues.add(new Issue(Issue.ERROR, path,
                                    "Unknown code '" + code + "' in the code system '" + system
                                            + "' (answered from this tenant's terminology)")));
                }
            }
            for (Element child : element.getChildren()) {
                issues.addAll(heldSystemMembership(child,
                        path + "." + child.getName()));
            }
            return issues;
        }

        /** FHIR's severities, as an outcome spells them. */
        private static String severityOf(ValidationMessage message) {
            return switch (message.getLevel()) {
                case FATAL, ERROR -> Issue.ERROR;
                case WARNING -> "warning";
                default -> "information";
            };
        }

        /**
         * Against the profile a step declares (#71).
         *
         * <p>The same validator and the same message shape — what changes is
         * which definition it is held to. A profile this face does not carry is
         * an issue rather than a pass: a shape nobody can resolve is not a
         * shape a document conformed to, and treating an unresolvable profile
         * as "nothing wrong" is how a step's precondition quietly stops being
         * one.
         */
        @Override
        public List<String> validate(String typeName, Element document, String shapeReference) {
            return refusals(check(typeName, document, shapeReference));
        }

        /** The refusing half: what a write is held to. */
        private static List<String> refusals(List<Issue> issues) {
            return issues.stream().filter(Issue::refuses)
                    .map(issue -> "ERROR " + issue.location() + ": " + issue.message())
                    .toList();
        }

        @Override
        public byte[] write(Element document) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                Manager.compose(context, document, out, Manager.FhirFormat.JSON,
                        IParser.OutputStyle.NORMAL, null);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write a payload to memory", e);
            }
            return out.toByteArray();
        }
    }

    /**
     * Built per call rather than held: a validator accumulates state about the
     * instance it is checking, and two tenants' writes sharing one is a defect
     * that only appears under load.
     */
    private InstanceValidator validator() {
        InstanceValidator validator = new InstanceValidator(context,
                new ElementHostServices(context),
                XVerExtensionManagerFactory.createExtensionManager(context),
                new ValidatorSession(), new ValidatorSettings());
        validator.setFetcher(new ElementFetcher(context));
        // What a write is held to, and what it is not — see the policy.
        validator.setPolicyAdvisor(new ElementValidationPolicy());
        // An extension whose definition this face does not carry is allowed:
        // FHIR says a plain extension may be ignored by a reader that does not
        // know it, and a store that refused one would refuse every
        // implementer's own — including dbo's, which is how this was found.
        validator.setAnyExtensionsAllowed(true);
        // A code system a store does not hold cannot be checked, and refusing
        // what cannot be checked would refuse every private terminology. A code
        // that violates a binding this face CAN check is still refused.
        validator.setUnknownCodeSystemsCauseErrors(false);
        // Coded values are checked, and checked from what is held: the value
        // sets and code systems the face carries. Turning the checks off would
        // accept `gender: unicorn`, which is exactly the write a store must
        // refuse; reaching a terminology server instead would make a write fail
        // when somebody else's server does. The context is offline by
        // construction — see ElementVersion.
        return validator;
    }

    @Override
    public Element read(String typeName, byte[] payload) {
        return reading.read(typeName, payload);
    }

    @Override
    public String typeOf(Element document) {
        return reading.typeOf(document);
    }

    @Override
    public List<String> validate(String typeName, Element document) {
        return reading.validate(typeName, document);
    }

    /** Against a step's declared shape (#71) — the same validator, held to a named profile. */
    @Override
    public List<String> validate(String typeName, Element document, String shapeReference) {
        return reading.validate(typeName, document, shapeReference);
    }

    /** Everything, at every severity (#50). */
    @Override
    public List<Issue> check(String typeName, Element document, String shapeReference) {
        return reading.check(typeName, document, shapeReference);
    }

    @Override
    public byte[] write(Element document) {
        return reading.write(document);
    }
}
