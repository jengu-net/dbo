# Reaching the data

## What this is about

Every other document here answers what the store holds and what it promises
about it. This one answers a narrower question that decides the shape of the
whole surface: **by what route does anything get at a tenant's records?**

A store that answers any well-formed question from any credential that holds a
broad grant has to solve authorisation, purpose and accountability three times
over, separately, in code, for every surface it grows. This document describes
the arrangement that solves them once.

**This is a design, not a description.** What exists today is stated at the
end. It is written down now so that the surfaces added later have something to
be consistent with, rather than a rule inferred afterwards from whatever was
built first.

## The invariant

> Nothing touches a tenant's records except under an authorisation that a
> running task confers — or for one of the enumerated reasons below, each of
> which exists because a task is *impossible*, not because it is inconvenient.

A **task** here is the unit the [work concepts](../processes-and-work/README.md)
already define: a run of a declared step. The claim is not that the store grows
a new mechanism, but that the mechanism it already has becomes the only way in.

The invariant is only as strong as what a step declares. A step that names the
*types* it may touch has granted those types: a context declaring `Observation`
and nothing more can be asked for every observation the tenant holds. So a
declaration has to carry an **anchor** — what this run is about — and the
**reach** from it: along which references, to what depth, bounded by which
types. Without that, step-scoping is type-level access control wearing a step's
clothing, which is weaker than a decent role model and worse for being mistaken
for a boundary.

## What a step buys, and why it is worth the constraint

Three things arrive together, and that is the whole argument:

**Authorisation.** The step declares what data it needs and which role may
perform it. A request is admitted because a step admits it, not because a
credential was broad enough. There is no second permission model to keep in
step with the first.

**Purpose.** The step *is* the lawful basis. A read that happened inside a run
of *dispensing a prescription* carries its reason with it, rather than having a
reason reconstructed for an auditor months later from a timestamp and a user
id.

**Accountability.** The trail names the run, so every disclosure has a *why*
attached and not merely a *who* and a *when*.

Those are exactly the three things a general-purpose CRUD surface cannot give,
because at the moment of the call it knows only the credential and the query.

## Two planes

The surface divides in two, and the division is the useful part.

The **data plane** is step-addressed. Every call names a step; every access sits
inside a run. This is the integration surface: what an external system uses.

The **control plane** is what remains — and it is *not* defined as "everything
that is not personal data". It is defined by the list below, which is shorter.

## The data plane is a FHIR root

The step context is not a bespoke protocol. It is a **base URL**, and under it
the ordinary resource paths work: an off-the-shelf client pointed at the
context talks to it without knowing that a run exists. Relative references
inside a payload — `Patient/123` — resolve within the context, which is what
makes documents hold together rather than becoming a bag of ids.

What the context serves is what the step declared, so the accessible types
*are* that context's capability statement — generated from the declaration, the
way a tenant's is generated from what it holds. An integrator reads it to find
out what this context answers, and that is the whole of the contract.

A reference that leaves the declared types, or leaves the anchor's reach, is
simply not resolvable here. Nothing errors and nothing is special-cased: the
document is as complete as the step's authority over it, and no more.

Three decisions follow from that, and each should be made rather than
inherited.

**Whether an out-of-scope reference is visible but unresolvable, or absent.** A
reference is itself information: a performer reference says a practitioner took
part, even when it cannot be fetched. Sealing already means a person usually
appears as a pseudonym, which softens it — but a step should be able to say
which of the two it wants.

**Not-accessible must be indistinguishable from not-existing.** If out-of-reach
answers one status and absent answers another, the boundary confirms existence
to anyone willing to probe it. The store takes this position already elsewhere:
no authority answer distinguishes a subject that exists from one that does not.

**Reads degrade, writes refuse.** A read of something out of reach can be
nothing at all. A write that *references* something out of reach has to be
refused, or the context becomes a way to smuggle links into records. The
asymmetry matches what the store already does with referential closure, where a
restore that would leave records pointing at nothing is refused rather than
warned.

