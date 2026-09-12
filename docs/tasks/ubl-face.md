# UBL is a face, described in FHIR's own tools

**Status** — two spikes, both green, both in the tree as tests. Nothing is
built. The two questions that could have killed the approach are answered, and
the one that remains is a policy call rather than a technical one.

**Issues** — none filed.

**Concepts** — [engine and faces](../arc42-008-crosscutting/engine-and-faces/README.md) ·
[the payload seam](../arc42-008-crosscutting/the-payload-seam/README.md) ·
[records you can rely on](../arc42-008-crosscutting/records-you-can-rely-on/README.md)

## What this is

OASIS UBL is a standard for the documents a business exchanges — invoice,
order, despatch advice — and in Europe it is the form an e-invoice takes: EN
16931 constrains it, Peppol carries it, and a growing number of member states
require it. It is a strong standard with divergent interests around it, which
is the shape of thing this engine exists for, and none of the engine's concepts
are clinical.

The question is not whether the store *can* hold an invoice. It is what a
second served domain costs. A face hand-written the way `GadgetFace` is written
costs three capabilities and eight methods, and then costs a search vocabulary,
a validator, a terminology loader and an outward surface that the FHIR face got
from its definitions rather than from code.

The idea this document exists to record is that **it need not be hand-written
at all**. A UBL document type is a `kind = logical` StructureDefinition, its
business rules are FHIRPath invariants, its code lists are CodeSystems and
ValueSets, its customisations are profiles, and its searchable paths are
SearchParameters. HL7 did exactly this for CDA, so the element model is proven
on a non-FHIR XML standard already. A tenant then declares `face: ubl-2`
alongside `fhir-r4` and `fhir-r5`, and the element face serves it with no new
Java at all.

## Where it stands

**The two spikes are done and are the reason this document exists.** Each ran
against the unmodified R5 element face, with UBL supplied as a tenant profile,
and each is left in the tree as a test so its findings are assertions rather
than recollection.

**Ancestor slots — answered.** A logical model parses by its type name, snapshots
against `Base`, validates cardinality and FHIRPath invariants, and is indexed by
SearchParameters, all through code written for FHIR. The store's `id` and `meta`
must be declared on the model: the token-copy serving path injects them whether
or not the model has them, but the projection path goes through the element
model and throws without them.

**Wire format — answered, and the shape is CDA's.** A UBL basic component is a
backbone element with a `value` child carrying the `xmlText` representation and
its qualifier carrying `xmlAttr`; namespaces are per-element extensions. With
that shape, real namespaced UBL XML goes in, JSON comes out as the stored form,
and UBL's own XML comes back — namespaces, qualifying attributes and written
decimal precision all intact.

**What is next** is generating the models, and nothing blocks it.

**What is open** is the signature question in *Not doing*, which decides what a
tenant may be promised rather than what can be built.

## Sequence

| # | step | status |
|---|---|---|
| 1 | **Ancestor slots survive a logical model.** Parse by type name, validate from the model, project a subset, index by SearchParameter. | **DONE** 2026-09-12 — `UblSpikeAncestorSlotsTest`, six assertions |
| 2 | **UBL's own XML crosses both ways.** Text-content values, qualifying attributes, three namespaces, a decimal's written precision. | **DONE** 2026-09-12 — `UblSpikeWireFormatTest`, five assertions |
| 3 | **Generate the document models.** From the OASIS model spreadsheets rather than the XSDs — the spreadsheets carry the CCTS cardinalities and component names the schemas only encode. One base logical model declaring the store's slots, every document type derived from it. | **NEXT** |
| 4 | **Invariants from the Schematron.** EN 16931 and the Peppol BIS rules are mostly presence, sum and consistency assertions, which translate to FHIRPath mechanically. Date arithmetic and the regex rules are hand work. | READY, needs 3 |
| 5 | **Code lists as terminology.** UBL ships genericode; a CodeSystem and ValueSet per list makes bindings validate without a line of code, and gives the face a grain codec it would otherwise have to write. | READY, needs 3 |
| 6 | **Search parameters.** UBL has no query language, so the face invents its vocabulary — issue date, supplier, buyer, amount, currency, document state — as SearchParameters with FHIRPath expressions. | READY, needs 3 |
| 7 | **Announce the face.** A `ubl-2` version registering the R5 core beside the UBL package, so a tenant spec can name it and bring-up can refuse what it does not provide. | READY, needs 3 |
| 8 | **The XML edge.** Rendering to UBL is not a straight compose: the ancestor slots have to come off, and a received document's own bytes have to be kept if signatures are promised. | READY, needs 7 |
| 9 | **Two faces in one tenant.** A provider issuing invoices wants clinical records and billing in one tenant. | BLOCKED by one face per tenant, which is the store's own gap — see *Not doing* |

