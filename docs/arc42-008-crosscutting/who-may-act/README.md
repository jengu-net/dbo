# Who may act

## The question

Every change in this store is somebody's act. Before anything else can be
decided — what may be read, what may be written, whose work it is — one question
has to have an answer that survives a shared deployment: **on whose authority?**

There are two kinds of actor and they arrive by different doors. A **system**
acts on its own behalf: a service, an appliance, another organisation's
integration. A **person** acts as themselves, or something acts on their behalf.
They share one trust root, and this document is about that root and the two
doors into it.

## The trust root is the tenant, not the store

A store that is its own authority — one issuer, one key set, every tenant
beneath it — makes separation into claim-checking inside a shared trust root.
Every party that trusts the deployment transitively trusts everything it hosts,
and one leaked signing key is everybody's problem.

So **each tenant carries its own authority**, with its own issuer and its own
keys. A relying party pins exactly one tenant. A token minted by any other
tenant fails at signature verification — wrong issuer, wrong keys — *before any
claim is read*.

That is the property worth the effort: cross-tenant confusion is
**unrepresentable rather than filtered**. A filter is something somebody can
forget to apply; a signature that does not verify is not a decision anybody
makes. And the blast radius of a compromised key is one tenant.

This is also the ordinary shape elsewhere — identity products scope an issuer
per realm or per tenant as a matter of course. The single-authority store is the
outlier.

## Credentials are ordinary records

A client, its granted scopes, and the tenant's signing keys are **records in the
tenant's own store**, not rows in a system table beside it.

Everything that is true of records is therefore true of them: history keeps
every version, changes flow through the change stream so anything can watch
credential lifecycle, provenance applies, and administering identity can itself
be a governed process rather than a privileged side channel.

A machine credential may carry two more things: **the public halves of the
keypairs the participant generated before it enrolled** — one it is sealed
to, one it signs with. They are offered at registration beside the secret,
recorded on the same record, and each named by its own thumbprint. The first
is what payload data keys are wrapped to when work leaves the tenant, so what
a participant may open is decided by what it holds; the second is what its
trail links are checked against, so what it said it opened cannot be forged
by a carrier or denied by itself. The private halves never cross, which is
why a copy of the enrolment records opens nothing and signs nothing — the
only asymmetric material this store keeps, and it keeps only the halves that
unlock nothing ([constraints](../../arc42-002-constraints/README.md)).

One relation reaches across tenants, and it is declared rather than held: a
tenant created as **managed by** a partner tenant trusts that partner's own
authority, for what the relation grants and nothing else. A partner's token
arrives as the audience the managed tenant declared for it — runs and the
trail, never a document — and fails at signature verification in every
tenant that did not declare it, exactly as any other tenant's token does.
The support desk that could widen its own reach by asking about more tenants
is the thing this refuses.

The consequence worth stating on its own: because the tenant's export carries
its authority, **restoring a tenant restores who may access it**. Recovery does
not have a separate, hand-managed step for "and now re-establish the
credentials", which is the step that gets missed.

## People authenticate elsewhere; the tenant decides what they may do

**The store authenticates nobody in production.** A person is sent to whatever
identity broker the deployment federates with — a national eID service, for
instance — and comes back verified. What the store owns is the other half:
**authorisation**.

And it owns it without a parallel user database, because **the organisation
model is already the authorisation model**. The records a tenant keeps anyway —
who works here, in what role, in which part of the organisation, from when until
when — are read as grants. A second system listing the same people with the same
roles is a system that drifts, and the drift is discovered when somebody who
left last year still has access.

**One authentication, many tenants.** A person may work for several
organisations, and a national authentication ceremony is often billed per use,
so a deployment runs one authentication hub beside the tenant authorities. The
hub holds a short-lived session carrying only the verified identifier and the
time of the ceremony — no names — and asserts that identity onward. Each tenant
then resolves the person in *its own* store, evaluates *its own* grants, and
mints *its own* tokens.

So authentication is shared across a deployment and **authorisation never is**.
Somebody with roles at two organisations authenticates once and works at both;
a tenant where they hold no active role refuses the very same valid identity.

Where several brokers exist, the **set is jurisdictional and the choice is
organisational**: a region declares which brokers are acceptable, and a tenant
declares which it uses and to what assurance level. A session that does not
satisfy a stricter tenant triggers that tenant's required ceremony and
accumulates onto the same session, so the strictest party is satisfied without
invalidating everyone else's.