One consequence is worth knowing before it surprises somebody: the context
belongs to a run, so its URLs stop working when the run ends. A record's stable
identity is tenant-rooted, which means the canonical url of an entry in a
bundle should stay tenant-rooted too — otherwise one record has as many
identities as the runs that have touched it.

## What is not an exception

Two things look like exceptions and are not, and confusing them makes the rule
seem stricter than it is.

**Direct access inside a run is the mechanism working.** A runner performing a
step reads and writes records directly. That is what the task authorised. The
exceptions below are acts that happen with *no task at all*.

**Creating and removing a tenant is a task.** It looks like an operator act
outside the model, but it is recorded in the managing tenant, which is where
the regress dissolves.

## The five reasons a thing may be direct

**The floor.** A task must be stored somewhere. Tenant creation is recorded in
the managing tenant; the managing tenant's own creation has nowhere to be
recorded. The source a deployment reads when it has no managing tenant is the
floor every deployment starts on, and it is direct because nothing exists yet
to record the act.

**Self-reference.** The machinery cannot be its own client. Enqueuing a task is
a direct write — an enqueue cannot be enqueued. Claiming and completing are
direct updates to the task's own record. Writing an audit entry is direct,
because an audit entry recording the writing of an audit entry does not
terminate.

**Forming a task.** A caller must read something in order to construct a valid
one: which steps exist, what shape each expects, what a valid code is. If
reading the contract required a task, the first task could never be formed.
This covers the capability statement, the process and step definitions, and the
profiles a step declares.

**Diagnosis.** Whether the machinery works cannot depend on the machinery. If
liveness were a task, a wedged engine would be undiagnosable: the symptom and
the broken instrument would be indistinguishable.

**Recovery.** Repairing the mechanism when it is broken. This is break-glass
for the machinery rather than for clinical data, and it is loud by
construction: time-boxed, recorded as its own kind of event, reviewed after.

## The test, and why the list stays closed

A candidate joins the list only by answering: *what prevents this from being a
task?*

The pressure to keep the answer honest is not tidiness. **Every direct
operation is hand-written authorisation and hand-written audit** — complexity
maintained in code, forever, and wrong the first time somebody adds a surface
and forgets. Task-based access is declared, and gets both without a line of
code. So the list is short because each entry is paid for in maintenance, not
because short lists are elegant.

This cuts against intuition in one place worth naming. Reading the audit trail
is the most sensitive read in the store: it says who saw whom. That is the
surface where hand-written access control is least wanted, which argues for the
trail being read through a declared step rather than through a direct door —
even though writing to it must stay direct.

## Synchronous and asynchronous

A task implies a queue only if the design says so, and this is the decision
that determines how long the direct list is.

A step declares whether it is **synchronous** — executed inline, answering in
the response — or **asynchronous**, queued for a runner to claim. The
declaration, the authorisation and the audit are identical either way; only the
transport differs.

With synchronous steps, the direct list stays at the five above, and everything
interactive — a record read, a search, an auditor's query — is a declared step
that happens to answer immediately.

Without them, being interactive becomes a sixth reason to be direct, and it is
a large one: it pulls in every user-facing read, each carrying its own
hand-written authorisation and audit. **That is the cost of deciding that tasks
are always queued**, and it should be paid deliberately if it is paid at all.

## What a direct operation carries

Whatever ends up on the list pays the same four-part price, which is what stops
it growing quietly:

- it is enumerated here, not discovered in code;
- it carries its own scope rather than riding a broad one — the erasure scope
  is already built this way, for exactly this reason;
- it is recorded, even where it is not recorded as a disclosure;
- its reason is stated in one sentence naming which of the five it is.

## What must not become a door

A boundary is only as good as its narrowest crossing, and two surfaces can
quietly become general read access if they are treated as administrative
conveniences.

**Feeds and subscriptions** deliver record content. If a feed sits on the
control plane, every integrator takes the feed instead of the data plane and
the boundary is decorative. What flows must be declared the way a step declares
its reach.

