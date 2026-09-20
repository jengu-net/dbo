# Context and scope

DBO is a storage engine consumed by an application, not a product somebody
uses. Its user is a builder, and nothing in it knows what the application
above it is for.

That makes its context three levels rather than one. A reader who wants to
know what the store touches has to be told what the store needs, what is
built on it, and what that system serves, because the store's own neighbours
explain almost none of its behaviour on their own.

--8<-- "assets/diagrams/three-levels-of-context.svg"

<p class="diagram-caption">Four bands: what the store needs, the store, the
system built on it, and the world that system serves. The relationship that
matters most is the one that skips a band — a client or a jurisdiction
reaching a face without passing through the application.</p>

## What the store needs

| Neighbour | Relationship |
|---|---|
| **PostgreSQL** | The only data store. A database per tenant, which is what makes isolation structural and erasure a drop |
| **A tenant spec** | Declares a tenant where the deployment reads specs. Configuration flows one direction, in |
| **An operator** | Provisions databases, buckets and credentials. No code here ever sees a credential it could read |
| **Monitoring** | Receives OpenTelemetry spans carrying process and step codes |

## The system built on the store

One consumer, in two assemblies: a cloud deployment and an edge appliance.
It reaches the store over the FHIR surface in production and through the
in-JVM embedded container in development and test, which is the same engine
either way.

What it owns and the store does not: its screens, its clinical logic, its
process and step definitions, and its own identity plane for the staff who
administer it. A store that knew any of those would be a product.

## The world that system serves

A clinician and a person meet the application and never the store. Two
others do not, and they are the reason the diagram has an arrow crossing a
band.

- **Another system's FHIR client** speaks the standard API to a tenant's own
  face, including topic-based subscriptions, against the CapabilityStatement
  that face generates from what it actually serves.
- **A jurisdiction's authority** publishes vocabularies and rules to a zone
  tenant, which dependent tenants read as declared, read-only copies.

Both hold a credential the tenant's own authority issued, because a tenant
is its own authority. Neither reaches anything the application has to
forward.

## Out of scope

Any end-user interface. The application's identity plane for its own staff
and administrators. Clinical logic, which belongs in the application's
modules and process definitions. A store that carried them would have to
know what it is for.

## The stories

[User stories](user-stories/README.md) tell the same context as scenes: one
builder's journey each, on named fictional people at a named fictional
organisation. Each is a constant in the promise catalogue declaring the
promises its journey rests on, so a story cannot claim a leg nothing
promises, and the table of legs in each one is projected rather than
written.