## A token says who, without saying who they are

Tokens issued for people are **pseudonymous by construction**: the subject is
the person's record id, and the token carries no name and no national
identifier. A captured token identifies nobody. Interfaces that need to show a
name fetch it through an authorised read, which is itself recorded.

This is what lets the audit trail be exact and still contain no personal data:
the actor is the pseudonym plus the client that acted.

## Acting on behalf of a person

Automated work happens *for* people constantly, and there are only two honest
ways to represent it — never as the person, and never as an anonymous system
account that hides whose behalf it was.

**Live delegation** is a token exchange: a service holding a person's token
exchanges it for one where the subject is still the person, an added claim names
the acting client, and the scopes narrow to the intersection of what the person
had and what was asked for. The audit actor becomes the chain.

**Durable delegation is a record**, because a process step outlives any token.
When a person starts a piece of work, a delegation is written — the person's
pseudonym, the acting client, which work it covers, the narrowed scopes, and how
long it lasts. Later steps exchange against *that record* rather than against a
token that expired hours ago. Being a record, it is auditable, watchable,
revocable by ending its period, and exported with the tenant.

A delegated token can never exceed what the person could do, and every change it
makes is attributable to both the process and the person.

## What a credential's life is

An authority holds credentials, so it owns what may be done to one — created,
changed, forgotten, retired. Leaving those unanswered is how a consumer ends up
inventing a password-reset workflow in a record store.

**A factor is a kind, and the rules are per kind.** "May this subject hold a
local credential" has no clean answer because it is two questions:

- **A password is only appropriate where the tenant is the identity provider
  for that subject.** Where sign-in federates, a local password is a second way
  in, weaker than the first, and not disableable by the provider that actually
  owns the subject. An account is only as strong as its weakest door.
- **A PIN may coexist with federation**, because it serves the case federation
  cannot: a workplace that cannot reach a broker at all. It is scoped to that.

A rule about "credentials" in general would have had to make that workplace an
exception. A rule per factor does not.

**An honest limit:** the password rule is decided and not yet enforced.
Enforcing it needs a per-subject signal — whether *this person* is bound to an
external identity — rather than the coarse signal of whether the tenant has
federation configured at all.

## Two defaults worth knowing

**Deny by default.** A deployment serving with its authority disabled and no
explicit development flag refuses to serve. The failure mode this prevents is
the one where a misconfiguration looks like a working system.

**The authority does not open a public door.** It exists so that *authorised
services* can reach a private surface with tenant-rooted trust. Per-tenant
issuers are not a step toward exposing the store's own interface to the world;
what is public is a process surface, deliberately.

## What stays outside

**The store's own REST surface is private.** The application in front
terminates TLS, applies whatever integration gates it has, and forwards bytes;
authorisation for the public edge belongs at that edge, while everything
touching identifying data sits inside the store. A directory provisioning users
into the store does not change that — it reaches a surface mounted on the
tenant, behind the tenant's own authority, and never the public internet.

**A provisioned user is the person, not the capacity they act in.** A directory
that creates staff is creating the human: identifying data is authored on the
person, and the practitioner is a capacity linked to them. Implementations
routinely have this the other way round, writing the capacity first and letting
the linkage follow, which leaves the human derived from their job. The store
corrects that on the way in rather than carrying it: the capacity is ensured
and linked when the person is created, so grants resolve at token time with
nothing further to do.

Login screens, session handling and human-user administration belong to the
application in front of the store. That application is an ordinary relying party
of the tenant authorities: it keeps the client machinery its framework gives it
and loses only the authorisation-server role it should never have held.

Consent — who must agree before a person's identifying data may be unwrapped —
is a separate track that attaches to the seams in [data
isolation](../data-isolation/README.md).

## Where the detail is written down

- **The exact rules and their proofs** — the authority and provisioning entries
  in the [REQ catalogue](../../arc42-006-runtime/req-catalogue.md).
- **Who may see what, once they may act** — [data isolation](../data-isolation/README.md).
- **Where broker declarations and regional configuration live** — [declared
  rules](../declared-rules/README.md).
- **How identity artefacts behave as records** — [records you can rely
  on](../records-you-can-rely-on/README.md).
