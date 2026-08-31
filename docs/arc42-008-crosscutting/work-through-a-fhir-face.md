# Work through a FHIR face (§8)

## What this document is

[Processes and work](processes-and-work.md) describes the concepts — a process,
a step, a run, a participant, a claim — in the engine's own words, which name no
standard and no industry. This describes what those concepts look like to a
reader who speaks **FHIR**, because that is the face the store serves today.

It is one document for every FHIR version. The mapping below is
version-independent: the resources it uses exist in R4, R5 and R6, and where a
version spells something differently that is the face's problem, not the
concept's. A second standard would get a second document like this one and would
change nothing on the other side.

**The engine never says any of these words.** No part of the store that decides
anything mentions `Task`, and this separation is not tidiness — it is what makes
a version a configuration rather than a fork, and what lets a domain that has no
FHIR at all use the same machinery.

## The mapping

| Concept | Rendered as | Notes |
| --- | --- | --- |
| A process definition | `PlanDefinition` | generated from the code-owned catalogue, never hand-written |
| A step definition | `ActivityDefinition` | contained in or referenced by its process |
| A run | `Task` | the run's own record, carrying holder, process, step, tally and correlation |
| An item of a pipeline run | a `Task` under the run's | one collection: parent and children read together |
| Which definition a run was of | `instantiatesCanonical` | the link back that makes a running thing traceable to what defined it |
| Who holds it now | the task's owner | a person, or something that claimed it |
| Where it has got to | `businessStatus` | the declared milestone and its position — "validated, 2 of 3" |
| What the step acts on | `Task.focus` | the item, the shape the step declares it consumes |
| The documents the work is over | `Task.input`, in declaration order | the step's declared slots; references displayed, not resolved |
| A failure on an item | `OperationOutcome` | on the item's own task, so the reason travels with the thing that failed |

## What does not cross

Some of the concept has no rendering, deliberately.

**A participant's claim mechanics.** Poll, claim, checkpoint, release — these are
how a run *changes*, and they are the participation surface rather than the read
surface. A reader sees the result on the task, never the protocol that produced
it, and there is no FHIR operation for taking work.

**Reach and entitlement.** What a participant may claim is decided before any
face is involved, so nothing about it appears in a rendered resource. A face that
exposed it would be describing an authorisation decision as though it were
content.

**Presence.** Whether a participant is answering is derived from how far it has
read, which is a fact about a cursor and not about a resource. An operator asks
the console or the store; a task never claims that its owner is alive.

**Anything about routed things.** What sits behind a connector is a record in the
engine like any other, and a version that spells connected things as a resource
of its own may project it — but the engine's record is chosen so that projection
is mechanical, and the word does not appear on this side of the line.

## Two rules for whoever writes a projection

**Generated, never hand-edited.** The catalogue projections are produced from the
code-owned definitions. A hand-edited `PlanDefinition` is a second definition of
a process, and two definitions of one thing is the collision the catalogue
refuses everywhere else.

**Rendering is one-directional.** A face renders a run; it does not accept one
back. Work changes through the participation surface, by a party holding a claim,
because that is where entitlement is checked and where the deadline lives. A face
that let a `Task` be PUT into a new state would be a second door onto work with
none of the rules behind it.

## Where the detail is

- the concepts themselves — [processes and work](processes-and-work.md);
- what a face owes the engine in general, and where those obligations live —
  [the engine and its faces](engine-and-faces.md);
- what is promised about rendering, and what proves it — the
  [REQ catalogue](../arc42-006-runtime/req-catalogue.md).
