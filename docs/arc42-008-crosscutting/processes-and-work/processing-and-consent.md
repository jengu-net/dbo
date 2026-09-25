# What a tenant agreed to, and what happened

What a deployment may do with a tenant's data, said in advance as a register;
what it did, recorded regardless; and what the difference between the two
means. It rests on [one lane for the fleet](one-lane-for-the-fleet.md), and
the contract underneath both is [processes and work](README.md).

**Nothing here is built.** It is the model
[the contract](README.md) is growing into, written down beside it because a
reader who has just learned how work reaches a participant today deserves to
know which way it is moving. The delta against what exists, and the decisions
still open, are in
[the ledger item](../../arc42-011-risks-and-technical-debt/032-one-lane-for-the-fleet/README.md).

### Router or processor, and asking is what decides

**An application-level step that reads only the envelope is a router.** It routes on the
manifest, holds no key, and the store has disclosed nothing to it.

**The moment it asks for a payload it is a data processor**, and the store
records that it was. Every such access is recorded, travels home on the
feedback stream, and lands in the originating tenant's own data-access trail.

**The classification is derived, not declared**, and that is the whole point. A
processor that had to announce itself could fail to, and an application whose
category was a configuration value would be one where the category and the
behaviour could disagree. Asking for data is the act; being recorded as having
asked is its consequence.

**The entry belongs to the tenant**, is written through the port that writes
that tenant's trail, and names the **processor** as the actor — not whatever
carried the record home. A trail that named the carrier would answer "who saw
this" with the name of something that cannot read it.
### A tenant admits a step, or the deployment requires one

**Most application-level steps are admitted.** The tenant declares which of the
deployment's steps its work flows through, so the application still names no
tenant — what it performs for is decided by the tenants that admitted it, and
the joiner filters on their declarations. A subscription by step and a tenant's
consent are both kept that way.

**Some are required, and a tenant cannot refuse them and still be a tenant
here.** Retention that must be swept, an erasure that must propagate, integrity
that must be checked, work that must be metered: a deployment that could be
opted out of one tenant at a time cannot make any promise about itself. So the
refusal exists, and it is at the level of the system rather than the step —
declining means not being a tenant of this deployment. That is an agreement
about processing and operation, and it is signed by joining.

**Two different words, because they are two different facts.** A tenant's spec
already declares the steps *its* work cannot do without, and what that list
decides is classification: a mandatory step nothing contributes is an incident.
A required step is the other direction — the deployment's obligation on the
tenant — and it is declared where the deployment's own configuration is, never
in a tenant's spec. Nothing can quietly become required for one tenant, and the
set is one list a reader can enumerate.

**Required is not hidden, and enrolment is what makes that true.** A required
step enrols with each tenant exactly as an admitted one does. So its payloads
are sealed under that tenant's own enrolment and the thing in the middle still
holds no key; the step appears in the tenant's catalogue, marked as required and
naming what requires it; and every payload it reads lands in that tenant's own
data-access trail, naming the step. **A tenant that cannot refuse can still
account for every disclosure**, and it can read the required set before it
joins, which is what makes joining a choice. Cannot refuse never means cannot
see.

**And requiring a router is a smaller act than requiring a processor.** A
router reads only the envelope, so requiring one is operational. A processor
decrypts, so requiring one is data processing and the agreement names that step
specifically rather than covering it by category. Without that line, *required*
becomes the way anything obtains access.

**Required decides that a step runs, not what it may reach.** What it may work
on is still the intersection of what its credential covers and what the step
admits. Nothing here widens that, and a required step asked for something
outside it is refused by name like anything else.


### What a tenant consents to is a register, not a list of steps

A tenant does not read the deployment's steps to know what happens to its data.
Most of them never touch it. **What it reads is a register of processing**, one
row per application-level step that opens a payload, and nothing else is on it.

**A carrier is not on the register.** A step that routes on the envelope
discloses nothing, so listing it would pad the one document whose whole value
is that it is short. What a tenant needs to account for is who *opened*
something, and a register that also named everything that carried it would bury
that.

**Each row is a declaration made in advance.** A step that intends to open a
payload says so, and says it where it already says what it takes — a
declaration already names its slots and their types, so what is added is
whether the step opens a slot or only routes it. From that, one row: the step,
what it opens, whether the deployment requires it or the tenant may decline it,
and what requires it.

