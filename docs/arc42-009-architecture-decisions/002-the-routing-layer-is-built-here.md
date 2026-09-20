**Status: Resolved.** Reflected in [deployment](../arc42-007-deployment/README.md).

# The routing layer is built here

Aries RSA / ECF activity is low, and the
full Remote Services spec solves a general problem we don't have. Current
thinking: a **purpose-built dOSGi-like layer** shaped by our actual needs —
remote proxies for a *known, small* set of DBO-owned service interfaces;
discovery from the durable tenant→pod assignment (DBOS state) instead of
generic topology gossip; tenant id + locality + serving-role as first-class
routing properties rather than opaque service filters; one transport we
control (gRPC or plain HTTP/2) with mTLS inside the mesh. The OSGi service
registry stays the programming model (consumers look up `ObjectStore` for a
tenant and may get a local instance or a remote proxy — indistinguishable);
we just don't buy the spec's generality: no dynamic interface export, no
pluggable discovery providers, no config-admin ceremony. Aries RSA/ECF
remain reference material for proxy/classloader mechanics. The work is
sizing our own layer — proxy generation over a fixed interface set is
small — rather than auditing someone else's.

**VERDICT (Cellar evaluation, 2026-08-14): REJECT Cellar — build our own,
as planned.** Source-level review of `cellar-dosgi` at `apache/karaf-cellar`
main:

- **Activity, corrected.** The earlier "more alive" impression does not
  survive contact: the 2025-08/09 flurry was a maintenance release (4.4.8,
  "cleanup and update to support Karaf 4.4.x"), essentially one maintainer
  plus dependabot; nothing since 2025-09. No refactoring toward a new
  major is visible on main. Cellar still pins **Hazelcast 3.12** — a
  long-EOL major — which alone disqualifies it for a medical platform.
- **Mechanics, measured.** The whole dosgi module is ~13 small classes.
  A remote call is a Java-serialized
  `RemoteServiceCall{endpointId, methodName, args}` pushed through
  Cellar's generic Hazelcast command-execution context; the caller gets a
  `Map<Node, Result>` and **returns the first entry of an arbitrary map
  iteration**. Dispatch is by method *name* (overloads ambiguous),
  serialization is Java serialization (schema coupling + a deserialization
  attack surface), there is no property-based routing, no locality, no
  partitioning, no streaming, no transport control. Every dimension DBO
  routing needs (tenant id, locality, serving-role, mTLS, lean frames —
  §5, §10) is absent.
- **Prior-art value**: genuinely useful but small — the
  `RemoteServiceFindHook` + proxy + endpoint-map *shape* confirms a
  purpose-built layer is a modest build, which the own-layer plan already
  assumed. Nothing worth importing as a dependency.
- **Karaf-the-container** (features model, shell, provisioning) remains a
  separate, open option — Karaf itself ships steadily (4.4.x through
  2026) — to be decided when packaging/distribution becomes real work.
  The embedded in-JVM mode runs on plain Felix regardless, which is
  proven.
