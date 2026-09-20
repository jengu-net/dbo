# Runtime

The behavioural promises live in the [requirement catalogue](req-catalogue.md),
generated from the promise constants and the tests that cite them. Its
counts are the catalogue's own, never typed here.

The scenarios below are how the building blocks interact for the cases that
matter, one sequence each. Each is drawn because its claim is about
**order**: what has to have happened before the next thing can, and what is
committed together. A box-and-arrow figure of the same participants would
show who talks to whom and none of that.

## A tenant comes up

--8<-- "assets/diagrams/a-tenant-comes-up.svg"

<p class="diagram-caption">A spec appears where the deployment reads specs,
and the tenant is built in order: its own database, then dispatch, where its
store and feeds exist, then its surfaces, and only then is it serving.</p>

Nothing is reachable before the thing it needs exists, which is why the
stages are the points a lifecycle listener is registered against. The cost
is almost all terminology, and it is paid once per database rather than once
per use. [Tenant provisioning](../arc42-007-deployment/tenant-provisioning.md)
is the same life from the operator's side.

## A write arrives

--8<-- "assets/diagrams/a-write-arrives.svg"

<p class="diagram-caption">The face parses the bytes once, the engine derives
the envelope from them, and the payload, the envelope, the trail entry and
the change event are written in one transaction.</p>

Three claims sit in that order. The bytes are parsed once, so a request does
not carry the cost of being read twice. The envelope is derived rather than
sent beside the payload, so it cannot disagree with what was stored. And the
change event commits with the row, so a reader that can see the row can see
its event. [Records you can rely on](../arc42-008-crosscutting/records-you-can-rely-on/README.md)
holds the rules the engine applies on the way through.

## A search is answered, or refused

--8<-- "assets/diagrams/a-search-is-answered.svg"

<p class="diagram-caption">Every parameter is checked against what the tenant
declared before the database is asked. An undeclared parameter is refused by
name, and nothing is read.</p>

The refusal happens first, and that is the safety claim rather than a
strictness preference: a parameter that was quietly dropped would answer a
narrower question while looking like an answer, which in a clinical system
is a wrong result set rather than a compatibility feature.
[Finding things](../arc42-008-crosscutting/finding-things/README.md) says
what is declared.

## A consumer catches up

--8<-- "assets/diagrams/a-consumer-catches-up.svg"

<p class="diagram-caption">A consumer is a name and a position. It asks for
what follows its position, handles the page, and only then records where it
has reached.</p>

Paging, subscription delivery, replication and catch-up are this one
exchange over one primitive, which is why none of them needs a broker or a
notification table. A consumer restored from its name alone stands where it
left off. [Change, and who is listening](../arc42-008-crosscutting/change-and-who-is-listening/README.md)
is the primitive itself.

## A run is performed

--8<-- "assets/diagrams/a-run-is-performed.svg"

<p class="diagram-caption">A participant asks, claims, does the work and
reports. Every arrow carrying work starts at the participant, including the
one that looks like delivery.</p>

Nobody is pushed, so a participant behind a firewall needs no inbound hole.
The claim is conditional and the deadline is real: a claim held past it is
released and another participant takes the run, which is why a transient
failure must not read as a refusal.
[Processes and work](../arc42-008-crosscutting/processes-and-work/README.md)
holds the model.

## A zone declares, and a tenant narrows

--8<-- "assets/diagrams/a-declaration-is-applied.svg"

<p class="diagram-caption">A jurisdiction publishes its vocabularies and
rules to the zone tenant as ordinary records. A tenant under it reads the
zone's rules beside its own.</p>

Configuration arrives through a face like anything else and travels one
direction. A tenant may make a declared rule stricter and never looser, so
the layering can be read off the two declarations rather than computed.
[Declared rules](../arc42-008-crosscutting/declared-rules/README.md) is how
they compose.

## A person is erased

--8<-- "assets/diagrams/a-person-is-erased.svg"

<p class="diagram-caption">The key is destroyed and the act is recorded.
Afterwards the rows are still there, and no version of them can be opened.</p>

Nothing is rewritten and no row is walked, so a history that was verifiable
before the erasure is still verifiable after it and says nothing about the
person. [Data isolation](../arc42-008-crosscutting/data-isolation/README.md)
is where the sealing is described.

## An archive leaves, and returns

--8<-- "assets/diagrams/an-archive-leaves-and-returns.svg"

<p class="diagram-caption">Export seals every version and the work still in
flight to the owner's key. Import verifies the attestation before anything
is written.</p>

Backup, restore, migration and export are one act with one artifact, and the
operator handles a file it cannot open at both ends.
[Running it](../arc42-008-crosscutting/running-it/README.md) covers the
operations these two belong to.
