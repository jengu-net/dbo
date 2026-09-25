**Open, and not started: a proposal awaiting approval. The desired state is
described in [processes and work](../../arc42-008-crosscutting/processes-and-work/README.md)
under "Where this is going". This item is the delta against what is built, the
decisions that have to be made before anything is, and nothing else. No
implementation plan is agreed.**

# One lane for the fleet, and two levels of step

## What is already true

More of the model exists than its absence suggests, which is why this is a
delta rather than a design from nothing.

- **One participant already serves many tenants without reading any of them.**
  The manifest is readable and the payload is sealed to whoever opens it, and a
  carrier holding no key is the existing rule rather than a new one.
- **The lane already has three carriers** — in-process, HTTP, and the store's
  own stream — and a runner cannot tell which it holds. So changing how work
  arrives changes nothing a step service sees.
- **The stream carrier already runs on the deployment's substrate**, with a
  door per served tenant, guarded by the same authority and participation scope
  as the HTTP door, and the plane between carries no credential.
- **Two catalogues already answer "is that a step"**: the tenant's own spec at
  the step door, and the composed catalogue — installed steps plus those a
  linked participant introduced — at the face's run document.
- **The managing tenant already exists** and is already the one the store keeps
  its own history in, named in configuration rather than watched, so the loop
  that retracts undeclared tenants cannot retract it.
- **Disclosure is already recorded with a reason**, and the trail already names
  the run a disclosure happened under.

## What does not exist

| the desired state | today |
|---|---|
| a step defined once performs for every tenant | a lane is per tenant; an application names each one it performs for |
| one unified stream at the managing tenant | a door per served tenant on the substrate, and nothing lifts work between tenants |
| a joiner, and a splitter | neither; no component moves a run out of its tenant or a result back in |
| a feedback stream applying outcomes to the originating tenant | the participant reports over the same lane it claimed on, to that tenant directly |
| execution state in the durable layer | the runner holds its cycle in memory; what survives is what the tenant recorded |
| a reduced account of execution in the managing tenant | nothing; the manager level asks each node and tenant over their own doors |
| router and processor as derived categories | a participant is sealed to or served in the clear, decided by whether it enrolled a key — not by what it asks for |
| a data-access entry per payload access, carried home | disclosure is recorded where it happens, under the request's purpose |
| two levels of step definition | one level, per tenant, in two catalogues |
| a register of processing a tenant reads as a whole | nothing: a participant enrols, and no document says what it opens |
| a step declaring that it *opens* a slot rather than carries it | a declaration names its slots and their types, and nothing distinguishes opening from routing |
| an incident where the trail disagrees with the register | incidents exist where a mandatory step is uncontributed; nothing compares an access against a declaration |
| enrolment answered for a whole deployment at once, and change detectable in one comparison | enrolment is a participant offering two keys, per tenant, one at a time |

**And one piece of plumbing is missing underneath all of it.** The Spring
worker assembly builds an HTTP lane and nothing else: `dbo-stream` is absent
from its bundle set, and its properties carry no substrate configuration. So
today even a worker inside the deployment polls over HTTP — which is
[item 031](../031-a-worker-in-the-deployment-takes-the-substrate/README.md),
and is a prerequisite rather than part of this.

## The decisions, before anything is built

Each of these changes what gets written. None is settled.

**1. Where the claim lives once work is joined.** Today a claim is a
conditional write in the tenant's own store and that is what makes it the
scheduler. Two candidates, and they fail differently:

- the claim stays in the tenant and the joiner holds it while the unified item
  is in flight — one authority, and the joiner becomes something that can die
  holding claims on many tenants' work;
- the claim moves to the unified stream and the tenant's row becomes an account
  — two places that can both believe they are advancing a run, which is the
  hazard this chapter already names for two sites of one tenant.

The first keeps the existing invariant. It needs the joiner's own liveness to
be a first-class thing, because its lapse is now many tenants' problem.

