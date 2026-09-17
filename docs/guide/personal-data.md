---
title: Personal data
eyebrow: Guide
standfirst: >-
  A tenant can put the elements that identify a person behind a membrane — held
  encrypted under that person's own key, reassembled for a reader who may see
  them, and answered for only when the asker says why.
template: essay.html
---

Most stores treat *personal data* as a policy: a column marked sensitive, a
review before an export, a rule somebody is supposed to follow. It works until
the one time somebody does not follow it.

Here it is structural. A tenant that declares it keeps the identifying elements
of a person somewhere the main store cannot read, and everything that reaches
them goes through a door that records what it did.

## One line turns it on

```json
"pdi": true
```

That is the whole declaration, and it is **per tenant** — a deployment can hold
a clinical tenant behind the membrane and a terminology tenant that has no
people in it at all.

!!! warning "It is a cold change"

    Turning it on moves where identifying data lives, so it is not something a
    serving tenant absorbs — the same class of change as switching a tenant's
    face. Decide it when the tenant is declared. [Lifecycle](lifecycle.md) has
    the general rule.

## What counts as identifying

Not a guess, and not a per-field annotation somebody maintains. The face
declares it for the types that are about people — `Person`, `Patient`,
`Practitioner`, `RelatedPerson`:

| Element | What happens to it |
|---|---|
| `identifier`, `name`, `telecom`, `address`, `photo`, `contact` | **sealed** — held in the vault under the person's key |
| `birthDate` | **generalised** — sealed like the rest, with the year kept in the clear beside it |

That last row is worth a sentence, because it looks like a compromise and is
not. The exact date is sealed with everything else; what is kept in the clear
is the year alone. A reader holding the key still gets the whole date — the
lookup below returns `1980-07-31` — and a reader without it sees a year.

The coarse value has to be computed on write, because a reader who cannot
decrypt has no plaintext to derive a year *from*. What it buys is ordinary
clinical work — cohorts, age bands, plausibility — for readers who are not
entitled to a birth date.

## Reading is unchanged, which is the point

A caller who may see the person gets the whole resource back, reassembled. No
second call, no decrypt step, no flag to remember.

That is deliberate and it is the difference between a membrane and a
convention: **isolation is beneath the API, not a caller discipline.** Code that
was written before the tenant turned this on keeps working, and code written
after it cannot forget to do the right thing, because there is nothing to
forget.

What changes is not reading. It is *asking*.

## Asking by name is refused, not answered empty

```bash
--8<-- "docs/guide/examples/check.sh:pdi-name-search"
```

```
this store holds Patient.name under the membrane and cannot match on it: only
exact lookup on the elements it indexes is supported, and a name is not one of
them. An empty result would have said nobody matches, which is a different
thing.
```

Under the membrane the identifying elements are not in the searchable payload
at all, so a name query matches nothing — and returning an empty bundle would
say **nobody here is called that**, which is a different fact with a different
consequence.

!!! danger "The silence is the hole this exists to close"

    An answer a caller cannot distinguish from the truth is worse than a
    refusal they can act on, and this one would never appear in a read audit —
    because no read happened. A store that quietly answered *no matches* would
    be lying to a clinician looking for a patient who is right there.

So it says it cannot match on that element, rather than pretending the answer
is empty.

## Exact lookups work, and say why they are asking

What the vault *does* index is exact: a claimed identifier, `system|value`. That
is the question a real integration asks — a national number, a chart number,
somebody arriving with a referral.

```bash
--8<-- "docs/guide/examples/check.sh:search"
```

But the credential has to have said what it is for:

```bash
--8<-- "docs/guide/examples/check.sh:pdi-no-purpose"
```

```
searching Patient by identifier is an identifying access and needs a stated
purpose — an HL7 PurposeOfUse code such as TREAT or PATRQT. Without one this
store will not match on it, and will not pretend the answer is empty.
```

Resolving a person by their national number is a **disclosure**, and a
disclosure without a stated reason is refused. The hospital's own credential
states `TREAT`, which is why every other chapter's searches work —
[Authority](authority.md) is where that is set.

The match itself runs over keyed hashes rather than values, and every
resolution leaves a fingerprint in [the trail](the-trail.md). After erasure the
answer is empty, and the person is not distinguishable from one who was never
here — which is [Erasure](erasure.md).

## What the store holds when nobody is looking

Pseudonymous records. The payload in the main store carries the clinical
content and not the person; the identifying elements are ciphertext in the
vault, wrapped per person.

This is what makes the operational story honest rather than aspirational: a
backup, a restore, a replica, an operator with database access — none of them
reach identifying data, because it is not there in a readable form.
[Encryption](encryption.md) is how the keys work, and
[Export and import](export-and-import.md) is the archive carrying ciphertext
end to end.

## What you would otherwise have written

A column-level encryption scheme, and the key-management story that was going
to be a later ticket.

A rule that says *do not log identifiers*, and the grep you run after somebody
does.

A search that quietly returns nothing for data you cannot index, and the
support ticket from a clinician who was certain the patient was there.

A purpose-of-use field threaded through every call, validated by nothing, blank
in a third of the rows — and the audit question it cannot answer.

And the conversation about whether the analytics replica counts as personal
data, held after it was built.
