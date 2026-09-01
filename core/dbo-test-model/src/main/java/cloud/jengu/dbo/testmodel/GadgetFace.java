package cloud.jengu.dbo.testmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import cloud.jengu.dbo.core.face.DeclaredFace;
import cloud.jengu.dbo.core.face.DomainFace;
import cloud.jengu.dbo.core.face.PayloadFraming;
import cloud.jengu.dbo.core.face.Payloads;
import cloud.jengu.dbo.core.face.RecordProjection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A face for a domain that is not healthcare, so "the engine holds no FHIR
 * knowledge" is something a build can fail on.
 *
 * <p>The engine half was already shown: one store serves gadget registrations
 * and FHIR ones through the same primitives. What that could not show is the
 * <b>contract</b> — every claim about a face was made by reading the FHIR one
 * and finding nothing domain-specific in the interfaces it implements, which
 * is evidence of absence in one direction only.
 *
 * <p><b>What it costs to exist is the point.</b> Three capabilities and eight
 * methods: read a payload, say what type it is, validate one, render one this
 * face built, open and close a document of many, write one member into it, say
 * which storage domains it speaks for, and render the engine's own records in
 * this domain's words. If a second face had needed forty methods, the cost of
 * a second face would have been exactly what the architecture was supposed to
 * keep low.
 *
 * <p><b>What it does not provide is absent, not stubbed.</b> Gadgets have no
 * clinical vocabulary, so there is no grain codec; nothing about a gadget is
 * personal, so there is no coarsening. A face that stubbed them would be
 * telling the engine a lie it cannot tell apart from a broken implementation —
 * so a tenant asking for personal-data isolation on this face is refused at
 * bring-up, by name, which is the behaviour those absences exist to produce.
 *
 * <p>Deliberately small. It is a proof rather than a product, and the moment
 * somebody makes it useful it stops measuring anything.
 */
public final class GadgetFace {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The face's own name, the way a tenant spec asks for it. */
    public static final String NAME = "gadgets";

    private GadgetFace() {
    }

    public static DomainFace face() {
        return DeclaredFace.named(NAME)
                .providing(Payloads.class, new GadgetPayloads())
                .providing(PayloadFraming.class, new GadgetFraming())
                .providing(RecordProjection.class, new GadgetRecords())
                .build();
    }

    /**
     * Reading and checking a gadget document. A pure function of the bytes: it
     * holds nothing, which is what makes it a declared capability rather than
     * a facade the version constructs.
     */
    static final class GadgetPayloads implements Payloads<JsonNode> {

        @Override
        public JsonNode read(String typeName, byte[] payload) {
            try {
                return MAPPER.readTree(payload);
            } catch (Exception e) {
                throw new IllegalArgumentException("not a gadget document: " + e.getMessage(), e);
            }
        }

        @Override
        public String typeOf(JsonNode document) {
            JsonNode kind = document.get("kind");
            if (kind == null || kind.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "a gadget document says what it is in 'kind'; this one says nothing, "
                                + "and guessing from its fields is how two types become one");
            }
            return kind.asText();
        }

        @Override
        public List<String> validate(String typeName, JsonNode document) {
            List<String> problems = new ArrayList<>();
            if (!typeName.equals(typeOf(document))) {
                problems.add("document says it is a " + typeOf(document)
                        + " and it is being stored as a " + typeName);
            }
            for (String required : requiredOf(typeName)) {
                if (!document.hasNonNull(required)) {
                    problems.add(typeName + " requires '" + required + "'");
                }
            }
            return List.copyOf(problems);
        }

        /**
         * A document this face built, rendered. Never the way to answer a
         * read: a payload that was stored comes back as it was stored, and
         * rendering it again would differ in whitespace and key order at
         * least.
         */
        @Override
        public byte[] write(JsonNode document) {
            try {
                return MAPPER.writeValueAsBytes(document);
            } catch (Exception e) {
                throw new IllegalStateException("could not render a gadget document", e);
            }
        }

        private static List<String> requiredOf(String typeName) {
            return switch (typeName) {
                case "Gadget" -> List.of("serial", "vendor", "name", "weightGrams");
                case "Blueprint" -> List.of("url", "title");
                case "Reading" -> List.of("metric", "value", "gadget");
                default -> List.of();
            };
        }
    }

    /**
     * Many gadgets as one document. The domain's own shape, not a Bundle:
     * framing exists precisely so the engine never learns what a page is
     * called.
     */
    static final class GadgetFraming implements PayloadFraming {

        @Override
        public Frame frame(String frameType, Facts facts) {
            StringBuilder prologue = new StringBuilder("{\"kind\":\"")
                    .append(frameType).append('"');
            if (facts.total() != null) {
                prologue.append(",\"total\":").append(facts.total());
            }
            if (facts.selfUrl() != null) {
                prologue.append(",\"self\":\"").append(facts.selfUrl()).append('"');
            }
            if (facts.nextUrl() != null) {
                prologue.append(",\"next\":\"").append(facts.nextUrl()).append('"');
            }
            prologue.append(",\"items\":[");
            return new Frame(prologue.toString().getBytes(StandardCharsets.UTF_8),
                    ",".getBytes(StandardCharsets.UTF_8),
                    "]}".getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The member's own bytes, passed through. The stored payload is the
         * truth, so framing copies it rather than re-rendering a parse of it —
         * the same reason the FHIR face frames instead of re-serialising.
         */
        @Override
        public void member(Member member, java.io.OutputStream out) throws java.io.IOException {
            out.write(member.payload());
        }
    }

    /**
     * The engine's own records, said in this domain's words. A run is a job
     * here, not a Task, which is the whole reason this obligation belongs to a
     * face: the engine knows who did what to which version, and only the
     * domain knows what that is called.
     */
    static final class GadgetRecords implements RecordProjection {

        @Override
        public Set<String> projects() {
            return Set.of(GadgetModel.DOMAIN);
        }

        @Override
        public Optional<String> project(Record record) {
            if (!projects().contains(record.domains().isEmpty() ? "" : record.domains().get(0))) {
                // A record over a domain this face does not claim renders
                // nowhere. Empty is an answer: producing a document with
                // nothing in it would read as a job that did nothing.
                return Optional.empty();
            }
            return Optional.of("{\"kind\":\"job\",\"id\":\"" + record.id()
                    + "\",\"version\":" + record.versionId()
                    + ",\"about\":\"" + record.typeName() + "\"}");
        }
    }
}
