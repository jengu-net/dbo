**Open. The cache is on, and CI has now said what it is worth: a documentation-only pull request went from forty-four minutes to thirty-seven, and one touching sources went to forty-five. The cache can only ever return the part CI barely spends time on. Next: the forty minutes is the container suites, and they are items 002 and 003.**

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

## What turning it on bought

Measured locally, deleting build directories between runs because that is
what a fresh checkout looks like:

| | Cold | After a clean |
|---|---|---|
| Three module test tasks | 342 s | 28 s |
| The build without the container tests | 123 s | 73 s, 90 tasks from cache |

The element module carries the four-minute comparison of every definition of
every carried face, and it is one of the tasks restored.

## And what it bought in CI, which is the number that mattered

The `build` job, on four consecutive pull requests:

| Commit | What it changed | `build` |
|---|---|---|
| `59f08275` | before the cache | 43m 38s |
| `f22a5800` | the cache, first run — writing it | 43m 36s |
| `2197097d` | a harness source | 45m 25s |
| `41cdfa8a` | documentation only | **37m 09s** |

So the cache does work between runners, and it is worth about six and a half
minutes — fifteen per cent — on the kind of change this item was written
about. On a change that touches sources it is worth nothing, which is not a
disappointment: recompiling is what a source change is for.

**The honest reading is that the lever is somewhere else.** Six minutes is
the whole of what the non-container build costs, so restoring all of it can
never return more than that. The other thirty-seven minutes is five test
tasks that drive Docker, and they are excluded on purpose and correctly.
Nothing about caching can reach them.

What can is fewer and shorter container suites: one world serving more of
the proofs, which is item 003, on an application that runs the stories, which
is item 002. This item should not be read as the answer to the forty
minutes. It is the answer to paying the forty minutes for a paragraph, and
that part now costs thirty-seven.

## What is kept out, and why it says so

The tests that drive Docker are not cacheable, and each says it where it is
configured: the harness suite, the distribution test, the memory
measurement, the guide and the conformance report. What a container held is
an input none of them can declare, so a restored pass would claim a suite
succeeded against a world nobody can describe.

## The hazard, found and closed

Three modules read the tree by path rather than through the classpath, which
is the shape of input a cache cannot see. Two are the excluded ones. The
third is the sample's check that a chapter quoting the world is quoting it
truly, and it now declares the chapters and the world as inputs.

That declaration was proven rather than assumed: with the chapters unchanged
the test comes from the cache, and editing a quoted line makes the task
re-run and go red.

The tasks that write into the tree — the ledgers, the projections, the
skills — declare no outputs at all, so nothing can cache them and they run
every time. That is why they were safe to leave while the cache went on, and
it is still worth giving them inputs so they can be skipped.

## Steps

1. Audit inputs, task by task, starting with the ones that write into the
   tree: the recorded projections, the ledgers, the skills. Each either
   declares what it reads or says why it cannot.
2. ~~Turn caching on for a named few and measure.~~ Done, and wider than
   planned: caching is on for everything except the tasks that drive a
   container, which are named and excluded rather than left to chance.
3. Widen by evidence, never by default. A task joins the set when somebody
   can say what its inputs are.
4. ~~Watch a documentation-only pull request in CI.~~ Done: 37m 09s against
   43m 38s. See the table above.
5. Revisit `dboTestParallelism` and `org.gradle.parallel` separately and
   with their own measurement. They are memory decisions today and may be
   the right ones; nothing here has measured them. This is now the only
   dial left in this item that could move the container suites, and the
   reason to be careful is the same as it always was: what this machine
   cannot carry is tenants alive at once.

## What this is not

Not a reason to make the four-minute comparison cheaper by sampling. It
checks a shortcut against the real thing over the whole population, which is
the only form of that check worth having. The aim is to stop repeating it,
not to weaken it.
