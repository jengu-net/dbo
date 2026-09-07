# Data isolation (§14)

## Two walls, and the openings that are declared

A store several parties use has to answer two different questions, and they are
not the same question:

- **Can another organisation see my data?** — the one a party asks before
  agreeing to use a shared exchange at all.
- **Can anyone, including whoever operates the store, see who this is about?** —
  the one the law asks, and the one that decides whether an operator can run the
  system without being trusted with its contents.

They have different answers and different mechanisms. The first is structural
separation; the second is cryptographic. This document describes both, and then
the third thing that matters as much: **what deliberately crosses, and how that
is declared**, because a wall with undocumented holes is worse than a wall
nobody claimed.

## An organisation's data is its own

**A tenant's records live in its own database**, not in a shared table behind a
tenant column. That is the design anchor rather than an optimisation: a query
that forgets a filter returns nothing belonging to somebody else, because there
is nothing else in there to return. (A shared tier exists as a variant, where
the same layout gains a tenant column and row-level policies; the dedicated tier
is what the design is reasoned from.)

**A tenant is its own trust root.** Credentials are issued by the tenant, and
identity artefacts are ordinary records inside it. The store does not hold a
directory of everybody's users, which is what would make it a single place worth
attacking for all of them at once.

**There is no cross-tenant surface.** Nothing offers "read across tenants",
including to the operator. A process that legitimately needs a fleet-wide view —
an operator console, for instance — holds per-tenant credentials and asks each
tenant in turn. That is a walk rather than a join, and it is deliberate: the
expensive path is the one that preserves the property, and a convenient
cross-tenant read would be available to anything that ever got hold of it.

**The operator's own records are a tenant too.** The party running the
deployment is a tenant like the others, distinguished by role rather than by
position, so what it records about its work inherits the same authority, audit,
retention and erasure as everything else — instead of living in a privileged
plane with rules written specially for it.

## A person's data is not the operator's

Protection here is structural rather than procedural, so the rights of access,
portability and erasure are machinery operations — and the operator can run the
whole system, including backup and restore, without being able to read a
person's data.

**The conflict this resolves.** Every version of every object is kept
immutably, and archives are byte-faithful. Erasure cannot be honoured by
rewriting history without destroying both properties. The resolution is
**crypto-shredding**: identifying material is encrypted with a per-person key,
and erasure destroys the key. History stays byte-immutable, archives already
taken stay valid as files, and the person's data is cryptographically gone from
the live store, from history, and from every archive that carried ciphertext.

**The vault and the pseudonymous store.** Each tenant contains a person vault
holding the identifying material; everything else refers to a person by
pseudonym. Records in the main store carry no name, so a reader who is entitled
to the work is not thereby entitled to the person.

**The shred ledger.** An archive taken before an erasure still contains that
person's wrapped key, so restoring it would resurrect them. Erasures are
recorded in a ledger — pseudonym, key fingerprint, timestamp, and no personal
data — and a restore re-applies the ledger before serving resumes.

**Rights as operations.** Erasure shreds the key and records the ledger entry.
Access and portability are a vault-joined export through the ordinary
maintenance machinery. Restriction is a flag the serving path honours.

## What crosses, and why it is allowed to

Isolation with no declared exceptions would be a claim nobody could act on: an
operator has to be able to run the thing, and parties need to know what that
involves. So the openings are named.

**Measurements about work leave the tenant.** Counts, durations and outcomes are
reported for whoever operates the deployment, across tenants — that is what
makes a fleet observable, and it is ordinary practice. What makes it safe is that
the set of things that may be said is closed: which tenant, which process and
step, what ran it, and how it ended as one word from a fixed vocabulary. A
failure's own words are **not** in that set; they stay on the run, in the store
of the tenant whose work it was. An open field in a stream declared anonymous is
how such a declaration stops being true without anybody editing it, and it is the
documented failure of every central logging system.

**Two appliances of one tenant exchange whole records** — but this is not an
exception, and reading it as one leads to the wrong instincts. A site appliance
and a cloud are one tenant in two places, so nothing crosses a tenant boundary
at all. What is worth knowing is that the traffic is bounded by what the work
names rather than by following references outward, so an appliance holds what it
is working on rather than a copy of the collection.

**Work leaves a tenant sealed, and its manifest does not.** A runner fleet that
serves every tenant carries every tenant's work, and what it carries is
declared in two parts: a manifest — tenant, step, task, and references to the
documents named — readable, because routing on it is its job; and a payload —
the documents — sealed to the participant meant to open it, in the carrier
form the encrypted disclosure mode already hands out. So the fleet sees, across
tenants, which tenant has which task naming which documents, and that is the
whole of what it sees. It is a chosen position rather than a leak, and it is
the declaration this rule asks for: the shared plane never holds resource
content in a form readable there — and it is checked by looking: a test
drives a marked document across the substrate and reads every row of every
table there, finding the manifest and nothing else. Nor does the plane hold
a credential: an ask on the stream is signed with the participant's
enrolment key rather than carrying a token, and the clear verb has no answer
there at all.

