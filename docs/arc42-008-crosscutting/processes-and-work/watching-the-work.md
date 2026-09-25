# How work is watched

Three different questions, deliberately answered by three different things.
What work *is* and how it reaches whoever does it is
[processes and work](README.md).

## How it is watched

Three different questions, deliberately answered by three different things.

**"What is true right now?"** — the store. Runs are records, so what is claimable,
what is stuck and who holds it are ordinary queries against the tenant's own
data. Anything that acts on work reads this and nothing else. Across a
deployment the same question is asked by one process outside every container,
over the doors each node and tenant already serves: a node is asked what it is
serving and what it has installed under the deployment's own token, because
both answers name other tenants' existence; a tenant is asked about its work
under a credential its own authority minted, as envelopes and never payloads,
so the reader holds one credential per tenant and is never handed a surface
that crosses them. Every answer is labelled with the node it came from, nothing
is copied, and a node that did not answer is in the reading as unreachable
rather than absent from it — the missing node being the one an operator opened
the reading for. The union of the nodes' inventories is the network map, by
step and version: descriptive, and never a second declaration of a step, which
is why an inventory travels this way and not through the introduction door.

**"What does this node know how to do, and who would take it?"** — the console.
It lists the steps installed here and the steps a participant introduced, names
the contributor of each, and answers which executor would take a given step now
and why that one. It answers while serving no tenant at all, because the
catalogue is what is installed rather than what is running — and a node that has
stopped serving is exactly when somebody asks.

The reading is **sequential and bounded**: every ask has a timeout and every
outcome is recorded, so one dead node costs one timeout and one line rather
than a hung reading. Asking in parallel buys latency and pays with a second
failure mode; it is worth having when a deployment has enough nodes that a
serial read is slow, and not before.

**Looking and acting are separate, and so is the authority for them.** The
process that reads a deployment can also act on it, but only through the doors
a participant uses, and it holds the supervisory credential separately — often
not at all. An operator needs to look far more often than to act, and looking
must not require the authority to destroy somebody's work. This is why control
planes that bundle both into one channel read as mostly mutations: cancel,
delete, fork, restart. Acting here goes through the lane like every other act,
so the rule the lane enforces is met once rather than bypassed by the tool
built to supervise it.

**"How is the fleet doing?"** — telemetry. Counts, durations and outcomes leave
as labelled measurements for whatever collects them. This is lossy by design and
nothing decides anything on it; it is for trends and alerting, not for state. What
may be said there is a closed set, and a failure's own words are not in it: they
stay on the run, in the store of the tenant whose work it was.

Speaking somebody else's control protocol is deliberately not how any of this
is offered. Those protocols' verbs are overwhelmingly mutations, so an endpoint
speaking one holds cancel, delete, fork and retention rights over every
executor that connects — a large authority surface acquired in order to read
counters — and their metric payloads carry no labels, so nothing said here
could ride them. Nor is anything synthesised so an external engine can emit on
this store's behalf: those metrics are computed from durable rows, so
fabricated telemetry is fabricated state, with real ids, to which recovery and
replay then apply.

Where the numbers go is the deployment's to say, never the code's. The seam has
one exporter, installed everywhere and idle without an endpoint: given
`dbo.telemetry.otlp.endpoint` (or the protocol's own environment variables) it
carries counts as sums, levels as gauges and durations as histograms to an
OpenTelemetry collector as OTLP over HTTP, rendered and sent with the JDK's own
client so no protocol library rides in the container. Reporting is not a
dependency of serving: every verb updates an aggregate and returns, a flusher
posts on an interval, and a collector that is absent, slow or refusing is said
once and costs the caller nothing. The seam finds the exporter through the
framework, the way the logging binding is found, and a container proof asks
the seam what it found — because an exporter that resolved and was discarded
in silence is this repository's characteristic failure in its quietest form.

**"What happened to this run, and who read what?"** — the trail, and it is one
trail with two kinds of subject. A hop that carried the work leaves a **travel**
entry about the *task*: the journey belongs to the work. A participant that
opened a payload leaves an **access** entry about the *document*, landing where
every other reading of that document lands and naming the task execution as its
occasion. So *who has read this?* is answered from the document by somebody who
need not know work exists, and *where did this go?* from the task; the occasion
is the join. The machinery's own read to seal a payload records nothing, because
a read that yields only ciphertext is not a disclosure.

The entries of a run are chained, each committing to the one before, rooted in
the task the store minted — so a participant cannot present a journey that never
started, and a hop that skipped its own entry is exposed by the next, because
every travel entry names who it handed to. The result that closes the run is the
chain's last link and always was; the store checks the chain when the result
lands, and a completion with a gap is refused and told which link. What the chain
cannot do is compel a participant to send: an intended recipient can open a
payload and never say so, and that limit is accepted rather than hidden — the
data was legitimately theirs, and what is lost is the entry for an authorised
read on a device the tenant answers for.

The tenant wires a trail into its lane. A claim writes the hop on the task.
A participant that opens a sealed document says so from where its key is,
and that lands on the document as its access entry naming the run; for a
participant served in the clear, the read that resolves its inputs is the
opening and is recorded the same way, whatever the audit level. The store's
own read to seal is recorded as nothing.

Those entries are chained. Each carries the link it commits to and its own,
the first commits to the task the store minted, and the participant signs
the links it makes with the signing key it offered at enrolment — so a router
cannot manufacture an edge's opening and an edge cannot deny one. The result
that closes the run carries the head it commits to; the store walks the chain
when the result lands, and a completion whose chain has a hole is refused and
told which link, so the run stays owed under a named participant. A
predecessor retention pruned reads as unchained rather than broken. What the
chain cannot do is compel a link never made: an intended recipient can open a
payload and never say so, and that limit is accepted rather than hidden.

**The store's own housekeeping runs on this model rather than beside it.**
Notification delivery, retention, configuration application, tenant serving,
upstream sync — each is a declared process with runs like any other. That is a
visibility decision more than an implementation one: an operator asking what is
running sees the machinery in the same list as the domain work, with the same
counts and the same holders, and a retention pass that fails is a card somebody
can pick up rather than a line in a log.
