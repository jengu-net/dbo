**Status: Resolved.** Reflected in [finding things](../arc42-008-crosscutting/finding-things/README.md).

# Search is tiered on measured usage

Resolved by measuring
instead of guessing: an inventory of all ~206 production FHIR search call
sites across a clinical cloud, a laboratory system and a visit assistant
([search-usage-inventory.md](../evidence/search-usage-inventory.md), 2026-08) shows
a narrow, conservative slice in real use — token `identifier=` lookup
dominates (~88 sites), nearly everything is `_count`-bounded and
`-_lastUpdated`-sorted, and `_filter`/`_has`/composites/full-text have zero
production usage.

**Tier 1 — drop-in parity (day one).** Everything today's call sites use:
token (`sys|val`, `sys|`, plain), reference, string, canonical `url=`,
date prefixes on `_lastUpdated`; result params `_count`, typed `_sort`,
`_elements`, `_summary=count`, `_total`, offset/next-link paging; `_tag`,
`_profile`, `_id`; modifiers `:missing`, `:not`, `:exact`, `:identifier`;
**one-level chaining** and **single named `_include`** (both in production
at low volume); **conditional create/update/delete** on search criteria
(~50 sites — the bootstrap upsert workhorse); operations `$expand`,
`$validate`, `$lookup`; Subscription-criteria evaluation. All of tier 1 is
envelope + identifier + reference-table mechanics — no new machinery.

**Tier 2 — deletes known workarounds.** Features with a waiting consumer:
`_has` (the device framework's outbound-candidate contract sketches it and
currently degrades), `_revinclude`, richer chaining, `$everything`
(docs-planned), a real `$match` (MPI's `match()` is an `identifier=`
search wearing the name), `:of-type`, Observation composites. Today these
gaps force client-side Java post-filtering (audit queries, lab worklists);
tier 2 is justified by deleting those workarounds.

**Tier 3 — not until a consumer exists.** `_filter`, `_text`/`_content`
(Postgres FTS when wanted — the legacy repo's ambition), `:above`/`:below`
terminology navigation, `_list`/`_query`/`_language`, GraphQL. Explicitly
out of scope per milestone.

**Honesty mechanism**: each personality generates its `CapabilityStatement`
from the actually-implemented parameter set, and search is **strict by
default** — an unsupported parameter is rejected, never silently ignored
(a silently dropped filter returns a *wrong result set*, which in a
clinical system is a safety issue, not a compatibility feature).

**Custom SearchParameters**: none exist today, but devices/sibling models
(R6) make personality-registered custom parameters + reindex a
first-class feature — the envelope's derived-projection design already
supports it (register extraction rule → rebuild envelope → new index).

**Adoption notes** (feeds [record 006](006-adoption-is-identity-first.md)): the server-proprietary usages an incoming
deployment has to replace rather than port — a project search parameter
(obsolete here: tenancy is structural), a compartment search parameter, bulk
terminology import, project initialisation, and expunge (which becomes
erasure-by-drop, [record 004](004-durable-work-sits-in-two-planes.md)).
