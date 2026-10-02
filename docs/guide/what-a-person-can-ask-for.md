---
title: What a person can ask for
eyebrow: Guide
standfirst: >-
  The human behind a record is held under a key of her own. She is read
  without her name by default, looked up only for a stated reason, and
  forgotten by destroying the key — which reaches every copy at once.
template: essay.html
---

Liis Tamm was recorded at the hospital. Ines's platform runs a request desk
where patients ask for things, and it must never become a system where whoever
may write the most may also see the most. Her identifying data was encrypted
under a key of her own before it reached the engine, so erasure is not a
delete: destroying the key makes every copy of it pseudonymous at once. The
scene is
[US-DBO-PERSON-RIGHTS](../arc42-003-context/user-stories/us-dbo-person-rights.md),
walked at Hogwarts by
[`WhatAPersonCanAskForIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/WhatAPersonCanAskForIT.java).

## The hospital holds people behind a membrane

One line of the hospital's declaration does it — `"pdi": true` — together with
the systems that identify a `Patient` and a `Person`
([a tenant opens](a-tenant-opens.md#a-declaration-is-the-whole-of-opening-a-clinic)).
From then on what identifies somebody lives in the tenant's person vault, under
that person's own key, and the records hold pseudonymous data
([personal data](personal-data.md)).

## Who somebody is

A patient record is somebody in one capacity; a `Person` is the human, carrying
the number they are known by and linking the records that are theirs. Whether
a second claim on a number is the same human or a mistake is the hospital's to
decide, so the application asks:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/AskingWhoSomebodyIs.java:asking"
```

and the worker's step answers with the person as a record the hospital writes:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/IdentifyingAPerson.java:step"
```

Liis is then one human held as two records under one key. A second `Person`
claiming her number, or a link joining her record to somebody else who is
identified, is refused by the hospital, and the run ends with its reason.

## She asks to be forgotten

Erasure is asked for at the hospital's erasure door, with a credential that
holds `erasure` and nothing else. A credential that may write every type cannot
erase anybody, and asking with none is refused before anything is looked up:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/WhatAPersonCanAskForIT.java:erasure"
```

The answer is a run keyed by her. Asking again finds the same run, because the
second ask usually comes from somebody who did not see the answer to the
first, and the run's tally says how far the erasure got: whether a key was
there to destroy is what tells erased apart from never-held.

## What the store guarantees

- **Reading her is not writing her.** A credential that may write every type
  reads Liis back without her name and with her birth date generalised to the
  year, because what a recipient sees is declared rather than inferred from how
  much it may write. The strict mode is the default.
- **Looking somebody up is an act with a reason.** A search by her national
  number is refused until the request states a purpose, and the refusal names
  the codes that would work. Then it finds her and nobody else, and the trail
  records who looked and why.
- **Identifying, pseudonyms and her recording each have a door of their own**,
  with a scope a grant over the records does not reach. A pseudonym is the same
  each time it is asked for and is written nowhere.
- **Erasure is its own authority, asked for like any other work**, and it
  reaches every copy: afterwards her records keep their shape and lose her,
  even to a reader stating treatment, and her number, her pseudonym and her
  recording reach nobody. The trail still says something happened, and can no
  longer say to whom.
- **Staff leave differently.** A clinician is deactivated rather than deleted.
  A process acting in a clinician's name carries a token that keeps the
  clinician as its subject and names the process beside them, narrower than
  what the clinician holds, and ending the delegation or the clinician's role
  ends what the process may do.

The [joins table](../arc42-003-context/user-stories/us-dbo-person-rights.md#joins)
names the test behind each.

## What the store cannot do yet

- **Reading her back is the records surface's.** The application learns that
  the hospital wrote her, and where; it cannot read her through the run that
  wrote her. The story reads her on the records surface, with and without a
  stated purpose.
- **Content is not scoped to a run.** A recording kept for her is put through
  the hospital's content door, sealed to her; a step cannot hand content over
  with its result, and a run cannot carry content in or out.
- **Erasure by dropping a tenant is proven, and a partial estate is not.** A
  tenant that lives in two places has its own erasure question.
- **Rights other than erasure and access are not operations.** Rectification and
  restriction are ordinary writes today.