**An audit trail replicates as it was recorded** — original actor, original
time, the appliance named — because an account of who did what is worthless if
the act of moving it rewrites its provenance.

**The rule behind all three:** a class of data that leaves a tenant is declared,
with what it may contain, before it leaves. Whoever adds the next one owes the
same declaration — and if the answer involves a free-text field, the answer is
not finished.

## What a particular recipient sees

Whether something may leave and what *this* recipient may see are different
questions, and for a long time only the first had an answer.

A type's declared **travel** says whether it goes into a backup, into an export
a customer leaves with, as a snapshot, or nowhere. Those are kinds of
destination, not people. A face's **coarsening** reduces a value — a birth date
to its year — and is by construction the same for everybody: it takes a value
and returns a value, and it never learns who is on the other end. That
restriction is load-bearing and stays.

So a clinic sharing with a referral hospital and with a research recipient
shared the same thing with both, or declared a type unshareable and shared it
with neither. Coarsen the birth date and the referral hospital is guessing at
the patient's age; leave it precise and the researcher is holding an
identifier.

**A tenant now declares audiences.** Each says which types it is answered about
at all, and what a read of one of them reveals:

```json
"disclosure": { "perAudience": {
  "referral": { "types": ["Patient", "Observation"], "reveals": "include" },
  "research": { "types": ["Observation"],            "reveals": "omit" }
}}
```

Four things about that, each of which could reasonably have gone the other way:

- **The recipient travels beside the request**, as a per-request fact rather
  than a scope — the third tier of [the face
  contract](engine-and-faces.md#where-a-new-obligation-belongs). Nothing
  becomes recipient-scoped; the store stays a function taking values.
- **A type outside the declaration is absent, not refused.** A refusal naming
  the type would tell the recipient it exists here, which is the leak an
  organisational compartment already avoids for the same reason.
- **The mode follows the declaration, not the request.** A recipient asking for
  the whole record with a stated purpose gets what the tenant declared. One
  that could negotiate upwards would make the declaration advice.
- **An audience nobody declared sees nothing.** The two ways to arrive at an
  undeclared name are a typo in a serving surface and a partner who was
  removed, and both want silence rather than the tenant's own view.

**Naming no audience is the tenant working with its own records**, which is
nearly every request, and nothing about it changes.

The declaration is configuration rather than records, deliberately. A store
that accumulated one rule per partner would be a policy engine nobody can
audit; configuration is swept, reviewed and diffed like everything else a
tenant declares.

**A partner is an audience, and something more.** A tenant that manages other
tenants — a reseller — needs two answers that compose rather than compete. A
*managed by* relation, declared when the managed tenant is created, says
*which tenants* the partner may read at all. Within each of them, the partner
is an ordinary declared audience saying *what of each*: runs and the journey
they took, not the documents; stated purposes only if the managed tenant opts
in. What the partner is shown is assembled outside the store — a store
instance is one tenant's store, and the cross-tenant query the tenancy design
exists to remove is not grown to serve a support desk. The relation is made
true at the door: a managed tenant trusts its partner's own authority because
it declared the partner, answers that credential as the partner audience, and
serves its trail by run so a journey is one ask; a tenant that declared no
partner refuses the same credential at signature verification, as it refuses
every other tenant's.

## A store-visible feature is a decision, not an accident

Most of what this store does is invisible to the customer of whoever runs it:
which store holds the data is the operator's business, and the contract the
customer sees belongs to the application in front. A surface that a customer's
own administrator reaches — a directory provisioning staff into the tenant is
the first of them — breaks that, and makes "which store" a customer-visible
answer for the first time.

That is accepted deliberately where the capability is worth it, and it is
mitigated rather than waved away: the address the customer is given stays the
operator's, so the visible contract is still theirs and the store behind it can
be changed again. What must not happen is acquiring such a surface without
noticing — a feature that quietly makes the store nameable to a customer has
taken away the operator's freedom to move it.

## What stays outside

Consent semantics and co-ownership — who must agree before a person's key may be
unwrapped — are their own track; this provides the key seams they attach to.
Anonymisation pipelines consume what is left behind here by construction:
pseudonymous, unlinkable records.

## Where the detail is

- **What is promised, and what proves it** — the
  [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
- **Where a tenant's storage actually sits** — [records you can rely on](records-you-can-rely-on.md).
- **Who may act, and on whose authority** — [who may act](who-may-act.md).
- **Why measurements leave at all, and what they carry** — the watching section
  of [processes and work](processes-and-work.md).
- **Why an operator that cannot read the data is the point**, commercially as
  well as legally — [where a neutral store earns its
  keep](https://github.com/jengu-net/dbo/blob/main/docs/plans/neutral-exchange-domains.md), whose last condition is that the
  operator can be paid without monetising what flows through.