**Content held beside records** — documents, images — is record data. It
belongs on the data plane, under the same run, and reachable by the same
erasure.

### Running inside the container is not an answer

The question arrives the first time something *inside* the runtime wants a
feed: a bundle beside the store could open the tables directly, so what does a
feed hand it that it could not already have?

Nothing — and the argument still fails, because it proves too much. It would
excuse every surface the runtime grows, including the general records door this
document exists to close. It is also an answer about **trust**, and the three
things a step confers are authorisation, purpose and accountability. A bundle
being trustworthy supplies none of them: a feed it consumes has no run to name
in the trail, and no reason attached to what it saw.

So the discriminator is not *where the code runs*. It is **whether what flows
was declared**:

| | declared? | |
|---|---|---|
| a run's inputs, delivered to a runner | yes — they are what the step named | inside, by construction |
| a domain feed of record content | no — the domain is handed over whole | a crossing |

A queued or carried task is safe for a reason that has nothing to do with
trust: its payload is exactly the reach the step declared, so the delivery
*is* the declaration being honoured. A feed has no such bound.

### A feed says what changed, not what it says

The obvious repair — let an observer declare the types it may receive — is the
one this document has already refused, a few paragraphs up: a declaration of
types with no anchor is *"type-level access control wearing a step's clothing,
which is weaker than a decent role model and worse for being mistaken for a
boundary."* A feed cannot carry an anchor, because a feed is not about
anything; it is everything, in order. So it can never reach the bar, and
pretending otherwise would put the decorative boundary back with a declaration
stapled to it.

What a feed may carry is therefore **the fact of a change and not its
content**: the type, the identity, the version, when it happened, whether it
was a deletion. A consumer that needs the record performs a step and reads it
in the run context, where the three things arrive together as they do for
everybody else.

This is not a loss of function. A feed is for noticing, and most consumers of
one want to notice: an encounter closed, a document arrived, a claim is ready
to bill. The ones that genuinely need every payload were the ones taking the
feed instead of the data plane, which is the case this rule is about.

Two domains are exempt, and each says which of the five reasons it is:

- **`work`** is *self-reference*. Runs, claims and closes are the machinery's
  own bookkeeping, and a task describing the delivery of a task does not
  terminate.
- **`identity`** is the same: credentials, delegations and provisioning are how
  a task comes to be authorised at all, so requiring a task to observe them is
  the regress that reason exists for.

**`audit` is not exempt**, and it is the one where the temptation is greatest.
This document already says reading the trail is the most sensitive read in the
store, because it says who saw whom — so an observer of it learns that an entry
was written, and reads it through a step like anyone else.

## Where this stands

**One slice is built.** A tenant declares the steps it offers beside the types
it holds; a run is started over a document per slot; and a run context serves
those documents and answers not-found for everything else, including a document
of a declared type the run was not given. A credential holding the work scope
enters that context and is refused by the tenant's own records surface.
`PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS` is the promise, and the boundary's
sharpest property is asserted directly: a withheld record and an invented id
answer identically, so the door cannot be used to discover what a tenant holds.

What is still true of everything else:

- A credential holding `system/*.read` reads any record with no step and no run
  anywhere in the picture, and that is how every guide chapter but one works.
  The general surface is a door this slice did not close.
- `work` is a single scope rather than one bounded to a step, so a credential
  that may enter one run context may enter another.
- Reach is what a run names and nothing is followed from it, so the anchor
  exists and the traversal described above does not.
- The run context is read-only.
- The participation surface a host obtains is still the replication lane's
  verbs rather than this.

So the rule now holds on one surface and nowhere else. The sentence in the
regulation mapping — that there is no way to reach the data without performing
the work that needed it — describes that surface and does not yet describe the
store.

[The implementation status](https://github.com/jengu-net/dbo/blob/main/docs/plans/implementation-status.md)
is the honest record of what is built; where it and this document disagree, it
is right.
