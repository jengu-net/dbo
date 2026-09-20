**Status: Adopted.** Reflected in [the payload seam](../arc42-008-crosscutting/the-payload-seam/README.md).

# One bundle owns the FHIR stack

Direction: yes — the HAPI stack rides inside the runtime as embedded packages
rather than as a flat-classpath dependency (same pattern as the DBOS embedding
bundle, [record 001](001-dbos-runs-inside-a-bundle.md); HAPI jars carry no OSGi metadata, so bnd-wrapping is needed
either way). It gives parsing, validation, and — decisively — a **FHIRPath
engine** per version, which SearchParameter → envelope extraction requires;
hand-building that per version is not a realistic alternative. Where the stack
sits — one shared bundle rather than a private copy per personality — is
resolved below.

Corrected premise: multi-version HAPI is *not* impossible on a flat
classpath — it is designed for it (distinct `org.hl7.fhir.r4/.r5` model
packages, `FhirContext.forR4()`/`forR5()` coexisting, one structure jar per
version). What OSGi isolation actually buys is subtler and still real:

- **Version-skew freedom, if a personality needs it.** All structure jars in
  one bundle share a `hapi-fhir-base` + `org.hl7.fhir.utilities`/validator
  core, so the personalities riding the shared stack upgrade in lockstep —
  which is the trade the shared stack makes, and the right one while they
  all sit on the same released HAPI. A personality that must skew (the
  R6-draft one, tracking fast-moving ballot snapshots) buys it back by
  embedding its own stack privately instead of importing this one: the
  packaging is per personality, not per platform.
- **A clean boundary rule**: HAPI types never cross the bundle boundary.
  The personality API toward dbo-core speaks payload bytes + typed envelope
  values + validation outcomes only.

That boundary rule has a consequence to decide deliberately. A host
platform whose own canon is "the typed HAPI object *is* the domain model"
meets a wall in embedded mode: the host's HAPI and the personality's private
HAPI are different classloaders even at the same version — so the host↔DBO
surface is canonical JSON, not shared HAPI objects. Either we accept
re-parse at that edge, or a personality may *optionally export* its model
packages for a host that wants to share them.

**RESOLVED: both, from one shared bundle.** Private embedding per
personality was measured at 138.0 MB for R4 and 155.7 MB for R5, of which
140.4 MB is byte-identical between them — and Felix caches each installed
bundle per framework, so a deployment giving a tenant its own framework pays
it again every time. The stack is one indivisible engine (the HL7 validator
converts everything up to R5, validates there, and maps back), which is
exactly what makes it a good thing to own once.

So: **one bundle owns the HL7/HAPI stack** — `dbo-fhir-stack` — and exports
it. A personality keeps its own code and the validation resources of its own
version (`hapi-fhir-validation-resources-rX`: R4 ships profile and value-set
XML under `org/hl7/fhir/r4/model/`, R5 ships NPM tarballs under
`org/hl7/fhir/r5/packages/`), exports those resource directories back, and
imports the engine. Version-keyed services are unchanged: a tenant's declared
FHIR version selects a personality, and nothing above it learns what HAPI is.

Two things cross that boundary, and a consumer picks which:

- **Canonical JSON**, for anything that wants no HAPI wire at all. This is
  the boundary rule unchanged, and it stays the only thing on dbo's own
  public API — enforced by `ApiBoundaryTest`.
- **The engine packages**, for hosts and personalities that would rather
  share class identity than re-parse. `org.hl7.fhir.*` and `ca.uhn.fhir.*`
  in full, from the one bundle that has them.

Exporting the engine whole rather than a narrow model-only slice is what lets
a personality keep calling `FhirContext`, `FhirValidator` and `IFhirPath`
directly instead of moving that code inside the shared bundle. The split-package
objection to a wide export does not apply to a single exporter: `org.hl7.fhir.*`
spans dozens of jars, but they are all inside one bundle, so there is exactly
one candidate for every package. What the wide export does demand is that the
list be complete — which is why it is read off the jars at build time, and why
the personalities' engine imports are read off *that* list rather than written
by hand. A missing entry resolves and then throws `NoClassDefFoundError` on
first use.

The reflection hazard argues the same way. `FhirContext` locating structures
by classloader-sensitive lookup is dangerous when it must see classes a
*personality* defines; here a personality defines none and imports them from
the bundle `FhirContext` itself lives in — one wire, one identity, nothing
to discover across a boundary.

R6 status: no released `hapi-fhir-structures-r6`; R6 normative ballot
started 2026-01, final publication 2027 at the earliest; draft R6 model
code lives in the `org.hl7.fhir.core` validator stack. So the R6
personality begins life on ballot-snapshot artifacts — exactly the
fast-moving dependency the bundle isolation is for.

**VERDICT: CONFIRMED — HAPI per personality works.** Every scenario
holds, on HAPI 8.10.1 and Felix 7 in-JVM:
R4 parse + FHIRPath (incl. identifier-extraction expressions), R5
coexisting with genuine divergence (R4 rejects `SubscriptionTopic`),
R4 profile validation flags structural errors, and the boundary rule held
— JSON in/out, zero HAPI types crossed the api.

Measured, with the engine shared and the authoring-side transitives
excluded: `dbo-fhir-stack` **95.7MB**, personalities **5.4MB** (R4) and
**23.2MB** (R5) — their validation resources and their own code. The whole
serving bundle set is 154MB, and a third personality costs its resources
rather than another engine. `FhirContext` init ~0.4s (R4) / ~0.8s (R5), lazy.

Landmine log:
1. **TCCL** — HAPI's cache-provider discovery (`HAPI-2200`) is
   ServiceLoader/TCCL-based; every personality entry point must run with
   the classloader that owns the engine as TCCL (a `withTccl` wrapper).
   That loader is asked for by way of a HAPI class, not the personality's
   own: with a shared stack the two are different bundles, and only the
   engine's loader carries its `META-INF/services`.
2. **R5 FHIRPath eagerly builds `DefaultProfileValidationSupport`** — pure
   path evaluation drags `hapi-fhir-validation` +
   `hapi-fhir-validation-resources-r5` (the base-profile npm package)
   into the bundle. Footprint and memory follow.
3. **Memory**: loading the R5 core-profile package OOMs a 512MB heap;
   2GB is comfortable. Personality memory budgets are real numbers, not
   rounding errors.
4. **Parse-time vs validate-time errors**: HAPI's parser rejects invalid
   required-binding codes at *parse* time (`HAPI-1821`) before any
   validator runs — the REQ-DBO-VER-SPECIFIED-VALIDATION semantics must
   define which errors surface at which stage.
5. **Finding**: the HL7 validator core is internally R5-based, so the R4
   validation path legitimately uses `org.hl7.fhir.r5` model classes. That
   is why the engine is indivisible, and why the R4 personality does not
   import the R5 model even though its validator walks it: the R5 classes
   are reached inside the shared bundle. The duplication was never in what
   a personality uses — it was in what the engine needs.

Still open (non-blocking): whether a leaner `org.hl7.fhir.core`-only
dependency (skipping the `ca.uhn` layer) is worth it per personality —
revisit when the R6 ballot personality is built, since that one starts
from the core stack anyway.
