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
3. **Closed with its issues.** When the last issue closes, promote anything
   still worth keeping into the arc42 docs — that is where a *permanent*
   explanation belongs — then move the file to `closed/`. The listing of this
   directory is then always the live agenda, which is the point.

## The shape

Keep the headings; drop any section that has nothing true to say rather than
padding it.

```markdown
# <Topic>

**Status** · **Issues** · **Concepts** (links to arc42 docs)

## What this is        — the problem, in a paragraph. Not the solution.
## Where it stands     — done / next / blocked-on-what. The part that rots
                         fastest, so it goes near the top.
## Decisions           — each with the reason it beat the alternative.
## Traps               — what bit, and what it looked like when it bit.
## Not doing           — deliberate exclusions, with why.
## Verifying           — the commands that prove it.
```

`Decisions` is the section that earns the document. A decision without its
reasoning is re-argued the moment someone disagrees with it; a decision *with*
its reasoning is either accepted or properly overturned.

## Live topics

| topic | status | issues |
|---|---|---|
| [Medplum → dbo](medplum-to-dbo.md) | active | [platform#851](https://github.com/jengu-net/jengu-platform/issues/851) |
| [Data versioning](data-versioning.md) | store side delivered; consumer side pending | [platform#778](https://github.com/jengu-net/jengu-platform/issues/778) |
| [The face contract](face-contract.md) | active, four slices open | [#38](https://github.com/jengu-net/dbo/issues/38) |

Everything else open in this repository is backlog: it has an issue, and it
does not yet have enough shape to need one of these.
