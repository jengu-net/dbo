# Distributed work (§8)

How work reaches whoever does it, wherever they are — the second half of §8.
[`process-catalogue.md`](process-catalogue.md) says what a process, a step and
a run *are*; this says how one travels to a service in another pod, an
appliance in another building, a hospital's own system, or a person at a
screen, and how what they did comes back.

One mechanism carries all of it: a named feed consumer and a cursor. A
participant's backlog, its presence, and a peer's replication position are the
same primitive read three ways, which is why none of them needed observability
built for them.

## How work reaches whoever does it

A run says who holds it; a **participant** is how a holder gets it — a service, an
edge, a hospital's own system, a person at a screen. It is the change feed's
fifth use rather than a sixth mechanism: a named consumer, a cursor, an ack
(`REQ-DBO-FEED-ONE-PRIMITIVE`), which is also why each participant's backlog and
lag are observable without anything being built for them.

**Pull, never push.** dbo holding a client for every external system is the shape
ADR 0060 rejected, and participants are precisely the things behind NAT, on
edges, and offline for a weekend. Pulling makes an offline participant a lagging
cursor rather than an outage.

**What a participant may claim is the intersection of what its credential
covers and what the step admits**, and the two halves are enforced where each
belongs. The step's half is at the primitive, where the declaration is: the
baseline always may — it is not an override, it is the rule — and anything more
local may only where the step opened itself to that class (ADR 0059), so a step
cannot grant its executor more than the executor already holds. The credential's
half is at the lane, because the lane is the only door a participant has and
only the host knows what the credential covers: the entitlement narrows what
`poll` offers and refuses what `claim` may take. An entitlement is stated when
the lane is provisioned — *everything*, because the host is the tenant, or the
steps a credential covers — and there is no implicit unrestricted, so no remote
participant's reach depends on a parameter somebody forgot.

**A claim is a conditional write with a deadline.** At-most-one actor needs no
lease service: two participants racing one run produce one winner and one version
conflict, and the loser takes the next run rather than coordinating about this
one. The deadline exists because a participant that dies must not hold work for
ever, and nothing but the clock is going to notice. **The claim is also the dedup
point** — delivery is at-least-once, so a participant will see the same run
twice, and its own bookkeeping must not be what saves it.

**A claim is extended by checkpoint, never by heartbeat.** A tick proves a
process is alive, and what a deadline protects against is a process that is alive
and getting nowhere. Counts are the evidence, and they are on the record anyway.

**A checkpoint can name the milestone reached.** A long-running step is visible
between claim and outcome the way events ride a tracing span: the step declares
its milestones in order, the executor asserts only the name, and the store
derives the position over that order — a completeness nobody declared cannot be
derived, only invented, so an undeclared step's name is recorded verbatim with
no position, and a name outside a declared order is refused naming both sides.
The run keeps the milestone replaced-never-accumulated, across release and
retake, so the next taker resumes from a fact; the face says it in the Task's
`businessStatus` beside the holder ("validated, 2 of 3"). Nothing on the
reporting path may default it away: a lane or decorator that degraded a
milestone to a bare checkpoint would drop the one thing the report said while
passing every test.

**Released is not done.** A run that says done because whoever held it stopped
answering is the failure a deadline exists to prevent, so a lapsed claim is
handed back saying exactly that.

**One participant, embeddable, that automates nothing.** Every place that does
work needs the same three things — pull, claim, report — and none of them should
be written twice: a hospital integrating with dbo embeds a participant, not a
FHIR client plus a webhook plus a queue. Its job is to carry work to wherever the
work is actually done and carry the result back, and the run afterwards reads as
it would if dbo had done the work itself. An integration is not a second kind of
history.

**A host holds the lane, and it does not have to hold the store.** The lane is
built by the party that legitimately has the tenant's objects — that is what keeps
the runner's world to twelve verbs. But the party that *serves* work is not always
the party that *holds* it: an appliance running dbo in its own JVM builds a lane
over its own store, while a deployment where dbo is its own process — so that the
application never holds `CREATE DATABASE` — has no store handle to build one from,
and is exactly the side the appliances pull from. So the tenant serves the
participation verbs on its own private surface, guarded by its own authority, and
such a host holds a lane that reaches them. The runner cannot tell the two apart,
which is the same contract the interface already states.

Two rules keep that from being a wider door than the in-process one. **The
entitlement comes from the credential, never from the request** — the bare
participation scope is a host saying it *is* the tenant, a suffixed one bounds
the holder to the steps it names, and there is no implicit unrestricted. And **a
bounded credential works only as itself**: the executor identity is what a claim
is recorded under and what the input read is checked against, so a credential
free to spell any name could read the inputs of runs it never claimed. What is
offered over the surface is the lane and nothing wider: no verb takes a
reference, none hands back a store handle, and a widened primitive would be
available to every caller with the scope, for ever.

