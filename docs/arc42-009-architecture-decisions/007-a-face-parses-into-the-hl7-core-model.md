**Status: Open.** Reflected in nothing yet.

# A face parses into the HL7 core model

[record 003](003-one-bundle-owns-the-fhir-stack.md) keeps the HAPI stack for what it decisively provides: parsing, validation,
and a FHIRPath engine per version, without which SearchParameter → envelope
extraction is not realistic. That remains true. What is worth separating from it
is the **model** — the typed objects a personality parses into.

Direction: parse into the HL7 core model (`org.hl7.fhir.core`) rather than
HAPI's structures. Three reasons, in order of weight:

1. **R6 exists there already.** The ballot model, its validator and its
   converters are published in that project before any HAPI release wraps them.
   [record 003](003-one-bundle-owns-the-fhir-stack.md) anticipated a personality tracking ballot snapshots and made packaging
   room for it; this is what that personality would parse into, and founding
   requirement R6 asks for the versions to be plural in fact rather than in
   principle.
2. **It is the reference implementation**, not a server framework's rendering of
   one. A store that means to be version-plural is better placed on the thing the
   versions are defined against.
3. **It shortens the distance to a face that is not FHIR.** The less a
   personality's model owes to one server's shape, the smaller the step to a
   sibling model, which is the second half of R6's ask.

**Not resolved — what has to be established first:**

- Which artifacts actually carry which packages. The `org.hl7.fhir.*` package
  names appear in both projects and the split is not obvious from the names; the
  answer decides whether this is a dependency change or a rewrite.
- Whether the validator and the FHIRPath engine can be driven from the core
  model without HAPI's `FhirContext` around them, and what that costs.
- What the R6 ballot artifacts are actually called and how often they move.

**What makes this cheap to be wrong about.** Payload is truth and stored bytes
are never rewritten, so the model is a lens over what arrived rather than the
form it is kept in. A representation that round-trips imperfectly cannot corrupt
storage, and a change of model is reversible — which is the reason to hold the
payload seam (§1) as bytes at the boundary and let a face parse into whatever it
likes behind it. That property is worth more than any particular model, and it is
the one thing here not to trade away.

Protocol-buffer models were weighed and are not the direction, though they are
the natural thing to weigh: they are compact and fast, and generated messages can
be shaped to a ballot or to a domain that is not FHIR. Against that, a generated
schema meets FHIR's extensibility awkwardly — extensions, primitive extensions,
contained resources, and elements no message has been generated for yet — which
is precisely the ground a store means to be exact about. If they are tried, the
way to try them is a face beside the others rather than under them, measured on
the production path, because that is what having faces is for.
