# US-DBO-ON-THE-STREAM — a lane held over the deployment's own substrate, and nothing readable left on it

> Meristem's synchronisation service already runs beside a deployment of
> this store, on the same Postgres the deployment keeps its durable
> workflows in. Opening an HTTP port into every practice's tenant is a
> second thing to secure, and the substrate is already there.
>
> So the service connects to the substrate and to nothing else. It claims
> work, reads what it needs and closes the run exactly as it would over
> HTTP, and its step code cannot tell the difference. What it has to be
> sure of is the other side of that convenience: the substrate is a plane
> every tenant's work crosses, and nothing readable may be left on it.

## The scene

Ines, Meristem's builder, runs the deployment with a substrate: a database
the runtime is given before any tenant is served, so every tenant's door to
the stream opens with the tenant. Three participants take part:

- an **analyser**, enrolled with its own keys, performing an assay;
- an **imager**, enrolled with its own keys, whose scans are large;
- a **courier**, which carries work and holds no key at all.

## The same work, whichever carried it

Ines points one runner and one step service at two lanes: one over HTTP and
one over the stream. Two runs of the assay go out, one on each. Both come
back closed, with the same tally, and the step service was handed the same
document on both. The hop over the stream is on the task's trail like any
other hop.

The stream is full duplex. Work goes out sealed to the analyser, the
analyser opens it with the key it holds, and the signed account of that
opening comes home on the same channel and lands on the document as a
reading, beside the travel entry on the task.

## Told there is work

A runner on the stream is not left to find work by polling. The courier
holds a run and then releases it, which is the store making a run claimable
the ordinary way, and the runner, which had gone to sleep for ten minutes,
performs it within seconds. What woke it was the news that something
changed. It still claimed the run through the ordinary path, and if the
news had not come, the poll would have done the work later.

## A large payload travels beside the message

The imager's scan is several hundred kilobytes. An answer on the stream is
an event on the door's workflow, which is a row in the substrate's own
tables, and those tables are re-read when the workflow recovers and kept
under the substrate's retention rather than the tenant's. So a payload
above a threshold waits beside the door, the message carries its key, and
the imager receives the scan byte for byte as the record holds it. A small
document goes in the message as before.

What is set aside is the sealed carrier form, the same bytes that would
have travelled inside the message. An erasure reaches the copy beside the
message exactly as it reaches one inside it, and no row of the substrate's
own tables is ever payload-sized.

## Nothing readable on the plane

Ines looks. Every row of every table in the substrate, as text, after a
run whose document carried a marker and a person's identifier. The
manifest is there, naming the run and the references it routes on,
because routing is what it is for. The marker, the identifier, any bearer
token and any client secret are not.

Asking the door for a run's inputs in the clear is refused on the stream,
whoever asks, and the refused request leaves nothing readable behind
either.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PROC-A-LANE-OVER-THE-STREAM` | A lane runs over the store's own stream, full duplex, beside in-process and HTTP: work goes out and travel, access and result events come home as they happen on the same channel. It serves exactly the verbs the other two do, and a runner cannot tell which it holds. | PROVEN |
| `REQ-DBO-PROC-A-HOST-HOLDS-A-LANE-WHEREVER-IT-IS` | A host that reaches the store over HTTP obtains the same lane as one that holds the store in-process: the tenant serves the participation verbs on its own private surface, guarded by its own authority, and a runner cannot tell the two apart. The entitlement is derived from the credential and never asked for by the caller, and a credential bounded to steps may work only as itself. | PROVEN |
| `REQ-DBO-PROC-A-WAKE-UP-IS-NOT-HOW-WORK-ARRIVES` | A lane may say that it has work, and a runner waiting on one looks again instead of waiting out its tick. What arrives is that something changed and never the work: the runner then polls and claims through the ordinary path, because the claim race is what decides who takes a run and a second mechanism deciding it would be a second answer beside the run record's account of what is owed and by whom. The poll stays underneath as the fallback, so a runner whose lane can say nothing — or whose wake-up never arrives — does the work anyway, and a delivery that goes missing is a latency bug rather than a lost run. A lane that cannot say is not degraded, and nothing above the facade can tell which kind it holds except by how long it waited. | PROVEN |
| `REQ-DBO-WF-TWO-PLANES` | Records live in the tenant plane, structurally isolated. The shared platform plane carries coordination and the copies work needs in flight — manifests readable, because routing is what they are for, and payloads sealed to the participant meant to open them. Isolation of a record is structural; of a copy in flight, cryptographic. | PROVEN |
| `REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE` | The platform plane never holds tenant credentials, and never holds resource content in a form readable in that plane. A sealed payload satisfies this; the plaintext form would not, however briefly. | PROVEN |

Coverage: {PROVEN=5} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **A door on the stream costs a durable-workflow instance per tenant.** A
  deployment of twenty tenants on a substrate ran its work about two and a
  half times slower than the same deployment without one. A deployment that
  wants the stream for a few tenants still pays for it on every tenant
  served.
- **The last wake-up of a burst can go unsent.** The door coalesces wake-ups
  inside a short window and sends none after it, so on a busy tenant the
  last run of a burst may wait for the poll. Nothing is lost; it costs
  latency.

## Open decisions

- **Whether the stream is opened per tenant** rather than for the whole
  runtime, so a deployment pays for the substrate only where a participant
  holds a lane on it.
