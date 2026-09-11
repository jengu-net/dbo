# Scaling — tenant-aware routing

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

