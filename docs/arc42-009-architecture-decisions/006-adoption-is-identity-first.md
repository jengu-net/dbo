**Status: Resolved.** Reflected in nothing yet.

# Adoption is identity first, storage second

A deployment already running on another FHIR server does not adopt DBO by
flipping a base URL, and the reason is worth stating plainly: **the hard
coupling is never storage**. Search, CRUD and conditional writes sit inside
tier 1 behind a client seam that any deployment already has. The coupling is
**identity and tenancy expressed in the incumbent's proprietary vocabulary** —
a project resource standing in for a tenant, roles hung off a membership
resource, credentials stored in a settings array, cross-tenant links with no
FHIR equivalent. None of that ports; all of it has to be re-expressed.

Hence the order: identity first, storage second.

1. **Extract identity while still on the incumbent.** Whatever issues tokens
   today stops being the incumbent's concern. In DBO the tenant is its own
   authority (§13), so this step is what makes the rest a storage question.
2. **Reach tier-1 parity behind the existing seam.** The embedded in-JVM
   store replaces the incumbent's test container first — development and test
   run on DBO long before production does, which is where the parity gaps
   surface cheaply.
3. **Flip tenant by tenant.** Configuration is recreated from its source of
   truth rather than migrated. Clinical data that must survive moves as
   NDJSON; everything derivable is re-derived. Version transitions come later
   through upgrade-on-read, not during the move.
4. **Move the edge.** An edge runtime hosting DBO bundles in its own JVM
   drops both the incumbent server and its cache tier.
5. **Decommission.**

No dual-write, no live synchronisation, and no compatibility layer beyond the
FHIR surface itself. A compatibility layer for a proprietary vocabulary would
outlive the migration it was built for.
