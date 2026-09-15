package cloud.jengu.dbo.fhir.common;

import cloud.jengu.dbo.core.api.Handling;
import cloud.jengu.dbo.core.api.IdentityClass;

import java.util.Set;

/**
 * Per-type identity configuration (§12): which identity class the type
 * carries, and — for IDENTIFIER — its designated identity-bearing systems in
 * trust order. Supplied by tenant/zone configuration in production. Version-neutral: any personality consumes it.
 *
 * <p>Also carries the type's {@link Handling} — what kind of data it is.
 * The factories below state {@code operational()}
 * rather than defaulting to it silently: a clinical resource written by the
 * tenant's own people is the ordinary case, and everything else says so.
 * Terminology replicated from a zone uses {@link #replicatedCanonical}, and
 * types written from two directions at once — {@code Practitioner} above all —
 * wait on per-field authority rather than being forced
 * into one answer here.
 */
public record FhirTypeConfig(String typeName, IdentityClass identityClass,
                             Set<String> identitySystems, Handling handling,
                             Extraction extraction, Definition definition,
                             Verdict verdict) {

    /** Whether the face has a definition for this type. */
    public enum Definition {

        /** The face defines it: parsed, validated, indexed by its parameters. */
        BY_THE_FACE,

        /**
         * The face has no definition for it, and cannot be given one.
         *
         * <p>A StructureDefinition that defines a resource type the
         * specification does not have is refused by FHIR's own validator —
         * the only legal way to describe an arbitrary shape is a logical
         * model, which is not a resource. So a consumer's own declarations
         * are held as themselves: stored and returned verbatim, indexed by
         * the identity their type declares, and validated against nothing,
         * because there is nothing to validate against.
         *
         * <p>Declared and never inferred. The face could notice it has no
         * definition for a name and quietly switch, and a typo in a type
         * name would then become an opaque type instead of an error.
         */
        NONE
    }

    /** Whose answer decides a write of this type. */
    public enum Verdict {

        /** The toolchain's, as it always has been. The ordinary answer. */
        THE_TOOLCHAIN,

        /**
         * The database's, from the definitions this tenant holds expanded.
         *
         * <p>Declared per type rather than switched on for a face, and never
         * a default — the same rule {@link Extraction} follows, for a sharper
         * version of the same reason. An envelope computed the wrong way
         * makes a document unfindable; a verdict decided the wrong way
         * changes what this store ACCEPTS, and a tenant that started refusing
         * its own traffic would learn it from its writers.
         *
         * <p>What a tenant should have before asking for it is a measurement
         * rather than a hope: the two checkers are compared on every write
         * and the tally is on the deployment's own read, per tenant. The
         * directions are not symmetric and the numbers say which is which —
         * this store has been the quieter of the two everywhere it has been
         * measured, except on cardinality, where the specification says it is
         * right and the toolchain says nothing at all.
         *
         * <p>The toolchain is still asked while this is declared, and its
         * answer is still counted. Only the decision moves. Not asking it is
         * what finally lets the loaded specification go, and that is a
         * separate step with its own evidence to gather.
         */
        THE_DATABASE
    }

    /** Where this type's envelope, claims and edges are computed. */
    public enum Extraction {

        /** In this process, by the face's own extractor. The ordinary answer. */
        IN_PROCESS,

        /**
         * In the database, by the function the release installs there.
         *
         * <p>Declared per type rather than switched on for a whole face, and
         * never a default. What a document is found by is the whole of what a
         * search answers, so a type computed one way where the other was meant
         * goes quietly unfindable rather than loudly wrong — and an empty
         * result is indistinguishable from there being nothing to find. A
         * tenant asks for this when the two sides have been compared over what
         * it holds, which is what config/envelope-baseline.txt records.
         */
        IN_THE_DATABASE
    }

    public FhirTypeConfig(String typeName, IdentityClass identityClass,
            Set<String> identitySystems, Handling handling) {
        this(typeName, identityClass, identitySystems, handling, Extraction.IN_PROCESS,
                Definition.BY_THE_FACE, Verdict.THE_TOOLCHAIN);
    }

    public FhirTypeConfig(String typeName, IdentityClass identityClass,
            Set<String> identitySystems, Handling handling, Extraction extraction) {
        this(typeName, identityClass, identitySystems, handling, extraction,
                Definition.BY_THE_FACE, Verdict.THE_TOOLCHAIN);
    }

    /** The same type, which this face has no definition for. */
    public FhirTypeConfig withoutADefinition() {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, handling,
                extraction, Definition.NONE, verdict);
    }

    /** The same type, computed in the database instead of here. */
    public FhirTypeConfig inTheDatabase() {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, handling,
                Extraction.IN_THE_DATABASE, definition, verdict);
    }

    public static FhirTypeConfig identifier(String typeName, String... systems) {
        return new FhirTypeConfig(typeName, IdentityClass.IDENTIFIER, Set.of(systems),
                Handling.operational());
    }

    public static FhirTypeConfig canonical(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.CANONICAL, Set.of(),
                Handling.operational());
    }

    public static FhirTypeConfig internal(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.INTERNAL, Set.of(),
                Handling.operational());
    }

    /** A vocabulary published by a zone: read in this tenant, never written here. */
    public static FhirTypeConfig replicatedCanonical(String typeName) {
        return new FhirTypeConfig(typeName, IdentityClass.CANONICAL, Set.of(),
                Handling.replicated());
    }

    /**
     * The same type, handled differently — a zone's override, or a test's.
     *
     * <p>Carries the extraction and the verdict forward. Rebuilding through
     * the short constructor resets them to the ordinary answers, and a tenant
     * that asked for the others would be served the defaults without anything
     * saying so — which is how the extraction was lost once already.
     */
    public FhirTypeConfig handledAs(Handling replacement) {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, replacement,
                extraction, definition, verdict);
    }

    /** The same type, decided by the database rather than by the toolchain. */
    public FhirTypeConfig decidedByTheDatabase() {
        return new FhirTypeConfig(typeName, identityClass, identitySystems, handling,
                extraction, definition, Verdict.THE_DATABASE);
    }
}
