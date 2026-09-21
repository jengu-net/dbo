**Open. Every run does every task, because the build cache is off and some tasks do not declare what they read. A documentation-only change pays the whole suite. Next: declare the inputs, then turn caching on for a small set and measure.**

# The build repeats work whose inputs did not change

Forty minutes is what a pull request costs, and it costs the same whether the
change is a new storage rule or a paragraph in a chapter. Two pull requests
this week were documentation only and each paid it twice, once per re-run.

## Why it repeats

`org.gradle.caching` is not set, anywhere. Neither is `org.gradle.parallel`.
The dials the build does carry are a two-gigabyte test heap and a test
parallelism of one, both deliberate and both about memory rather than about
repetition.

So the only thing standing between a task and running again is Gradle's
up-to-date check, which holds within one checkout and is worth nothing on a
CI runner that starts from a fresh one. Every task runs, every time, on every
commit.

## What it would be worth

The clearest case is the class that costs the most: a comparison of every
definition of every carried face, JSON path against toolchain, nineteen
thousand definitions at thirteen milliseconds each. It is four minutes and
it is correct — the JSON path exists because the toolchain cannot parse a
definition before the tenant holds the version it belongs to, and this is
what permits that path to exist. Its answer depends on the carried packages
and the parameter compiler and on nothing else, so between two commits that
touch neither, running it twice asks a question already answered.

The same holds, with different inputs, for most of the suite.

## What has to be true first

**A task must declare what it reads.** This build has already been caught
not doing that: `moduleMap` had an output and no inputs, so it read
UP-TO-DATE after a dependency changed and the committed map stayed behind
the build it described. That was found by accident. A cache turns every
such omission from a stale file into a green build that proved nothing,
which is a worse failure and a quieter one.

**A test's environment is an input it cannot declare.** Much of this suite
drives Docker. A cached pass carries no evidence that the container it
passed against resembles today's. Where that matters the task should not be
cacheable, and saying which is part of the work rather than a detail of it.

## Steps

1. Audit inputs, task by task, starting with the ones that write into the
   tree: the recorded projections, the ledgers, the skills. Each either
   declares what it reads or says why it cannot.
2. Turn caching on for a named few — the ones with file inputs and file
   outputs and no container — and measure a documentation-only change
   against a code change.
3. Widen by evidence, never by default. A task joins the set when somebody
   can say what its inputs are.
4. Revisit `dboTestParallelism` and `org.gradle.parallel` separately and
   with their own measurement. They are memory decisions today and may be
   the right ones; nothing here has measured them.

## What this is not

Not a reason to make the four-minute comparison cheaper by sampling. It
checks a shortcut against the real thing over the whole population, which is
the only form of that check worth having. The aim is to stop repeating it,
not to weaken it.
