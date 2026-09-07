# Tasks: the live topics, and what a session needs to pick one up

A **task document** carries a topic across sessions. It exists because the two
places that already hold knowledge each hold only half of it:

- **GitHub issues** say *what* is to be done and *whether* it is done.
- **The arc42 docs** say *how the system works* once something is done.

Neither holds the middle: the decisions that were argued and must not be
re-litigated, the traps that cost an afternoon, what is deliberately not being
built and why, and where the work actually stands right now. A session that
starts without that spends its first hour rebuilding it — and sometimes
rebuilds it *differently*, which is worse than slowly.

So a task document holds **only what neither the issues nor the docs hold**.
If a sentence belongs in a concept doc, put it there and link it. If it
belongs in an issue, put it there and link it. What is left is this.

## When a topic deserves a document

Write one when the work is a **topic**, not a task:

- it spans more than one issue, or one issue that will take more than a sitting;
- it involves decisions somebody could reasonably make differently;
- it crosses repositories, or crosses a seam (engine ↔ face, store ↔ consumer);
- it will outlive the session that starts it.

Do **not** write one for a bug fix, a single groomed slice, or anything whose
whole context fits in its issue. A directory of thin documents is worse than
no directory, because it teaches people to skip reading them.

**When unsure, ask.** The cost of asking is a sentence; the cost of guessing
wrong is either a document nobody reads or a topic nobody can resume.

## Lifecycle

1. **Opened** when the topic starts — before the first commit, so the first
   decisions land in it rather than in a transcript.
2. **Kept current** as decisions are made and traps are found. A stale task
   document is a lie with a timestamp; update it in the same commit as the work
   it describes, exactly as the REQ catalogue is updated with its slice.
3. **Closed with its issues, and deleted.** When the last issue closes,
   promote anything still worth keeping into the arc42 docs — that is where a
   *permanent* explanation belongs — then delete the file, naming it in the
   closing commit so the history is findable
   (`git log --diff-filter=D -- docs/tasks/`, then `git show <sha>`).

   Deleted rather than archived, deliberately: git already keeps it, and a
   folder of closed topics is a second pile that grows, gets skimmed, and
   eventually gets mistaken for current. The listing of this directory is then
   always exactly the live agenda, which is the point.

## The shape

Keep the headings; drop any section that has nothing true to say rather than
padding it.

```markdown
# <Topic>

**Status** · **Issues** · **Concepts** (links to arc42 docs)

## What this is        — the problem, in a paragraph. Not the solution.
## Where it stands     — done / next / blocked-on-what. The part that rots
                         fastest, so it goes near the top.
## Sequence            - the steps in the order each becomes possible, one line each, with a status (read below)
## Decisions           — each with the reason it beat the alternative.
## Traps               — what bit, and what it looked like when it bit.
## Not doing           — deliberate exclusions, with why.
## Verifying           — the commands that prove it.
```

`Decisions` is the section that earns the document. A decision without its
reasoning is re-argued the moment someone disagrees with it; a decision *with*
its reasoning is either accepted or properly overturned.

`Sequence` carries the steps in the order each becomes possible, one line each, with a status —
**DONE** (dated), **NEXT**, **READY, needs N**, **PARTLY BLOCKED**,
**BLOCKED by X**, **WRITTEN, HELD** (the work exists and is deliberately
not applied yet — say why), **LATER**. Two rules make it worth trusting: a step is
DONE only when a *capability* was verified, never when code exists; and a
blocked step names **what** blocks it and **who owns** that, so the reader
can tell a wait from a stall. Close the table with the critical path, so
"what now" is one line rather than an inference.



## Live topics

| topic | status | issues |
|---|---|---|
| [Going public](going-public.md) | public and proven from outside; jars wait on the rebuilt host | [#199](https://github.com/jengu-net/dbo/issues/199) |
| [Medplum → dbo](medplum-to-dbo.md) | the store's answers are delivered; the migration is the consumer's and is tracked there | none open here |
| [The face contract](face-contract.md) | one slice open, and the document closes with it | [#112](https://github.com/jengu-net/dbo/issues/112) |
| [IHE profiles the store should serve](ihe-profiles.md) | analysis only; SVCM next | none filed |
| [The tenant lifecycle](tenant-lifecycle.md) | built; the queue is gone and change is a transition, not a retraction — open on the consumer half | [#188](https://github.com/jengu-net/dbo/issues/188) |
| [Eventing is unreachable](eventing-is-unreachable.md) | built and closed as done; nothing constructs it | [#184](https://github.com/jengu-net/dbo/issues/184) |

Everything else open in this repository is backlog: it has an issue, and it
does not yet have enough shape to need one of these.
