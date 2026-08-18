# Security

## Reporting a vulnerability

Report privately, not as a GitHub issue: **security@jengu.cloud**.

Please include what you did, what happened, and what you expected. If you have
a proof of concept, send it — it is the fastest way to a fix, and it will not
be shared beyond the people working on the problem.

You will get an acknowledgement within three working days and an assessment
within ten. If a report turns out to be a design decision rather than a
defect, you will get the reasoning, not a dismissal.

## What this store is responsible for

DBO holds clinical data for tenants that do not trust each other, so a few
classes of report matter more than their severity score suggests. Any of these
is serious regardless of how hard it is to reach:

- **Anything readable across a tenant boundary.** A tenant is a database and a
  token from one tenant fails at another's signature check; a way around
  either is the top of this list.
- **Anything that makes the operator able to read tenant data.** Provisioning
  is credential-blind, archives are sealed under the owner's key, and under
  personal-data isolation the platform holds no key that opens a person. A
  path that undoes one of those defeats the design rather than a control.
- **Identifying data appearing where it should not** — in a log, an error
  message, an unencrypted column, an export that declared it carried none.
- **Anything that writes to an append-only record**, or removes one.
- **A search that returns more than it was asked for.** Strict search exists
  because a silently broadened filter is a wrong result set, and in a clinical
  system that is a safety problem, not a compatibility one.

## What is not a vulnerability here

- **The store surface being reachable.** It is meant to be private; a
  deployment that exposes it has a deployment problem. The authority surface
  at `/t/<code>/oidc` is the part built for hostile networks.
- **A tenant's own administrator reading that tenant's data.** That is the
  design.
- **Search features returning 400.** Unsupported parameters are refused on
  purpose, and the CapabilityStatement says which are supported.

## Supported versions

Pre-1.0. Fixes land on the current minor; there is no backport line yet, and
saying otherwise before anyone depends on one would be a promise with nothing
behind it.