**Critical path:** 3 → 7 → 8. Everything between is definitions, and definitions
are parallel work.

## Decisions

**UBL is described, not implemented.** The alternative is a hand-written face in
the shape of `GadgetFace`, which is honest and small but buys only the eight
methods: validation, terminology, search, profiling and conversion would each be
written again in UBL's terms. Describing the domain instead means the element
face — one implementation already serving three FHIR versions — serves a fourth
thing, and every obligation in the face contract is answered by a definition
rather than by a class. What makes this credible rather than hopeful is that HL7
already did it for CDA, against the same element model this store embeds.

**The stored form is JSON; UBL's XML is an edge rendering.** Storing the
received XML looks more faithful and costs more than it returns. The ancestor
rendering is a JSON token copy shared by serving, export and framing; document
equivalence canonicalises a JSON tree. Storing XML would fork both for one face.
The spike showed the JSON form loses nothing that matters: attributes survive as
children, namespaces live on the model rather than in the instance, and a
trailing zero on an amount survives, which is the case that would have decided
it the other way.

**The type name is prefixed, and the XML name is an extension.** R5 has an
`Invoice` resource of its own, and the JSON parser resolves the FHIR canonical
URL for a name before anything else. A document stored as an `Invoice` therefore
comes back as the R5 resource with every UBL element dropped — a silent,
complete data loss that looks like an empty document. So the type is
`UBLInvoice` and the model carries UBL's real root name as an extension. This is
the single most dangerous finding in either spike, because nothing fails.

**FHIRPath expressions are rooted.** UBL element names are UpperCamelCase, and an
uppercase name at the start of a FHIRPath expression is a type test rather than a
path step — so a bare `LegalMonetaryTotal.PayableAmount >= 0` evaluates to empty
and the invariant *fails on a conformant document*. Whatever generates invariants
from the Schematron emits `$this.`-rooted expressions. Asserted, because the
failure looks like a wrong document rather than a wrong rule.

**Customisation is the shape stamp, and the document already carries it.** FHIR
needed the store to write the stamp into `meta`; UBL states its own in
`CustomizationID`, and a Peppol BIS or an EN 16931 CIUS *is* a tenant-authored
profile. So the face reads the stamp rather than writing one, and shape
governance applies unchanged. The two-axes rule still holds: the customisation
is the shape, the payload version stays internal.

**A received invoice is `mirrored`.** It is somebody else's publication, held as
published and never corrected — a correction is a credit note, which is a new
object with a billing reference. Append-only history and the audit trail are then
what accounting retention law already asks for, rather than something added for
it.

## Traps

**A primitive typed element silently loses every value.** The first wire-format
model typed the UBL components as `string` and `decimal` directly. FHIR XML
carries a primitive in a `value` attribute and UBL carries it as element text, so
the parse succeeded, the document had the right shape, and every value was null.
Nothing errored. The `xmlText` representation on a `value` child is the fix, and
the shape of the trap is the point: a missing representation reads as an empty
document rather than as a broken model.

**`Meta.lastUpdated` has nowhere to go here either.** The ancestor checklist's one
unsaid fact stays unsaid in UBL for the same reason it is unsaid in FHIR, so
nothing about this face closes it.

## Not doing

**Signature fidelity, until somebody decides it.** A Peppol invoice can carry a
XAdES signature, and the JSON hop cannot preserve it: element order is normalised
to the model's sequence, namespace prefixes are reassigned, and any
reserialisation breaks the digest. The store's usual answer is that the payload
is the truth held as received, and here the truth is a transformed form. Two ways
out, both real: keep a signed document's received bytes beside the parsed form as
declared opaque extras, exactly as the
[neutral IFC repository](../plans/ifc-repository.md) proposes for a vendor's
unexpressible data; or state plainly that this face holds invoices and does not
attest to their signatures. The choice belongs to whoever first has a tenant that
needs one, and it is written here so that it is made rather than discovered.

**Order normalisation is not being fought.** UBL's schema sequences are ordered,
so coming back in model order makes a stored document schema-valid whatever
arrived. It is a gift everywhere except under a signature, which is the previous
paragraph.

**A second outward surface.** UBL over FHIR REST is deliberate, not a compromise:
Bundle pages, OperationOutcome, a CapabilityStatement listing the document types.
The face contract's argument about the five FHIR-named methods holds, and the
Peppol access point stays an edge, which is where a transport belongs. This is
the day that argument said to re-read it, and re-reading it, it survives.

**One face per tenant.** Step 9 is blocked on a founding requirement the code
does not yet keep, and this document is not the place to close it. What UBL adds
is a concrete reason to: a care provider's invoices are not FHIR `Invoice` or
`Claim`, because no tax authority accepts those. Until it closes, billing is its
own tenant with a zone dependency.

## Verifying

```bash
./gradlew :core:dbo-fhir-element:test --tests '*UblSpike*'
```
