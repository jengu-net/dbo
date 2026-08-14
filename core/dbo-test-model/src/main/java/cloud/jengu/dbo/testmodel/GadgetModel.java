package cloud.jengu.dbo.testmodel;

import cloud.jengu.dbo.core.api.Envelope;
import cloud.jengu.dbo.core.api.EnvelopeExtractor;
import cloud.jengu.dbo.core.api.EnvelopeValue;
import cloud.jengu.dbo.core.api.Identifier;
import cloud.jengu.dbo.core.api.IdentityClass;
import cloud.jengu.dbo.core.api.IndexSpec;
import cloud.jengu.dbo.core.api.TypeRegistration;
import cloud.jengu.dbo.core.api.ValueKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The test personality: three types covering the three identity classes.
 *
 * Gadget    — IDENTIFIER identity via serial number; token/number/date/ref envelope
 * Blueprint — CANONICAL identity via url
 * Reading   — INTERNAL identity; references its gadget
 */
public final class GadgetModel {

    public static final String SERIAL_SYSTEM = "urn:gadget:serial";
    public static final String DOMAIN = "gadgets";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GadgetModel() {}

    public static List<TypeRegistration> registrations() {
        return List.of(gadget(false), blueprint(), reading());
    }

    /** Registrations with the v2 gadget extractor (adds vendorUpper) — for the reindex proof. */
    public static List<TypeRegistration> registrationsV2() {
        return List.of(gadget(true), blueprint(), reading());
    }

    private static TypeRegistration gadget(boolean v2) {
        EnvelopeExtractor extractor = (type, payload) -> {
            JsonNode n = parse(payload);
            if (n.hasNonNull("boom")) {
                throw new IllegalStateException("extractor boom requested"); // atomicity probe
            }
            Envelope e = new Envelope();
            e.identifier(SERIAL_SYSTEM, n.get("serial").asText());
            e.value("vendor", EnvelopeValue.token("urn:vendor", n.get("vendor").asText()));
            e.value("name", EnvelopeValue.of(n.get("name").asText()));
            e.value("weightGrams", EnvelopeValue.of(new BigDecimal(n.get("weightGrams").asText())));
            if (n.hasNonNull("commissionedAt")) {
                e.value("commissionedAt", EnvelopeValue.of(Instant.parse(n.get("commissionedAt").asText())));
            }
            if (n.hasNonNull("partOf")) {
                e.reference("partOf", "Gadget", n.get("partOf").asText());
            }
            if (v2) {
                e.value("vendorUpper", EnvelopeValue.of(n.get("vendor").asText().toUpperCase()));
            }
            return e;
        };
        List<IndexSpec> indexes = v2
                ? List.of(new IndexSpec("weightGrams", ValueKind.NUMBER),
                        new IndexSpec("vendorUpper", ValueKind.STRING))
                : List.of(new IndexSpec("weightGrams", ValueKind.NUMBER));
        return new TypeRegistration("Gadget", DOMAIN, IdentityClass.IDENTIFIER,
                java.util.Set.of(SERIAL_SYSTEM), extractor, indexes);
    }

    private static TypeRegistration blueprint() {
        EnvelopeExtractor extractor = (type, payload) -> {
            JsonNode n = parse(payload);
            Envelope e = new Envelope();
            e.identifier(Identifier.CANONICAL_SYSTEM, n.get("url").asText());
            e.value("title", EnvelopeValue.of(n.get("title").asText()));
            return e;
        };
        return new TypeRegistration("Blueprint", DOMAIN, IdentityClass.CANONICAL,
                java.util.Set.of(), extractor, List.of());
    }

    private static TypeRegistration reading() {
        EnvelopeExtractor extractor = (type, payload) -> {
            JsonNode n = parse(payload);
            Envelope e = new Envelope();
            e.value("metric", EnvelopeValue.token("urn:metric", n.get("metric").asText()));
            e.value("value", EnvelopeValue.of(new BigDecimal(n.get("value").asText())));
            e.reference("gadget", "Gadget", n.get("gadget").asText());
            return e;
        };
        return new TypeRegistration("Reading", DOMAIN, IdentityClass.INTERNAL,
                java.util.Set.of(), extractor, List.of(new IndexSpec("value", ValueKind.NUMBER)));
    }

    private static JsonNode parse(byte[] payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
