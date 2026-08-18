# FHIR search usage inventory (evidence for the search tiers)

Snapshot 2026-08-14: every FHIR search interaction a production healthcare
platform — a clinical cloud, a laboratory system and a visit assistant —
actually issues against its FHIR server, across ~206 call sites in production
code. This is the empirical basis for the tier structure in
[§7.5](../arc42-009-architecture-decisions/README.md). All searches go through
hand-rolled REST clients taking raw query strings: no server SDK, no GraphQL,
and no batch search-entry usage.

## Used, by frequency

| Feature | ~Sites | Typical shape |
|---|---|---|
| Token `identifier=` (`sys\|val`, `sys\|` any-in-system) | 88 | the universal lookup idiom: tenant code, edge id, MRN, national ID, device id, canonical dedup key |
| `_count` | ~70 | nearly every search explicitly bounded |
| `_sort` | ~30 | overwhelmingly `-_lastUpdated`; also `-date`, `-authored`; ascending `_lastUpdated` for sync cursors |
| Plain tokens (`status=`, `intent=`, `active=`, `type=`, `service-category=`) | ~20 | worklists, active-record filters |
| Reference params (`subject=`, `based-on=`, `specimen=`, `target=`, `performer=`, …) | ~20 | `Condition?subject=Patient/<id>` |
| `_tag` | ~16 | insurance workflow state machine; audit idempotency probes |
| Canonical `url=` | ~13 | terminology / IG sync |
| String (`name`, `family`, `given`, `email`) | ~10 | MPI broker, admin lookups |
| `_elements` | 4 | terminology sync payload trimming |
| `_lastUpdated=gt/lt` (date prefixes) | 3 | sync cursor, retention cutoff |
| `:missing` | 3 | `partof:missing=true/false` tenant-org queries |
| `_summary=count` | 2 | existence/count probes |
| `_offset` explicit | 2 | (otherwise next-link paging everywhere) |
| `:not` (on `_tag`) | 2 | lab edge "not yet synced" scan |
| `:identifier` (reference by identifier) | 2 | result intake by external order system |
| Chained (one level) | 2 | `ServiceRequest?specimen.identifier=<barcode>` (lab edge) |
| `_include` (single, named) | 1 | `ServiceRequest … &_include=ServiceRequest:specimen` (deliberately *not* including Patient — pseudonymity) |
| `_profile` | 1 + Subscription criteria | shape migration; LIS observation subscription |
| `:exact` | 1 | `ClientApplication?name:exact=` |
| Conditional create/update/delete on search criteria | ~50 | `If-None-Exist: url=…` / `identifier=sys\|val` — the bootstrap/upsert workhorse |

$operations in use: `ValueSet/$expand`, `$validate`, `CodeSystem/$lookup`,
`$meta-add`. The remainder were server-proprietary — bulk terminology import,
project initialisation and expunge, project and compartment search
parameters — and have no FHIR equivalent to reproduce.

## Zero production usage

`_filter`; `_has` (only a javadoc contract sketch in the device framework — the
real implementation degrades to `status=final`); `_revinclude` (one test
fixture); `_text`/`_content`; `_security`; `_id=` as search param (reads are
`GET Type/id`); composite params; modifiers `:above :below :in :not-in :of-type
:text :contains :iterate`; `_list _source _query _type _language
_total=accurate`; `Patient/$everything` (docs-planned only); `$match` (MPI's
`match()` is named after it but implemented as an `identifier=` search);
GraphQL; custom `SearchParameter` resources.

## Reading

The platform pushes anything more expressive than token+reference+sort into
client-side post-processing: audit queries fetch bounded `_count`/`_sort`
pages and filter in application code, worklists append a `performer` filter
rather than chaining. Tier 2 exists to delete those workarounds, not to chase
spec completeness.