**2. What a unified processor is enrolled with. Settled: per tenant.** A
payload is sealed to an enrolled participant, so enrolling once at fleet level
would mean something re-seals a tenant's payload and therefore holds tenant
keys — which is exactly what the carrier rule exists to exclude. Per tenant
keeps the managing tenant unable to read what it moves, and makes a tenant's
authorisation a real act rather than a setting.

What follows from it is a UX obligation rather than an open question: a tenant
must be able to do it **all at once**, and to see in one comparison whether
what a deployment does with its data has changed since it last looked. Enrolment
being per tenant must not become enrolment one step at a time.

**3. What may be required, and what the word costs.** Settled in shape: a
tenant **admits** most application-level steps, and the deployment **requires**
a few. An admitted step keeps the subscription by step — the joiner filters on
what tenants declared, so the application still names none of them. A required
step cannot be refused per step; the refusal is per system, and declining means
not being a tenant here. It is an agreement about processing and operation,
signed by joining.

What is open is the boundary, and it matters because *required* is the word
everything will want. Two constraints are proposed:

- **Required is declared with the deployment's configuration**, never in a
  tenant's spec, so nothing becomes required for one tenant quietly and the set
  is enumerable. It also must be readable before a tenant joins, or "if it wants
  to be in the system" is not a choice.
- **A required processor is a heavier act than a required router.** A router
  reads only the envelope, so requiring one is operational — retention sweeps,
  erasure propagation, integrity checks, metering. A processor decrypts, so the
  agreement names that step specifically rather than covering it by category.

**And the word collides with one already in use.** A tenant's spec already
declares `mandatorySteps` — the steps *its own* work cannot do without — and
that list decides classification: an incident where nothing contributes one.
Required is the other direction and must not reuse that field, or an obligation
and an incident become the same declaration.

Required decides **that** a step runs, never what it may reach: the
intersection of credential and step stands, and enrolment still happens per
tenant, so a tenant that cannot refuse can still account for every disclosure
in its own trail.

**4. What the reduced account holds.** Explicitly undecided, and the constraint
is easy to state even before the fields are: nothing about a person, because a
managing tenant is not a place identifying data goes, and nothing a tenant's own
record is the answer to, because a second place to ask is a second answer.

**5. The register, and what a row is.** Settled in shape: what a tenant reads
is a register of processing — one row per application-level step that opens a
payload — and a step that only routes on the envelope is not on it. A row is a
declaration made in advance, made where a step already declares what it takes,
so what is added is whether a slot is opened or only carried.

Open: **the granularity of a row**. A slot is the obvious unit because it
already exists and already names a type. Whether that is enough — against, say,
declaring which elements of a document are opened — decides how precise an
incident can be, and a register nobody can read is worth as little as one
nobody can act on.

Open too: **whether "consent" is the word.** It is the tenant authorising
processing under an agreement, not a data subject's lawful basis, and the two
are different things that the word does not distinguish. The concept is right;
the risk is a tenant-facing screen that invites the legal reading of a term
being used in an operational sense.

**6. What a joined item is.** A copy in flight, bounded by the work that caused
it, is the existing shape for a sealed payload and is probably right here too —
but a run that is also a row in a managing tenant needs its lifetime stated:
when it is removed, what happens to it on retraction, and whether an erasure
reaches it.

## What is deliberately not proposed

**Not a second answer to "what is true right now".** The tenant's own record
stays it. A unified layer that could be asked instead would be a cache of
work-in-progress, and the chapter's whole answer to how this is watched rests on
there being one place.

**Not an orchestrator.** Nothing here names one. The unified stream carries
work that was authored on a tenant's own surface, in the order claims settle,
exactly as a lane does today.

**Not a replacement for the HTTP lane.** It is how another organisation's
application participates, and the tenant-level step stays for that reason.

## What has to be true before this starts

- Item 031: a worker in the deployment can take the substrate at all.
- Decisions 1, 2 and 3 answered, because each changes what is written rather
  than how.
- And the thing to prove first, before a joiner exists: that a step service
  reached over the stream and one reached over HTTP are indistinguishable in a
  deployment, which the harness proves for the lane and no test proves for an
  application built on the assemblies.
