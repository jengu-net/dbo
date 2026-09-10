# Medplum → dbo

**Status** — active on the consumer's side; this side answers. Everything the
cutover has asked of the store so far is delivered, and what remains open here
is the trail of traps it left.

**Issues** — none open on this side. Served:
[#136](https://github.com/jengu-net/dbo/issues/136) (exact identifier
resolution), [#143](https://github.com/jengu-net/dbo/issues/143) (SCIM served
per tenant). The migration itself is tracked by the consuming platform, in its
own task document.

**Concepts** — [data isolation](../arc42-008-crosscutting/data-isolation/README.md) ·
[who may act](../arc42-008-crosscutting/who-may-act.md) ·
[engine and faces](../arc42-008-crosscutting/engine-and-faces.md)

## What this is

The consuming platform is replacing Medplum with this store. That is a cutover
of the whole runtime substrate — tenancy, identity, clinical data, terminology,
audit, subscriptions, the edge appliance — and it stalled repeatedly on things
Medplum could not do rather than on effort. Each stall arrives here as a
question about what the store should offer, and the interesting part is usually
that the question is better answered by moving a *surface* than by widening a
*primitive*.

## Where it stands

The consumer drives; this repository answers. The pattern that has worked three
times now: they describe the operation that stalled, we find the answer is
either already present and unexposed, or belongs inside the membrane rather
than through it.

- **Staff provisioning** — SCIM 2.0 is served per tenant from the store
  (#143), so the enumeration a provisioning API needs never crosses the
  membrane. Their mapping controller becomes a gate-and-forward proxy.
- **Identifier resolution** — exact resolution through the vault is exposed as
  the standard FHIR search spelling (#136), not a new primitive.
- **Data versioning** — the store's half is delivered and is described in
  [records you can rely on](../arc42-008-crosscutting/records-you-can-rely-on.md).
- **The edge appliance** — per the participation doctrine in
  [processes and work](../arc42-008-crosscutting/processes-and-work.md), the
  edge runs dbo in-JVM as a second appliance of the same tenant. Everything it
  needs from this side is delivered — the batch/apply toolset and the
  credential half of reach among it — so a stall there is a question about the
  consumer's half.

Where the consumer's own cutover stands — which lanes are on the seam, which
are rebuilt rather than ported — is theirs to say, and they say it in their
own task document rather than here.

## Decisions

Four of these outlived the migration that produced them, so they have moved to
where a permanent explanation belongs rather than waiting to be deleted with
this file:

- a surface moves inside the membrane rather than a primitive widening —
  [engine and faces](../arc42-008-crosscutting/engine-and-faces.md);
- the store's REST surface stays private, and a provisioned user is the person
  rather than the capacity they act in —
  [who may act](../arc42-008-crosscutting/who-may-act.md);
- a store-visible feature is a decision rather than an accident —
  [data isolation](../arc42-008-crosscutting/data-isolation/README.md).

What remains in this document — what the store has answered, and the traps it
found — is this migration's own, and goes when it closes.

## Traps

**A new module another module imports must join the OSGi bundle set in the same
commit.** This bit twice in one session — `dbo-scim`, then `promise` /
`dbo-promises`. bnd computes `Import-Package` from bytecode, so the tenant
bundle resolves fine on the classpath and dies in Felix. Three places, all fed
from one list: `dboRuntimeModules` in the root build, the harness's jar
properties, and `TenantOsgiIT`'s install list.

**A role-global Postgres setting applied per tenant collides under load.**
`ALTER ROLE … SET` writes one shared catalogue row, so N concurrent bring-ups
meant N writers and `tuple concurrently updated`. Applied once per process now,
with a bounded retry for the residual race — matched on the message, because
the SQLState is the catch-all `XX000` and gating on that alone would swallow
real faults.

## Not doing

**Groups as a SCIM write surface.** Who works here is the identity provider's
call; who is an admin here is not. Read-only, with 405s proven, and currently
listing role codes *without members* — a member list is a different disclosure
decision, and the consumer can ask for it as its own slice.

**SCIM PATCH.** `replace` covers the lifecycle, matching what the consumer's
own implementation supports.

## Verifying

```bash
./gradlew :core:harness:test --tests '*ScimProvisioningIT' --tests '*DisclosureModesIT' --tests '*PdiIT'
```
