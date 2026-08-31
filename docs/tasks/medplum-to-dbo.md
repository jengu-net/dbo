# Medplum → dbo

**Status** — active, and the largest thing in flight. The store side keeps
delivering the pieces the cutover needs; the consumer side is mid-migration.

**Issues** — [platform#851](https://github.com/jengu-net/jengu-platform/issues/851)
(epic) · open pre-actions:
[#852](https://github.com/jengu-net/jengu-platform/issues/852) (the store seam) ·
recently served from this side:
[#910](https://github.com/jengu-net/jengu-platform/issues/910) (SCIM),
[#136](https://github.com/jengu-net/dbo/issues/136) (exact identifier
resolution), [#143](https://github.com/jengu-net/dbo/issues/143) (SCIM served
per tenant)

**Concepts** — [data isolation](../arc42-008-crosscutting/data-isolation.md) ·
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

- **Staff provisioning** — SCIM 2.0 is now served per tenant from the store
  (#143), so the enumeration a provisioning API needs never crosses the
  membrane. The consumer's ~380-line mapping controller becomes a
  gate-and-forward proxy.
- **Identifier resolution** — exact resolution through the vault is exposed as
  the standard FHIR search spelling (#136), not a new primitive.
- **Data versioning** — see [its own topic](data-versioning.md).
- **The store seam is built and the cloud lane is done** on the consumer's
  `main`: zero Medplum store sources in their production wiring (from
  fifteen), and their `integrationTest` runs against dbo. What remains on
  their side are identity offshoots (platform#912/#914) rebuilt on the
  membrane rather than ported.
- **The edge appliance is its own consumer-side task now**:
  [platform#917](https://github.com/jengu-net/jengu-platform/issues/917),
  `docs/tasks/edge-appliance-on-dbo.md` over there. Per their ADR 0062/0063
  and the participation doctrine in
  [processes and work](../arc42-008-crosscutting/processes-and-work.md), the edge
  runs dbo in-JVM as a second appliance of the same tenant. Everything it needs
  from this side is delivered — the batch/apply toolset and the credential half
  of reach among it — so a stall here is now a question about their half.

## Decisions

**Move the surface, not the primitive.** Twice now the consumer asked for a
capability to be *widened* (scoped enumeration, then general listing) and the
better answer was to move the whole surface inside the membrane, where the
capability is internal and only the protocol's own verbs are public. A widened
primitive is available to every caller with the scope, forever; a surface is
available to the one server that needs it.

**A SCIM User is the Person, not the Practitioner.** §14 makes Person the human
and the place identifying data is authored; Practitioner is a capacity they act
in. The consumer's Medplum implementation wrote Practitioner first and let the
linkage follow — the store corrects that rather than porting it, and the
Practitioner capacity is ensured and linked on create so token-time grants work
with nothing further.

**dbo's REST surface stays private.** The consumer terminates TLS, applies its
integration gates and forwards bytes. This was their proposal and it is the
right one: it keeps authorisation at the public edge where it belongs while
everything touching identifying data moves inside the store.

**Store-visible features are accepted deliberately.** SCIM makes "which store"
a customer-visible answer for the first time. Mitigated by the proxy URL
staying the consumer's, so the customer-visible contract is theirs and the
store behind it can change again.

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
