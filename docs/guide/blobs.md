---
title: Blobs
eyebrow: Guide
standfirst: >-
  Content a tenant holds whole — a scan, a recording, a sealed document. Kept
  opaque on purpose, guarded by the grant you already have, and carried by the
  archive with everything else.
template: essay.html
---

Not everything a record refers to is a record. A radiology image, a scanned
consent form, a dictation. They are large, they are meaningless to a search,
and they belong to the tenant as much as anything else does.

## Writing one

```bash
--8<-- "docs/guide/examples/check.sh:blob-write"
```

```json
{"key":"01a0afda-6ae6-…","size":13}
```

A key and the length. The key is what a record points at, and it is a store
assignment like any other id — you do not choose it, for the same reason
[the quick start](quick-start.md) would not let you choose a record's.

## Reading it back

```bash
--8<-- "docs/guide/examples/check.sh:blob-read"
```

```
Content-type: application/pdf
Content-length: 13
```

The bytes you wrote, and the media type **you** declared, handed back as you
gave it.

## Opaque, deliberately

Nothing here sniffs the content, transcodes it, or normalises it. That is a
decision rather than an omission, and it has two reasons.

The first is corruption. Content that identifies somebody is often sealed
before it arrives, so the store frequently cannot read it *even in principle* —
and a store guessing at ciphertext would be guessing about the one thing it
must not alter.

The second is honesty about classification. The media type is the writer's
statement about their own content, kept and returned, never inferred.

!!! warning "The store cannot classify what it cannot read"

    Putting something unsealed through here is a decision about your own data.
    This store cannot tell whether opaque bytes identify somebody, so it cannot
    classify them — and the protections that work on records, the ones
    [Personal data](personal-data.md) describes, have nothing to say about a
    blob they cannot read.

    A blob is not a hiding place for something that should have been a record.

## Guarded by the grant you already hold

A blob is not behind a scope of its own. It is guarded by the scope for
`Binary` — the type in the specification that *is* binary content:

```bash
--8<-- "docs/guide/examples/check.sh:blob-unheld"
```

```
401
404
```

No credential, no answer. And a key that does not exist answers `404` — the
same shape of refusal [Reaching data through a run](runs.md) explains at
length, for the same reason: an answer that distinguished *forbidden* from
*absent* would be a way to find out what a tenant holds.

Reusing `Binary` rather than inventing a scope means whoever may write a
record about somebody may write the recording it points at, and whoever may
read one may read it. A new scope would have made this the single surface a
consumer's existing grant did not describe.

## Where the bytes live

In the tenant's own database, like everything else it holds — so a blob is
dropped when the tenant is, carried when the tenant is exported, and restored
with it. [Export and import](export-and-import.md) covers a tenant leaving as
one sealed file; blobs are inside that file.

That is the property worth having and the one a bucket beside the database
does not give you: no second lifecycle, no second retention policy, and no
orphaned objects surviving the tenant that owned them.

!!! info "A dedicated storage tier is specified and not built"

    Bytes live in the tenant's database today. A credential-blind blob store —
    where large content sits outside the database while staying the tenant's,
    and the store handling it cannot read it — is specified and not built. What
    is written here works; what is missing is a place to put a great deal of it
    without the database carrying the weight.

## What you would otherwise have written

A bucket, credentials for it, and a second answer to *who may see this* that
has to agree with the first one.

A lifecycle that does not match the database's, so deleting a tenant leaves
objects behind — discovered in a bill, or in an audit, and rarely soon.

A media-type sniffer that is wrong about one vendor's files, and the ticket
about it that stays open for a year.

And an encryption story bolted on afterwards, because the bucket was added
before anybody asked whether the contents identified anybody.
