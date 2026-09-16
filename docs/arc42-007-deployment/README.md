# Deployment

## The shapes a deployment takes

Three, and the engine behind them is the same build in all three.

- **Embedded in a host process.** A host application boots the store inside its
  own JVM, sharing a small container runtime and its API. The engine carries no
  application framework, which is what makes this possible; cold start is
  measured in seconds so that consumers test against the store rather than
  against a mock of it. This is also the appliance shape — one JVM, one
  Postgres, no container and no orchestrator — and it is the ordinary edition
  with one tenant, not a reduced one.
- **One node, many tenants.** One process on one port, a database per tenant,
  tenants served at `/t/<code>/…`. The orchestrator runs instances, holds
  secrets and enforces network policy. It does not route and does not know
  which tenant lives where.
- **A fleet.** Specified below; **no implementation exists**. A deployment today
  is one node and its databases.

### A participant's own container

A participant that runs steps is not one of those shapes: it is somebody
else's process, and what it installs is the deployment. `dbo-runner` brings
the whiteboard — a bundle registering a step service contributes a step, a
bundle registering a lane says which tenant's work to offer it — and the step
bundles are the participant's own.

The lane depends on where the participant sits. In-process it is the host's
own; over HTTP it is built by whatever holds the credential. Over the store's
durable substrate it comes from installing `dbo-stream` beside the runner and
telling the container four things: the substrate it already shares with the
store, the tenants it holds a lane into, the participant name it enrolled
under, and the private halves of the two keys it enrolled with. That is the
shape for a participant that can take no inbound connection at all — nothing
is opened towards it, and it opens nothing towards any tenant.

A container serving tenants installs the same bundle, because the door is in
it. It is told no tenants to hold a lane into, so it holds none.

A worked example of the second shape — two organisations, the managing tenant,
the applications in front of them and the participants outside — is drawn on
the site under Technical, where a reader planning a deployment is standing.

## Performance is a requirement, not a later phase

R7 states it as founding, and two consequences are structural rather than
tuning: the searchable envelope is indexed from the first schema rather than
retrofitted, and change distribution runs on the store's own database rather
than on a broker beside it. A cache tier is the usual answer to the same
problem and is deliberately absent, because it is a second copy of the truth.

## What scales, and what one more of it buys

--8<-- "assets/diagrams/what-scales-and-how.svg"

<p class="diagram-caption">Solid is built. Dashed is specified and unwritten.</p>

- **Data — a database per tenant.** Built. The unit that moves is a whole
  tenant, so one is never split in order to be moved, and clustering, replicas
  and sizing are per-instance Postgres decisions.
- **Work — runners.** Built. A runner is the work module packaged with the
  durable-execution runtime it uses. Runners ask for work rather than being
  called, so adding one adds throughput and nothing has to be told it arrived.
- **Serving, routing and entry.** Specified below. All five `SCAL` promises
  read `PLANNED`.

## Scaling — tenant-aware routing

- One Kubernetes-managed flat network; every DBO pod sees every other pod.
- Assignment maps tenants → pods: a pod serves one or more tenants; a big tenant
  spans multiple pods. The assignment itself is durable state (DBOS) with
  version-driven takeover semantics inherited from the legacy election design.
- Routing is **dOSGi-like**: a tenant's `ObjectStore` service is local on its
  serving pods and a remote proxy everywhere else, so callers do a plain registry
  lookup and the topology is invisible. This is application-level, tenant-smart
  routing; Kubernetes only runs instances and enforces the security layer.
- The layer is **our own**, purpose-built (see §7.2): registry programming model
  and remote proxies as in OSGi Remote Services, but discovery driven by the
  durable tenant→pod assignment and a single controlled transport — not a full
  RSA implementation (Aries RSA / ECF serve as prior art only).

### Two-hop routing: locality first, tenancy second

Not every pod needs to be an entry point. A subset of nodes are (internally)
**"public" dOSGi nodes** — e.g. one per zone — and routing happens in two hops
with a different concern at each level:

1. **Kubernetes level — requestor-location-based resolution.** An incoming
   request is routed/redirected to the *closest* public node relative to the
   requestor (topology-aware routing / zone-local Service semantics, or an
   explicit redirect from a resolver endpoint). Kubernetes decides *where you
   enter* the mesh, using what it actually knows: network topology and locality.
2. **dOSGi level — tenant resolution.** The zone/public node then routes to the
   pod(s) actually serving the tenant via the registry-driven routing table.
   dOSGi decides *who serves you*, using what it knows: the tenant → pod
   assignment.

Consequences to design for:

- The tenant → pod assignment (§ above) gains a locality dimension: the
  assigner should prefer placing a tenant's serving pods in the zone where its
  requests originate, so the second hop is usually zone-local and the entry
  node's forward is cheap or a no-op (entry node *is* a serving pod).
- Public nodes are a role, not a separate binary: any DBO pod can be flagged
  into the entry role; the role is part of the durable assignment state.
- Redirect vs. proxy at hop 1 is an open choice per protocol: FHIR REST can use
  HTTP redirect to the tenant's home entry point; websocket subscriptions and
  in-mesh dOSGi calls proxy.

