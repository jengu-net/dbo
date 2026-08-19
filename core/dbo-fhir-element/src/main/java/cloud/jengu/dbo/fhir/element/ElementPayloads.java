package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.face.Payloads;
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
    private final Payloads<Element> reading;

    ElementPayloads(SimpleWorkerContext context) {
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
            List<ValidationMessage> messages = new ArrayList<>();
            // The path a message is reported against: the document's own type,
            // since a caller reading "Patient.name[0]" can find it and a caller
            // reading "[0]" cannot.
            validator().validate(null, messages, document.fhirType(), document);
            return messages.stream()
                    .filter(m -> m.getLevel() == ValidationMessage.IssueSeverity.ERROR
                            || m.getLevel() == ValidationMessage.IssueSeverity.FATAL)
                    .map(m -> m.getLevel() + " " + m.getLocation() + ": " + m.getMessage())
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

    @Override
    public byte[] write(Element document) {
        return reading.write(document);
    }
}