**Two layers own different failures.** Whatever runs the work locally owns local
durability — resuming its own half-finished work after a restart. The
participation client owns the global truth: what is owed, by whom, and what
happened. With only the first, work is durable and invisible to everybody else;
with only the second, a crashed runner loses its half. The contract line falls
between them, which is why **no orchestrator is named** in it — the same
participant runs as a service, on an edge with none, and as a workplace with a
person inside it, where opening a run is the claim and finishing it is the
report.

**You scale by adding claimants, never by relaxing the claim.** Partitioning is
the second lever and is not built: competition is fine at small N, and a
partition hint belongs on the run only once one step has measurably outgrown it.

## Who can run a step here

**An executor exists because something announced itself**, the way a face is
served because a bundle providing it is installed. A participant declares
process › step, scope, version and provider as a record in the tenant's store,
and resolution walks those declarations rather than this container's bundles — so
a local implementation and a hospital's own system are two candidates for the
same step, ordered by the overlay chain rather than by which machine they are on.

**A declaration is a claim to be a candidate, never a grant.** What a participant
may actually take stays the intersection of its scopes and what the step admits;
a step cannot grant its executor more than the executor already holds, and a
record saying otherwise does not change that.

**Presence is derived.** A participant is present while its cursor moves, so a
declaration whose consumer is behind and unmoving is *declared but not present* —
skipped by resolution, and shown to an operator as exactly that, which is a
different sentence from "nothing is declared". The trap worth naming: **a
caught-up participant's cursor does not move either**, so silence with nothing
waiting is not absence, and only silence with work waiting is.

## A trackable may route other trackables

**The topology is a tree, and only its root has a cursor.** A connected worker
reports for itself; it may also be a **router**, carrying the state of things
behind it — an appliance behind a connector, an instrument behind that — to
arbitrary depth. All of them are the same kind of thing: something whose state
is worth knowing. So there is one record shape and one row per trackable at
every depth, and the rule about what a state is exists once rather than once
per router. Three routers each inventing it would disagree, and the
disagreement surfaces as a question about a bench that nobody can answer.

**What a trackable IS stays outside the engine.** It knows that a trackable
may route other trackables, and no more — the same line the run record holds,
where the engine never says the word a face renders it as. A face may project
a trackable and its state onto whatever its version spells connected things
with; the fields are chosen so that projection is mechanical, and the word
still does not appear here.

**Trust is delegated down the chain.** The store has no independent path to a
routed trackable — everything it knows arrived through the router — so a
router is trusted about its routees exactly as it is trusted about itself. It
is enrolled and authenticated, and a router lying about what is behind it is
the same problem as one lying about itself. Each hop owns liveness for the hop
below it, with whatever protocol suits that hop: a serial timeout, a TCP
keepalive, an application ACK.

**So the store imposes no freshness rule on routed state**, and this is a
refusal rather than an omission. It has no means to evaluate one, and a single
threshold would be wrong anyway — an instrument on a serial line and an
appliance on a socket have nothing sensible in common to threshold on. Report
quality is the router's contract, and a router that reports badly is a fact
about that router.

**Presence stays derived where there is a cursor, and is attested where there
is not.** An attestation names **the worker that reported**, which is not
always the parent: a connector reporting an instrument two hops away is the
observer, while the appliance between them is where it sits. That is not
second-class trust — knowing which hop last saw something is what tells an
operator where to look, and "where it sits" and "who to ask" are different
questions.

**A tree reaches the store as a verb of the participation lane**, `routes`,
beside `declare` — one says what a participant can do, the other what it can
reach. The two alternatives were weighed and both fail on the same fact:
**vitals ride a declaration, and a declaration is keyed per process, step,
scope and name.** A connector declaring candidacy for two steps would carry
one fleet twice, and withdrawing either declaration would drop half of it. A
routed tree is not per step. The variant that changed no transport at all
also asked the engine to read inside a block it promises to treat as opaque,
which is the contract the vitals block rests on.

**The observer is stamped by the store from the lane's own participant, never
carried on the wire.** A router that named its own observer could send an
operator to the wrong hop while being the party accountable for the
instrument, so what a caller puts there is discarded rather than believed. An
attestation its reporter could forge is not an attestation.

**Ownership across routers is deliberately not decided.** A participant can
report a trackable id that another router also reports, and the later report
wins; the stamped observer is what makes that visible. Bounding it is a
question for the first deployment where two routers can genuinely see one
thing, and answering it earlier would be the store deciding what a trackable
is — the same line it refuses to cross with a freshness rule.

## A change belongs to a piece of work

