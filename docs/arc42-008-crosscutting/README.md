# Crosscutting concepts

The questions no single building block owns. Each page here is one question
the whole store has to answer the same way everywhere, which is why it lives
here rather than beside whichever module happens to implement it.

Read [the engine and its faces](engine-and-faces/README.md) first. Everything else
assumes the split it describes.

## The seam

- [**The engine and its faces**](engine-and-faces/README.md) (§1) — why this is not a
  FHIR store, and what a face is allowed to know about the engine.
- [**The payload seam**](the-payload-seam/README.md) — how data crosses that line:
  bytes, framed rather than rebuilt, parsed once.

## What the store holds, and what it promises about it

- [**Records you can rely on**](records-you-can-rely-on/README.md) (§2–§3, §12) —
  truth, identity, declared shape, immutability, and rebuilding everything
  derived from the payload.
- [**Finding things**](finding-things/README.md) — search and terminology as one
  concept: an honest answer or a refusal, never an approximation.
- [**Change, and who is listening**](change-and-who-is-listening/README.md) (§6, §10)
  — one feed primitive behind paging, subscriptions, dependent copies and
  appliance sync.
- [**Processes and work**](processes-and-work/README.md) (§8) — what has to be done,
  who is entitled to do it, and how work leaves the store and comes back.

## Who may act, and what stays apart

- [**Who may act**](who-may-act/README.md) (§13, §16) — the tenant as its own trust
  root, and how systems and people get in.
- [**Data isolation**](data-isolation/README.md) (§14) — tenant from tenant, person
  from everyone, and what is declared to cross.
- [**Declared rules**](declared-rules/README.md) (§15, §17) — what a tenant must do,
  what its jurisdiction says, and how the two layer.

## Operating it

- [**Running it**](running-it/README.md) (§11) — embedding, deployment shape, backup
  as export, upgrades.

## All of the above, in one spelling

- [**The FHIR face**](the-fhir-face/README.md) — every concept on this page as a FHIR
  client receives it. Written to be readable on its own: if you know FHIR and
  nothing about this store, start there instead of here.
