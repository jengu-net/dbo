# Shape versioning: the second axis (§2)

Every stored object versions on **two axes**, and the store never conflates
them:

- **`payload_version`** — the storage format the object's bytes are held in.
  The store's own concern: converters registered by personalities upgrade old
  payloads lazily on read, and a FHIR-release transition inside a tenant is a
  converter plus a reindex ([object model](object-model.md) §2).
  `payload_version` stays internal; no face says it.
- **The shape stamp** — the version of each tenant-pack profile the object was
  validated against at accept. The tenant's concern: it is what makes "which
  of my data was written under an old shape" an answerable question, and it is
  tenant-facing on the wire.

The shape an object was authored under and the format its bytes are stored in
move independently — an R4→R5 payload conversion changes no shape, and a
profile revision changes no bytes — so a single version field would break at
the first move on either axis.

## The stamp

The fact exists at exactly one moment: **accept**, where validation resolves
the object's declared profiles against the tenant pack. For every declared
profile the pack publishes, the store records `profile|version` — the
`StructureDefinition.version` current in the pack at that write.

- **It is a per-version fact of the accept event**, stored as a column beside
  the payload in both state and history — the `payload_version`/`chain_hash`
  category, not payload content. Stored bytes stay exactly what the author
  sent, and every history version keeps its own stamp.
- **It rides the envelope** as a dimension written from that column (reindex
  rebuilds it from the row), so it is GIN-indexed from birth: shape-grain
  queries are native searches, not scans, and no bespoke index or tier-2
  machinery is involved. The envelope itself stays out of history for the
  standing reason: it is a derived cache, and derived data in immutable rows
  either mutates on reindex or goes stale.
- **On the wire it is the `urn:dbo:shape` complex extension** in
  `meta.extension` (`profile` canonical + `version` string, one per declared
  pack profile), joined at serve like the engine's other Meta facts, its
  definition published and resolvable like every dbo system.
- **It is served in `meta`** as an engine fact on the reading path, under the
  same replace-not-append rule as the engine's other Meta facts
  ([engine and faces](engine-and-faces.md)): re-stamping replaces prior
  stamps, so a migration write-back and an ordinary update are
  indistinguishable in the stored shape.
- **`meta.profile` stays an unversioned canonical.** The stamp is a separate
  fact beside the conformance claim — a version-pinned profile canonical
  changes what validators resolve, so the pin is not the stamp.
- An object declaring no pack profile is stored unstamped; the store stamps
  only shapes the pack publishes a version for.

## Asking about shapes

Two questions, answered natively:

- **Which objects sit below shape version N** for a given profile — an
  envelope-dimension search, pageable like any search, with its complement
  (at-or-above) for verification.
- **How many, per profile and per stamped version** — inventory counts beside
  the store-move inventory's existing numbers, including the count of objects
  that declare a profile and carry no stamp at all. The same report runs
  before and after a migration and diffs line by line.

## Reshape

`reshape` is a maintenance operation on the tenant surface
([maintenance](maintenance.md)): type + profile + target version in; pages
converted, refused and remaining out; resumable by cursor; rate-bounded.

- The walk is the stamp query; the rewrite is an **ordinary versioned write**,
  so history keeps the pre-conversion object addressable and version-checked
  update semantics guard concurrent writers.
- Every rewrite carries **Provenance** — what converted it, when, from which
  version — and a fresh stamp. Migrated and ordinarily-updated objects are
  indistinguishable in shape, distinguishable in audit.
- One object the converter refuses is named and left behind; it must not
  strand the rest of a tenant's data. The next run finds it still behind.
- **Conversion never changes identity** — the invariant of
  [identity rules](identity-rules.md) binds shape converters exactly as it
  binds payload converters: `url` and designated identifiers bit-exact,
  verified after every conversion.

## Newer than understood

An object stamped **above** the pack's declared version for a profile is one
the store cannot claim to understand. It is refused with a named error —
which object, which profile, stamped what, the pack declares what — never
served best-effort, because a silent misreading is indistinguishable from a
correct read to whoever holds the result.

The same refusal holds at accept: an incoming object carrying a stamp above
the pack's understanding cannot be created locally — it can only arrive by
restore or stream from a store running a newer pack, which is exactly when
the refusal must hold. What a consumer does with a tenant in that state
(hold it, upgrade the pack) is consumer policy over a store fact; the
inventory answers "how many sit above" as one question.

## Version-ahead: a door, kept open

Writing at a storage-target shape while serving the operating shape —
down-converting on read through the same converter registry, gated on a
declared round-trip property — is deliberately **not** part of this concept
yet. The registry design leaves room for the round-trip declaration so that
door stays open; nothing else here depends on it.

## Version ordering

**Major-prefix, refused loudly when unparseable.** Only the leading major
orders (`2.4 < 3.1`; `3.0` and `3.1` are the same shape for migration
purposes) — matching what a version bump means (minors are compatible, only
majors break with a converter) and how converters are keyed (per-major-hop),
so the "below N" query and the converter registry share one rule. A pack
profile version with no parseable leading major is refused at pack load —
config time, loud, fixable — never a silently unorderable stamp. The stamp
records the full version string verbatim; only the comparison collapses to
the major.

## Who converts

**Internal shape conversion is a face capability, not an engine feature.**
The engine owns the reshape loop unconditionally and knows nothing about
conversion; a face type declares — through the same capability-by-type lookup
as every other face feature — whether it can execute its model's own
converter data (the FHIR face can: pack-shipped StructureMaps; a face over a
model with no in-data converter standard cannot, and that is a truth about
the model, not a defect). Hand-back through the API is the universal floor:
the only lane for a non-capable face, and available to a capable one for a
hop that exceeds its mechanism. The face declares the capability; the
registry entry declares the lane per converter hop; the round-trip property
is a registry declaration in both lanes.

## Decided, stated above

The wire representation (`urn:dbo:shape`), the stamp's carrier (a
per-version fact column beside the payload), and stamps on streams — a
mirrored copy keeps the stamp of the store that validated it, carried
explicitly on the sync wire; only an authored accept restamps.
[Eventing and feeds](eventing-and-feeds.md) records the stream rule once the
slice lands. Remaining grooming detail lives with the slices under the
delivery epic ([dbo#130](https://github.com/jengu-net/dbo/issues/130)): the
hand-back lease surface and the registry entry shape (#133).
