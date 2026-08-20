package cloud.jengu.dbo.fhir.element;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.elementmodel.Element;
import org.hl7.fhir.r5.fhirpath.FHIRPathEngine;
import org.hl7.fhir.r5.model.Base;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.SearchParameter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The searchable envelope, from a version's own search parameters.
 *
 * <p>What a parameter means and where it points are in the definitions: an
 * expression to evaluate and a type that says how the result is indexed. So
 * extraction needs no version knowledge of its own — the same code serves a
 * version released years ago and one still at ballot, because both publish
 * their parameters the same way.
 *
 * <p>A parameter whose expression the engine cannot evaluate is skipped rather
 * than fatal, as it is on every other face: a store that refused a write
 * because one of a hundred parameters has syntax the evaluator does not
 * support would be refusing valid data.
 */
final class ElementEnvelopes {

    private ElementEnvelopes() {
    }

    static Envelope extract(SimpleWorkerContext context, List<SearchParameter> parameters,
            Element document, boolean canonical) {
        FHIRPathEngine fhirPath = new FHIRPathEngine(context);
        // A search parameter may say `.where(resolve() is Patient)`, and an
        // engine with no host fails the whole expression rather than the test.
        fhirPath.setHostServices(new ElementHostServices(context));
        Envelope envelope = new Envelope();
        for (SearchParameter parameter : parameters) {
            List<Base> hits;
            try {
                hits = fhirPath.evaluate(document, parameter.getExpression());
            } catch (Exception e) {
                continue; // an expression this evaluator cannot run is not a bad write
            }
            String path = pathName(parameter.getCode());
            for (Base hit : hits) {
                add(envelope, parameter.getType(), path, hit);
            }
        }
        if (canonical) {
            // The canonical identity: a type whose identity IS its url is found
            // by it, not merely indexed under it — conditional writes and every
            // reassembly from a native form go through the identity index
            // (REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS).
            String url = document.getNamedChildValue("url");
            if (url != null) {
                envelope.identifier(cloud.jengu.dbo.core.api.Identifier.CANONICAL_SYSTEM, url);
                envelope.value("url", EnvelopeValue.of(url));
            }
        }
        return envelope;
    }

    private static void add(Envelope envelope, Enumerations.SearchParamType type, String path,
            Base hit) {
        switch (type) {
            case TOKEN -> token(envelope, path, hit);
            case STRING -> {
                String value = primitive(hit);
                if (value != null) {
                    // FHIR string search is case-insensitive starts-with: the
                    // base path stores lowercase; :exact matches the _xct path
                    envelope.value(path, EnvelopeValue.of(value.toLowerCase()));
                    envelope.value(path + "_xct", EnvelopeValue.of(value));
                }
            }
            case URI -> {
                String value = primitive(hit);
                if (value != null) {
                    envelope.value(path, EnvelopeValue.of(value)); // uris are case-sensitive
                }
            }
            case DATE -> date(envelope, path, hit);
            case NUMBER -> {
                String value = primitive(hit);
                if (value != null) {
                    try {
                        envelope.value(path, EnvelopeValue.of(new BigDecimal(value)));
                    } catch (NumberFormatException e) {
                        // a number-typed parameter over something that is not one
                    }
                }
            }
            case REFERENCE -> reference(envelope, path, hit);
            default -> {
                // composite, quantity, special: their own work, and a wrong
                // guess here would index something a search then cannot find
            }
        }
    }

    /**
     * A token in the three shapes a search can ask for it.
     *
     * <p>FHIR's token syntax is three questions, not one: {@code sys|code} is
     * this code in this system, {@code code} is this code in any system, and
     * {@code sys|} is <b>anything at all</b> in this system. The index answers
     * by containment, so a form it does not carry is a search that silently
     * finds nothing — which is worse than an error, because a count of zero
     * looks like an answer (#81).
     */
    private static void tokenForms(Envelope envelope, String path, String system, String code) {
        if (code == null) {
            return;
        }
        if (system != null) {
            envelope.value(path, EnvelopeValue.token(system, code));
            envelope.value(path, new EnvelopeValue.Token(system, null)); // "sys|"
        }
        envelope.value(path, new EnvelopeValue.Token(null, code)); // bare code
    }

    private static void token(Envelope envelope, String path, Base hit) {
        if (!(hit instanceof Element element)) {
            String value = primitive(hit);
            if (value != null) {
                envelope.value(path, EnvelopeValue.token(null, value));
            }
            return;
        }
        switch (element.fhirType()) {
            case "Identifier" -> {
                String system = element.getNamedChildValue("system");
                String value = element.getNamedChildValue("value");
                if (value != null) {
                    tokenForms(envelope, path, system, value);
                    envelope.identifier(system, value);
                }
            }
            case "CodeableConcept" -> {
                List<Element> codings = new java.util.ArrayList<>();
                element.getNamedChildren("coding", codings);
                codings.forEach(coding -> coding(envelope, path, coding));
            }
            case "Coding" -> coding(envelope, path, element);
            case "ContactPoint" -> tokenForms(envelope, path,
                    element.getNamedChildValue("system"), element.getNamedChildValue("value"));
            default -> {
                String value = element.primitiveValue();
                if (value != null) {
                    envelope.value(path, EnvelopeValue.token(null, value));
                }
            }
        }
    }

    private static void coding(Envelope envelope, String path, Element coding) {
        tokenForms(envelope, path, coding.getNamedChildValue("system"),
                coding.getNamedChildValue("code"));
    }

    private static void reference(Envelope envelope, String path, Base hit) {
        if (!(hit instanceof Element element)) {
            return;
        }
        String reference = "Reference".equals(element.fhirType())
                ? element.getNamedChildValue("reference") : element.primitiveValue();
        if (reference != null) {
            int slash = reference.lastIndexOf('/');
            if (slash > 0) {
                String type = reference.substring(0, slash);
                int previous = type.lastIndexOf('/');
                envelope.reference(path, previous < 0 ? type : type.substring(previous + 1),
                        reference.substring(slash + 1));
            }
        }
        if ("Reference".equals(element.fhirType())) {
            Element identifier = element.getNamedChild("identifier");
            if (identifier != null && identifier.getNamedChildValue("system") != null) {
                // logical reference: the :identifier modifier's target
                tokenForms(envelope, path + "_identifier",
                        identifier.getNamedChildValue("system"),
                        identifier.getNamedChildValue("value"));
            }
        }
    }

    private static void date(Envelope envelope, String path, Base hit) {
        String value = primitive(hit);
        if (value == null && hit instanceof Element element) {
            // Period and Timing index by their start: a range's lower bound is
            // what an ordering asks for
            value = element.getNamedChildValue("start");
        }
        Instant instant = instant(value);
        if (instant != null) {
            envelope.value(path, EnvelopeValue.of(instant));
        }
    }

    /** A FHIR date, dateTime or instant, whichever precision it was written at. */
    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            if (value.length() == 4) {
                return OffsetDateTime.parse(value + "-01-01T00:00:00Z").toInstant();
            }
            if (value.length() == 7) {
                return OffsetDateTime.parse(value + "-01T00:00:00Z").toInstant();
            }
            if (value.length() == 10) {
                return OffsetDateTime.parse(value + "T00:00:00Z").toInstant();
            }
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }

    private static String primitive(Base hit) {
        return hit.isPrimitive() ? hit.primitiveValue() : null;
    }

    /** Envelope path names: parameter codes with '-' folded to '_' (engine path charset). */
    private static String pathName(String code) {
        return code.replace('-', '_');
    }
}
