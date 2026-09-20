# Moving tests onto worlds that already exist

Tests are being taken off worlds of their own and put onto shared ones: the
harness's shared tenants, and where the behaviour is reachable through a door,
the guide's running world. This document is how that work is *run* — the loop,
the gate, and the rules that were paid for rather than reasoned out.

It is not the case for doing it. That is in the commit messages and in
[the requirement catalogue](../arc42-006-runtime/req-catalogue.md), which says
where each promise is proven.

## The shape of it

**A branch that outlives its merges.** `guide-world-migration` is rebased onto
main and merged when a stretch is done, and then stays open. The work is
continuous; closing the branch each time would cost a rebase and a fresh
worktree for nothing.

**A worktree of its own**, at a path beside the main checkout. Other sessions
edit the main checkout at the same time, and two sessions editing one tree is
how an uncommitted fix gets committed by somebody else. The worktree makes that
impossible rather than unlikely.

**A trimmed signal, and never the suite.**
[`guide-world.yml`](../../.github/workflows/guide-world.yml) runs the moved
tests and only those, on every push to `guide-world-**`, in a few minutes. It
answers one question — do the moved tests still pass *together* — and the
author never waits locally for it.

## The loop

1. Convert or cite one class.
2. Run **that class**, to see it pass at all.
3. Run **the whole shared set** — `.github/scripts/shared-world-tests.sh`.
   This is not optional and not the same question: a conversion that passes
   alone can still make the set slower or collide with a tenant it now shares.
4. Push. The trimmed job answers within a few minutes; the next class starts
   without waiting.

The set is derived, never listed: it is every class naming `SharedTenants`.
A list would be one more thing to forget, and forgetting it means a conversion
nothing runs again.

## The gate

**One full `./verify` before a merge, on the rebased tip.** Not before the
rebase — that run would not have seen what main did — and not on the trimmed
job's word.

The trimmed job cannot see `PromiseCatalogueTest`. A catalogue claiming a §14
promise was proven by a method that had been deleted sat on this branch behind
a green tick until a full run found it. That is what the gate is for.

Run it as two phases with `--no-daemon` rather than through `./verify`: the
script stops the daemon between phases, and the daemon is shared across every
worktree of this repository, so `./verify` here kills whatever another session
is running there.

## What was paid for

**A deployment-wide pass keeps its own runtime.** A class calling
`shapesRound`, `syncRound` or `scanOnce` must not join the shared world. The
sweep visits every tenant in the runtime, so converting one moves it from
sweeping one tenant to sweeping all of them — five rounds cost the shared set
nearly three minutes, against the fourteen seconds a conversion saves. And the
return value is a whole-deployment count, so `assertEquals(1, shapesRound())`
becomes a claim about every other class's tenants, passing on ordering.
Screen for those three calls *first*.

**Extend a shape before inventing one.** A missing *type* is additive: nothing
that does not declare it can notice it, and the tenant is already up, so it
costs nothing. Invent a shape only where the declaration *conflicts* — a face
binds one version, a type declares one identity class, handling is one thing —
because no tenant can hold both. Two shapes were added here for what turned out
to be two missing type declarations.

**Number the instance when the data conflicts, not the runtime.** A class that
rolls its pack version backwards, counts a tenant whole, or claims a step name
cannot share a tenant — and still does not need a runtime. `of(Shape, n)` gives
it a tenant nobody else touches for the price of a tenant, not a deployment.

**Measure against the set, not the class.** Every cost above is invisible when
the class runs alone.

## What this does not do

It does not measurably shorten the build. A conversion is worth about fourteen
seconds against a verify of twenty-five minutes, and run-to-run variance from
machine load is minutes. Two runs of the same tree came back at 46m35s and
25m43s; the difference was what else the machine was doing.

The wins are elsewhere and they are real: promises proven at the door a caller
actually uses, worlds that no longer need building, and assertions that could
not fail before and can now.
