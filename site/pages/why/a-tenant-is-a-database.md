---
title: A tenant is a database
eyebrow: Why DBO
standfirst: >-
  Not a filter over a shared one. That difference decides what happens on the
  day somebody writes a query with a bug in it, and what "we have removed your
  data" is actually worth.
template: essay.html
---

Every shared store has to answer one question before an organisation will
agree to use it at all: can anybody else see my data?

The usual answer is a tenant column and a discipline about always filtering on
it. It works, until it doesn't, and the failure is silent in both directions —
the query returns rows and the caller has no way to know some of them were not
theirs.

--8<-- "assets/diagrams/a-tenant-is-a-database.svg"

<p class="diagram-caption">Both panels contain the same defect. Only one of
them can produce somebody else's records from it.</p>

## What the operator is, and is not

The party running the deployment provisions tenants. It never holds their
credentials: provisioning hands a store a connection, and the tenant is its own
authority from that moment. There is no master issuer whose keys open
everything, because there is no master issuer.

There is also **no cross-tenant surface** — nothing that reads across, not even
for the operator. A process that legitimately needs a fleet-wide view holds a
credential per tenant and asks each in turn.

That is a walk rather than a join, and it is slower on purpose. The convenient
version of it would be available to anything that ever got hold of it, which is
precisely the shape of thing that ends up in an incident report.

<div class="takeaway" markdown>
The operator's own records are a tenant too — distinguished by role rather than
by position. What it records about its work inherits the same authority, audit,
retention and erasure as everybody else's, instead of living in a privileged
plane with rules written specially for it.
</div>

## Leaving

This is the part that is hard to promise and easy here.

Removing an organisation drops a database. Not a delete sweep across shared
tables that somebody has to certify was complete, and not a `deleted` flag that
a later reporting query forgets about. A thing you can watch happen, and
afterwards there is no table left to have missed a row in.

Taking your data with you is one sealed archive — the same artefact that
serves as backup, restore and migration, encrypted under the owner's key so the
operator cannot read it, and restore-tested by ordinary use rather than by an
annual exercise nobody enjoys.

## What this costs

Honesty about the trade: a database per tenant is more databases. Connection
pools, migrations and monitoring are per-tenant concerns rather than one big
one, and a thousand tenants is a thousand of them.

That cost is paid deliberately, because the alternative spends the same effort
on being sure the filter was applied everywhere, forever, by everyone — and
that bill never stops arriving. A shared tier does exist as a variant, where
the same layout gains a tenant column and row-level policies. The dedicated
tier is what the design is reasoned from, and the variant is the exception that
has to argue for itself.

<div class="further" markdown>
The other wall — the one between a person's data and the operator — is
[personal data](personal-data.md), and the two together with the crossings that
are declared are
[Data isolation (§14)](../docs/arc42-008-crosscutting/data-isolation.md).
Who a tenant lets in is [Who may act (§13, §16)](../docs/arc42-008-crosscutting/who-may-act.md).
</div>