**Granted once, and for every tenant at once.** Enrolment is per tenant,
because that is what keeps the thing in the middle unable to read anything —
but a tenant should not perform it one step at a time. The register is
answered as a whole, and a tenant reads the whole of it as a whole: what the
deployment processes, which rows it may decline, and **whether anything has
changed since it last looked**. A change in what a deployment does with data is
one comparison rather than a diff somebody has to assemble.


### Declaring is not the same as being recorded

**The trail happens regardless.** Every payload a step opens is recorded and
lands in that tenant's own data-access trail, whether the step declared it,
whether it was granted, and whether anybody is reading. Recording is a
consequence of asking and nothing turns it off.

**So the register and the trail are two different documents**, and comparing
them is the point. The register is what the code said it would do. The trail is
what it did.

**A disagreement is an incident: the code does not follow the consent.** Two
shapes, one meaning:

- a step opened a payload it never declared;
- a step opened one under a row the tenant declined.

Neither is refused in flight. The application is enrolled, so it holds the key
and can open what it was sent — no cryptography can prevent a processor from
processing, and a store that pretended otherwise would be describing a
protection it does not have. What it can do is notice, name the step, and say
which tenant's data it was. That is the same answer this store already gives
for a mandatory step nobody contributes: the honest state, classified, rather
than an enforcement it cannot perform.

**Declining an optional row is a routing decision first.** Work whose
processing a tenant declined is not offered to that step, so the ordinary case
never reaches the incident at all. The incident is what remains: a step
reaching past what it declared, inside work it was legitimately given.


### What happens between a change and its approval

A register is answered once and then the deployment changes: a step that opens
a payload is added, or one already there begins opening more. Work arrives in
the window between the change and the tenant's answer, and something has to
happen to it.

**Only widening asks for an answer.** A row that disappears, or a step that
stops opening a slot, leaves a tenant with less to approve than it already
approved. Renewal is asked for when the deployment would process **more**, and
never as a formality — a register that asks to be re-approved for a narrowing
teaches everyone to approve without reading.

**And here refusal is genuinely available**, which it is not elsewhere in this
design. Whether a step declared what it opens is a fact about code the store
learns from the trail after the act. Whether a tenant has approved a row is
known **before the payload is sent**, and what is sent is sealed by the store
to the participant meant to open it — so declining to seal is a real refusal
rather than a request not to look.

**So a deployment states, once, what it does with work whose processing is not
yet approved.** Three postures, and the choice is itself part of the register a
tenant reads before it joins — a deployment that could change the posture
quietly would have made the whole register advisory.

- **Approved by the agreement.** The change applies and work continues,
  because the tenant's agreement already says the deployment may vary this.
  Legitimate only where that is actually what was signed, and it is the posture
  that costs the most to get wrong: the mechanism's entire value is that a
  change in processing is visible, and this one makes it visible after the fact.
- **Processed, and named as an incident.** Work continues and the store says,
  by name, that a step processed under a row nobody approved. Nothing is
  interrupted and nothing is quiet, which is the same answer this store gives
  wherever it can classify but not prevent.
- **Not processed until approved.** The payload is not sealed to that step, so
  the work is not offered to it. **It is not an error and the run is not lost:**
  it queues, as work waits here by design, and an incident names what it is
  waiting for. A deployment that must not process without an answer chooses
  this and accepts that an unanswered register stops work rather than widening
  quietly.

**A bounded window is the fourth shape**, and it is the two middle ones in
sequence: process and name it for a stated period, then stop. It is what a
deployment that can neither halt on a Friday nor process indefinitely without
an answer actually needs, and it says so as a duration rather than as a habit.

**Processed-and-named is what a deployment gets if it says nothing.** It is the
posture that neither stops work nor hides that work happened, and a default
that halted would make an unanswered register an outage caused by nobody
clicking. What it costs is that the unapproved case runs, so the incident is
the pressure: it names the step, the tenant and the row, and it stands until
the row is approved rather than being a notice that scrolls past.

**And a row may state its own.** The deployment's posture covers the rows that
state none, so a single new row that must not run before it is approved says
so, beside itself, without stopping everything else. A new row and a widened
one are different acts and a deployment may treat them differently.

**A row's posture is part of the row.** It is read with the register, and
changing it is one of the changes a tenant detects — because a deployment that
could move a row from *not until approved* to *processed and named* would have
found a way to approve its own widening. The register's protection is that
everything about it, including how it behaves when unanswered, is visible
before a tenant joins and never changes quietly afterwards.

**What none of them changes** is that the access is recorded. A payload opened
under an unapproved row is in the tenant's trail like every other, so whichever
posture a deployment takes, the tenant's account of what was read is complete.