A type may declare that **every change to it happens inside a run** — a
handling property like the others, refused by the engine when no run is in
scope. The point is not visibility: history already has the change and audit
already names who made it. The point is that it **belongs** to something, so
what happened is one record rather than an assembly job across two that were
never designed to agree.

**A run then names the versions it produced**, which is what makes work the
*manifest*: reading runs in order reads the content changes in order, and
another appliance asks for exactly what it is missing instead of comparing two
stores. Bounded, because a manifest is an enumeration and children are
exceptions — individual versions up to a cap, a per-type high-water mark past
it, and the run says which of the two it is. A run that stopped naming and did
not say so would let a reader believe it had everything.

**The engine cannot record this itself.** A store writing into the work domain
on every content write is the engine re-entering itself, so the recording is a
decorator: the write commits, then the run is told. The honest limit is a crash
between the two — a version the run does not name, which a far side then reads
by cursor rather than by manifest, and is behind rather than wrong.

**Bulk paths are runs, not exemptions.** An import, a restore and a replication
apply open a run and write under it, which is better than being excused from the
rule: they then appear in the same list as everything else, and what they
changed is as answerable as anything else.

## Two appliances of one tenant

An edge and its cloud hold **one tenant on two appliances** — same code, same
declarations, different local settings (ADR 0062). So a lane between them is
same-version replication: no converter chain, and the stored bytes travel as
they are.

**dbo builds no channel.** It hands a caller a batch and takes one back; a
connector outside dbo carries the bytes, authenticates and reconnects. What is
dbo's is store-level and nothing else: what the far side does not have, an apply
that is idempotent under replay **and safe under reorder** (source version wins,
so neither property depends on the connector being careful), the **epoch** that
makes a cursor resumed from a restored copy detectable, and the **marker** each
side keeps about where the other said it had reached.

**Data before work**, so nothing arrives pointing at something absent. **Bounded
by what the work names**, never by following references as far as they go —
Patient → Encounter → Observation → everything is how a bench ends up holding a
register.

**A record arrives with a piece of work and leaves with it.** What brought it is
noted, and a revocation pass removes what no open run still names — locally,
because the appliance holds the runs and can see for itself, and because a
withdrawal that had to arrive would leave a bench holding a register every time
the link was down. A card still open counts as work still needing the record;
somebody has to be able to look at what they are fixing.

**The side that authored a run is the side that advances it.** A mirror is a
read-only account of somebody else's work: it can be read, counted and compared,
and it cannot be claimed, checkpointed, released or closed where it landed. The
reason is the lane's own latency — across two stores "the deadline passed" and
"the checkpoint is in flight" can both be true at once, and a peer acting on the
first has the work done twice. So a deadline is judged only where the run lives,
and the housekeeping sweep skips what it did not author rather than refusing it.
The consequence is accepted rather than hidden: an appliance that dies holding
work it authored keeps that work until it returns, and moving it is an
operator's deliberate act rather than something a clock infers from a lane that
is merely behind.

**An appliance offers only what it authored.** The other half of the same rule,
and the one a pair discovers the hard way: a mirror sent back is a *new* record
at the far side — filed under the sender, prefixed again — so two appliances
that echoed would deepen a key and add a run every round, without bound. What
arrived from elsewhere does not go back out, which is the rule the replicated
trail already obeys.

**A mirrored run is filed under the appliance that authored it.** Two appliances
running the same task write the same run key, and without the namespace the
second arrival silently replaces the first — which is exactly the comparison
this makes possible: *applied 46/46 here, 44/46 there, same correlation*.

**The lane declares which processes travel.** `dbo.config.applied` does, because
its outcome is the tenant's business on every appliance. `dbo.tenant.serving`
does not: an appliance's account of its own bring-up is housekeeping, and
mirroring it would put an edge's answer to "what is serving" into the cloud's.

**A step declares the actions it contains.** Open a task, close it, reopen a
closed one. Roles narrow *actions within* a step — an operator works the open
tasks, a supervisor also reaches the closed ones — so without declared actions
there is nothing for a role to narrow. It is also what "held by a person" means:
manual is the baseline here, and what that person may do is exactly the set an
automated executor would otherwise perform.

**And a report lands through them.** Closing and reopening are acts of
judgment, checked at the primitive against the step's declaration — a step
whose actions omit `close` has said its closure is somebody else's act, and a
participant reporting done there is refused naming both sides. A step that has
not declared actions is not narrowed (empty means "has not said", never
"admits nothing"), and **releasing is never narrowed**: released-is-not-done
is failure honesty, and a step must not be able to refuse to hear that its
executor failed. A closed run reopens the same way — through the declared
`reopen`, claimable again with the reason on the record, instead of a second
run invented to disagree with the first.
