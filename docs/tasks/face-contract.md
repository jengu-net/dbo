# The face contract

**Status** — active but dormant; three slices delivered, four open, and the
open ones are the load-bearing ones.

**Issues** — [#38](https://github.com/jengu-net/dbo/issues/38) (epic) ·
delivered: [#106](https://github.com/jengu-net/dbo/issues/106),
[#107](https://github.com/jengu-net/dbo/issues/107),
[#109](https://github.com/jengu-net/dbo/issues/109) · open:
[#108](https://github.com/jengu-net/dbo/issues/108),
[#110](https://github.com/jengu-net/dbo/issues/110),
[#112](https://github.com/jengu-net/dbo/issues/112),
[#113](https://github.com/jengu-net/dbo/issues/113)

**Concepts** — [engine and faces](../arc42-008-crosscutting/engine-and-faces.md)

## What this is

`FhirStoreFacade` is what a face offers the world. The mirror — what the
*engine* requires *from* a face — existed only in pieces: the envelope
extractor on `TypeRegistration`, the codec in the personality, the query
compiler elsewhere. Unnamed, it drifts, and every new obligation lands wherever
the author happened to be standing.

The deeper claim underneath it is the one the epic exists to make true: **the
engine holds no FHIR knowledge.** Today that is an assertion: `EngineKnowsNoFaceIT` guards it as far as one face
allows, but the only production face is FHIR, so nothing would notice a leak
that FHIR happens to satisfy.

## Where it stands

`DomainFace` names the contract, capabilities are looked up by type, and the
three-tier question (version-scoped capability / tenant-scoped facade /
per-request fact) is decided and written down. A tenant whose face lacks a
capability its spec requires is refused at bring-up rather than mid-request.

The open four are where the assertion gets tested:

- **#108** — a second, non-FHIR face, which is what turns "the engine holds no
  FHIR knowledge" from a claim into something a build can fail on.
- **#110** — `fhirVersion` in the tenant spec, for a face that need not be FHIR
  at all. Named wrong, and the name is load-bearing config.
- **#112** — what counts as *one object* is a face decision the contract has no
  place for.
- **#113** — what may leave the store is not the same question as what *this
  recipient* may see.

Shape versioning added a fifth capability (`ShapeConversion`) to the contract
without any of this settling first, and it fit — which is mild evidence the
type-keyed lookup was the right shape.

## Decisions

**Lookup is by capability type, not a method per capability.** That is the
answer to how the contract evolves: a new capability is a new type, and faces
that do not provide it are unaffected rather than broken.

**A capability is a pure transformation** — data in, data out. None reads the
store. Anything that needs to *act* is a facade, and anything that needs the
request is a per-request fact. Written down because the alternative was
arguing it per obligation.

**Version-scoped versus tenant-scoped is a real distinction, and it bites.**
Shape conversion looked like a version-scoped capability and is not: converters
ship in the *tenant's* pack, so a conversion resolved against the shared
definitions answers "no converter" about maps the tenant is holding. When a
capability needs tenant content, it is reached through the facade.

**A missing capability is refused by name.** "Unsupported" and "misconfigured"
look identical from outside, and a caller that cannot tell them apart cannot
act.

## Traps

**`getDeclaringClass()`, not `getClass()`, on an enum constant.** A constant
with a body is an anonymous subclass and does not carry its enum's annotations.
Cost an hour in the promise catalogue; the same shape will recur anywhere the
contract reads annotations off constants.

## Not doing

Nothing deliberately excluded — this topic is paused rather than scoped down.
Its open slices are ordered by #108, which the other three lean on: a second
face is what makes the rest testable rather than theoretical.

## Verifying

```bash
./gradlew :core:harness:test --tests '*DomainFaceIT' --tests '*FaceRefusalIT' --tests '*EngineKnowsNoFaceIT'
```
