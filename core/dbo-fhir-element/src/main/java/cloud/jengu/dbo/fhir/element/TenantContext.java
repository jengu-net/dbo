package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.context.SimpleWorkerContext;
import org.hl7.fhir.r5.model.CodeSystem;
import org.hl7.fhir.r5.model.StructureDefinition;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.ValueSet;
import org.hl7.fhir.r5.terminologies.utilities.ValidationResult;
import org.hl7.fhir.utilities.validation.ValidationMessage.IssueSeverity;
import org.hl7.fhir.utilities.validation.ValidationOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One tenant's view of the shared worker context: the definitions are the
 * version's, the terminology is the tenant's.
 *
 * <p>The shared {@link SimpleWorkerContext} is one per version and takes
 * seconds to build (#83); a tenant cannot have its own. What a tenant has is a
 * copy — the copy constructor shares the loaded definition managers, ~100ms —
 * with the {@code validateCode} family overridden to consult the tenant's
 * {@link Terms} for systems the definitions do not carry.
 *
 * <p>The order of consultation is the contract:
 *
 * <ol>
 *   <li>a system the shared context carries is answered by the shared context,
 *       exactly as before this class existed;</li>
 *   <li>a system it does not carry is asked of the tenant's store — a held
 *       system answers definitively, in or out;</li>
 *   <li>a system neither holds falls through to the toolchain's own honest
 *       "cannot validate" — <i>unresolvable</i>, never <i>invalid</i> (#50).</li>
 * </ol>
 *
 * <p>Binding strength is deliberately NOT decided here. This class answers the
 * membership fact; the validator above it maps a failed membership to error,
 * warning or information according to the binding the element declared —
 * which is how required and extensible bindings come to differ without this
 * class knowing bindings exist.
 */
final class TenantContext extends SimpleWorkerContext {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(TenantContext.class);

    private final Terms terms;

    TenantContext(SimpleWorkerContext shared, Terms terms) throws IOException {
        this(shared, terms, List.of());
    }

    /**
     * The tenant's own StructureDefinitions join the view (#87).
     *
     * <p>Real validation never runs against the standard pack alone: it runs
     * against the pack PLUS what a tenant defined on top — the profiles its
     * zone mandates, the shapes its steps declare. They are cached into THIS
     * tenant's copy, which is why the isolation the copy constructor gives is
     * load-bearing rather than incidental.
     *
     * <p>Snapshots are generated where a profile shipped only a differential,
     * and that is not a nicety: a differential says what CHANGES from the
     * base, so a validator handed one without a snapshot checks the handful
     * of elements the author mentioned and silently passes everything else.
     */
    TenantContext(SimpleWorkerContext shared, Terms terms, List<String> profiles)
            throws IOException {
        this(shared, terms, profiles, List.of());
    }

    /**
     * The same, with the tenant's own converters (#133).
     *
     * <p>A pack's StructureMaps are pack content exactly as its profiles are
     * — authored in the tenant's own store, synced with the shapes they
     * convert — so they belong in the same view. Cached verbatim: unlike a
     * profile, a map needs no snapshot, and a map this context cannot read
     * is skipped rather than failing a bring-up over a converter nothing has
     * asked for yet.
     */
    TenantContext(SimpleWorkerContext shared, Terms terms, List<String> profiles,
            List<String> maps) throws IOException {
        super(shared);
        this.terms = terms;
        for (String profile : profiles) {
            cacheProfile(profile);
        }
        for (String map : maps) {
            cacheMap(map);
        }
    }

    private void cacheMap(String map) {
        try {
            cacheResource(new org.hl7.fhir.r5.formats.JsonParser()
                    .parse(map.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception unreadable) {
            LOG.warn("a stored StructureMap could not be read, so it converts nothing: {}",
                    unreadable.getMessage());
        }
    }

    /**
     * One tenant profile, snapshotted if it needs it.
     *
     * <p>A profile that cannot be snapshotted is REFUSED rather than cached
     * half-formed: caching it would mean validating against the fragment the
     * author happened to write, and reporting that as conformance. What the
     * tenant gets instead is the profile absent and the reason said out loud
     * — a broken profile is a fact about the profile.
     */
    private void cacheProfile(String profile) {
        StructureDefinition definition;
        try {
            definition = (StructureDefinition) new org.hl7.fhir.r5.formats.JsonParser()
                    .parse(profile.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "a stored StructureDefinition could not be read: " + e.getMessage(), e);
        }
        if (!definition.hasSnapshot() && definition.hasBaseDefinition()) {
            StructureDefinition base = fetchResource(StructureDefinition.class,
                    definition.getBaseDefinition());
            if (base == null) {
                throw new IllegalStateException("the profile " + definition.getUrl()
                        + " is built on " + definition.getBaseDefinition()
                        + ", which this tenant does not have — nothing can be validated "
                        + "against it, so it is not offered as if something could");
            }
            List<org.hl7.fhir.utilities.validation.ValidationMessage> messages =
                    new ArrayList<>();
            try {
                new org.hl7.fhir.r5.conformance.profile.ProfileUtilities(this, messages, null)
                        .generateSnapshot(base, definition, definition.getUrl(), null,
                                definition.getName());
            } catch (Exception e) {
                throw new IllegalStateException("the profile " + definition.getUrl()
                        + " could not be resolved against its base: " + e.getMessage(), e);
            }
        }
        cacheResource(definition);
    }

    @Override
    public ValidationResult validateCode(ValidationOptions options, Coding coding, ValueSet vs) {
        ValidationResult fromStore = fromStore(coding, vs);
        return fromStore != null ? fromStore : super.validateCode(options, coding, vs);
    }

    @Override
    public ValidationResult validateCode(ValidationOptions options, Coding coding, ValueSet vs,
            org.hl7.fhir.r5.utils.validation.ValidationContextCarrier ctxt) {
        ValidationResult fromStore = fromStore(coding, vs);
        return fromStore != null ? fromStore : super.validateCode(options, coding, vs, ctxt);
    }

    /**
     * A concept is answered here only when EVERY coding is answerable from the
     * store — one coding the store cannot speak for sends the whole question
     * to the toolchain, so a mixed concept is never half-answered by two
     * authorities that cannot see each other's reasoning.
     */
    @Override
    public ValidationResult validateCode(ValidationOptions options, CodeableConcept concept,
            ValueSet vs) {
        List<ValidationResult> answers = new ArrayList<>();
        for (Coding coding : concept.getCoding()) {
            ValidationResult answer = fromStore(coding, vs);
            if (answer == null) {
                return super.validateCode(options, concept, vs);
            }
            answers.add(answer);
        }
        if (answers.isEmpty()) {
            return super.validateCode(options, concept, vs);
        }
        // one coding in the value set carries the concept, as in the spec
        return answers.stream().filter(ValidationResult::isOk).findFirst()
                .orElse(answers.get(0));
    }

    /**
     * The store's answer, or null for "not this class's question" — the system
     * is the shared context's, or the tenant does not hold it.
     */
    private ValidationResult fromStore(Coding coding, ValueSet vs) {
        String system = coding.getSystem();
        if (system == null || fetchCodeSystem(system) != null) {
            return null;
        }
        var membership = terms.membership(system, coding.getCode());
        if (membership.isEmpty()) {
            return null;
        }
        if (!membership.get().present()) {
            return new ValidationResult(IssueSeverity.ERROR,
                    "Unknown code '" + coding.getCode() + "' in the code system '" + system
                            + "' (answered from this tenant's terminology)", null);
        }
        return switch (inValueSet(vs, system, coding.getCode())) {
            case IN -> new ValidationResult(system, null,
                    new CodeSystem.ConceptDefinitionComponent()
                            .setCode(coding.getCode())
                            .setDisplay(membership.get().display()),
                    membership.get().display());
            case OUT -> new ValidationResult(IssueSeverity.ERROR,
                    "The code '" + coding.getCode() + "' from the code system '" + system
                            + "' is valid but is not in the value set '"
                            + (vs == null ? "?" : vs.getUrl()) + "'", null);
            case UNANSWERABLE -> null;
        };
    }

    private enum VsAnswer { IN, OUT, UNANSWERABLE }

    /**
     * Membership in a value set, decided from its compose — the only part of
     * a value set this class reads. A filter it cannot evaluate makes the
     * question unanswerable rather than quietly true or false.
     */
    private static VsAnswer inValueSet(ValueSet vs, String system, String code) {
        if (vs == null) {
            return VsAnswer.IN; // no value set: the question was system membership
        }
        if (!vs.hasCompose()) {
            return VsAnswer.UNANSWERABLE;
        }
        for (ValueSet.ConceptSetComponent exclude : vs.getCompose().getExclude()) {
            if (system.equals(exclude.getSystem()) && exclude.getConcept().stream()
                    .anyMatch(c -> code.equals(c.getCode()))) {
                return VsAnswer.OUT;
            }
        }
        for (ValueSet.ConceptSetComponent include : vs.getCompose().getInclude()) {
            if (!system.equals(include.getSystem())) {
                continue;
            }
            if (include.hasFilter()) {
                return VsAnswer.UNANSWERABLE;
            }
            if (include.getConcept().isEmpty()
                    || include.getConcept().stream().anyMatch(c -> code.equals(c.getCode()))) {
                return VsAnswer.IN;
            }
        }
        // not in any include naming the system — and a system the compose
        // never mentions is equally out: the coding is simply not in this set
        return VsAnswer.OUT;
    }
}
