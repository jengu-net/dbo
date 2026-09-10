# The face contract

**Status** — the epic is closed and the load-bearing slice landed: a second
face exists, so the engine's ignorance of FHIR is a build failure rather than
a claim. One slice remains open, and this document closes with it.

**Issues** — [#38](https://github.com/jengu-net/dbo/issues/38) (epic, closed) ·
delivered: [#106](https://github.com/jengu-net/dbo/issues/106),
[#107](https://github.com/jengu-net/dbo/issues/107),
[#108](https://github.com/jengu-net/dbo/issues/108),
[#109](https://github.com/jengu-net/dbo/issues/109),
[#110](https://github.com/jengu-net/dbo/issues/110),
[#113](https://github.com/jengu-net/dbo/issues/113) · open:
[#112](https://github.com/jengu-net/dbo/issues/112)

**Concepts** — [engine and faces](../arc42-008-crosscutting/engine-and-faces/README.md)

## What this is

`FhirStoreFacade` is what a face offers the world. The mirror — what the
*engine* requires *from* a face — existed only in pieces: the envelope
extractor on `TypeRegistration`, the codec in the personality, the query
compiler elsewhere. Unnamed, it drifts, and every new obligation lands wherever
the author happened to be standing.

The deeper claim underneath it is the one the epic existed to make true: **the
engine holds no FHIR knowledge.** It was an assertion while FHIR was the only
face — `EngineKnowsNoFaceIT` guarded it as far as one face allows, and nothing
would have noticed a leak that FHIR happens to satisfy. A second, non-FHIR
face now serves through the same engine, so a leak fails a build.

## Where it stands

`DomainFace` names the contract, capabilities are looked up by type, and the
three-tier question (version-scoped capability / tenant-scoped facade /
per-request fact) is decided and written down. A tenant whose face lacks a
capability its spec requires is refused at bring-up rather than mid-request.

**#108 is done.** A second face costs three capabilities and eight methods —
read a payload, say what type it is, the envelope, the codec — and serving one
still means a FHIR-shaped facade, which is accepted rather than overlooked;
[engine and faces](../arc42-008-crosscutting/engine-and-faces/README.md) says what
the count means and why it is not lower.

**#113 is done.** What may leave the store and what *this recipient* may see
are two questions, and a tenant can now say the second per recipient;
[who may act](../arc42-008-crosscutting/who-may-act/README.md) holds it.

**#112 is open**, and it is the last one: what counts as *one object* is a
face decision the contract has no place for. It is not blocked on anything
here — the second face is what made it testable rather than theoretical.

**#110 is done.** The spec field is called `face`, its old name is refused
rather than honoured, and the operator's schema no longer enumerates three
FHIR versions — so a face that is not one of them is expressible from the
CRD down. That was the last piece of the contract that was wire-visible, and
greenfield is what made it a rename rather than a migration.

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

Nothing deliberately excluded. What is left is one slice, and when it closes
this document is deleted: the decisions above already live in
[engine and faces](../arc42-008-crosscutting/engine-and-faces/README.md), and the
trap belongs in the promise catalogue's own notes.

## Verifying

```bash
./gradlew :core:harness:test --tests '*DomainFaceIT' --tests '*FaceRefusalIT' --tests '*EngineKnowsNoFaceIT'
```
