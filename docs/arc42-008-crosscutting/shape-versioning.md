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

- **It rides the envelope** as a dimension, so it is GIN-indexed from birth:
  shape-grain queries are native searches, not scans, and no bespoke index or
  tier-2 machinery is involved.
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

## Open decisions

Recorded here so the doc is honest about its edges; each is decided in the
grooming of its slice under the delivery epic
([dbo#130](https://github.com/jengu-net/dbo/issues/130)) and written back
into this doc as it lands:

1. **The wire representation of the served stamp** — the default candidate is
   a dbo-published complex extension (canonical + published CodeSystem, since
   every dbo system on the wire must be resolvable).
2. **Version ordering** — how "below version N" compares pack versions
   (major-prefix vs full ordering); the query and the converter keying must
   share one rule.
3. **Converter ownership** — pack-shipped StructureMaps executed in-process
   (converters as catalogue data, the default proposal) vs consumer-supplied
   conversion through the API.
4. **Stamps on streams** — whether a streamed copy's stamp travels with it or
   is recomputed at apply against the receiving tenant's pack
   ([eventing and feeds](eventing-and-feeds.md) records the outcome).
