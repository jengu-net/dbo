# Where a neutral store earns its keep

**Status: not scheduled.** A test for recognising domains this engine's shape fits, and the
candidates that pass it today. Written because the pattern is easier to recognise than to
re-derive, and because the windows on some of these close.

## The pattern

The IFC study generalises. What made that domain interesting was not buildings; it was a
structural situation that recurs across industries:

> A capable published standard exists. The parties who must exchange over it compete, so in
> practice each implements a dialect or the minimum. A regulator or a dominant buyer
> requires the exchange to happen anyway. **No participant can be the exchange point,
> because every candidate is an interested party.**

That last sentence is the whole opportunity. Where it holds, neutrality is not a marketing
claim — it is the only structure the parties will accept, and it cannot be supplied by any
of them.

## The test

A domain is worth looking at when all seven hold:

1. **A capable standard**, already published and genuinely able to carry the exchange.
2. **Divergent interests degrade it** — dialects, minimal compliance, deliberate friction.
3. **A forcing function** — regulation or a buyer big enough that exchange is not optional.
4. **The exchange is a process**, with obligations and steps, not a file drop.
5. **Provenance is required**, so somebody must be trusted to hold the record.
6. **No credible neutral incumbent** — every existing hub is a participant, or none exists.
7. **The operator can be paid without monetising the data.**

Condition 3 separates a market from a good idea. Condition 6 is what disqualifies most
candidates, and it is worth checking first because it is cheapest to check.

Conditions 4 and 5 are what make a *store with governed processes* the answer rather than a
message broker. Where they fail, the domain needs a pipe, and a pipe is a worse business.

## Candidates

| Domain | Standard | Forcing function | Condition 6 |
| --- | --- | --- | --- |
| Product passports | ESPR and the battery passport; standards still forming | mandates arriving from 2027 | **open — no incumbent** |
| Clinical trials | CDISC ODM / SDTM, Define-XML | regulator submissions | contract research organisations are participants |
| Ports and customs | single-window mandates, container-carrier standards | mandatory since 2024 | port authorities are semi-neutral |
| Charging roaming | the open roaming protocol | transparency regulation | **occupied** — hubs exist, some consortium-owned |
| Energy flexibility | grid model exchange standards | network codes | national data hubs hold metering; flexibility is freer |
| Open banking | mandated interfaces, one per bank in practice | mandated APIs | crowded commercially |
| Agricultural machinery | machinery and field-data standards | weak — the buyer is fragmented | vendors actively resist |

**Product passports score best on the test as written.** A legal mandate arriving while the
standards are still forming; every actor in a value chain obliged to contribute; provenance
*is* the deliverable rather than a supporting feature; and condition 6 held open by
structure rather than by luck — a brand cannot ask its suppliers to trust its database, and
suppliers will not disclose upstream detail to a customer.

**Clinical trials score best on adjacency** to a health-standards practice: the same
regulators, the same provenance demands, the same standards culture, and a payload problem
this engine has not met (tabular datasets rather than documents).

## Two things to know before getting excited

**The thesis has a name in policy, and funded programmes attached.** European "data spaces"
are this argument as industrial policy, with reference architectures and consortia per
sector. That is strong validation that the problem is real and that money exists for it. It
also means several of these domains already have an anointed consortium to argue with. The
observable gap is that such programmes produce governance and architecture faster than they
produce working software.

**Neutrality is a governance property, not a technical one**, and it constrains the business
model more than the code:

- the operator cannot also sell a tool that competes with participants;
- the data flowing through cannot be the thing that is monetised;
- exit and portability have to be real, and visibly so;
- an exchange owned by a participant is not trusted as one, whatever its architecture.

These are the constraints that decide whether a neutral exchange is believed. They are worth
stating early because they are cheap to honour by design and very expensive to retrofit.

## What this engine would need per domain

Recorded in the companion study for buildings, and generalising:

- the **object boundary** is a decision each domain forces differently, and the engine's
  contract has no place for it yet;
- **what a particular recipient may see** is not the same question as what may leave at all,
  and several of these domains need the first;
- **third-party attestation over an object** — a participant signing a claim about a thing —
  is not the same as the store attesting to its own custody.

None of that is an argument against the shape. It is the list of what a second and third
domain would ask for that the first one never did.
