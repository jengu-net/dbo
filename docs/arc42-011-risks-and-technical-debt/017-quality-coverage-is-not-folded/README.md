**Open. The twelve goals are declared and the quality tree is generated from
them, coverage and all, under the ratchet the requirement catalogue is under —
so a goal cannot go missing from it and a fold cannot go stale. Four goals are
short: isolation 5/7, process-as-storage 7/9, portability 6/7, and performance
1/6. The last of those is read out now and it is not mostly about
measurement: four of its five missing points are PLANNED scaling promises that
no item describes, and the figures item 015 would take are the fifth. Next:
those figures, which need hardware, and a decision about whether the tree
should name the four.**

# Quality coverage is not folded

[The quality tree](../../arc42-010-quality-requirements/README.md) maps each
goal to the areas that carry it, and the mapping lives in a table a person
wrote. The goals are declared now, beside that table rather than instead of
it: nothing reads the declarations, so the table is still what a reader has.

How well that goes is already on the record. The introduction lists twelve
goals; the table had eleven rows, and the missing one was the last — operational
honesty, which is strict search, an honest CapabilityStatement and an entry per
boundary crossing. It has a row now. Nothing could have noticed it was gone,
because a table is a list somebody keeps in step by hand, and this item is
about not doing that. The promise model already has the classification this wants: a
**Quality** is the technical view of a promise, it declares the promises that
fulfil it, and coverage is folded from their statuses rather than asserted.

So nothing computes a goal's coverage, and nothing fails when a goal loses
its last proof.

## Steps

1. ~~Declare a `DboQualities` catalogue, one constant per goal, each declaring
   the promises that fulfil it.~~ Done. Twelve constants, and the promises are
   chosen rather than swept in by area: an area is where a promise lives, a
   quality is what would stop being true without it.
2. ~~Make a declared hole renderable.~~ Done, and it was a defect in the
   projection rather than a missing feature: see below.
3. ~~Replace the tree's hand-written table with the projection, under the same
   ratchet the requirement catalogue is under.~~ Done. The chapter carries
   markers and a generated table of goal, areas and fold; `PromiseCatalogueTest`
   regenerates it in memory on every build and refuses a hand-edit or a stale
   copy. Proven by making the edit: changing one fold by hand fails the suite.
   The areas are derived from the promises rather than named again, which is
   the column the old table kept by hand and the row it lost.
4. Take the performance figures the goal asks for, which is
   [item 015](../015-the-comparative-load-test/README.md). The gap the quality
   now declares is what that work closes, and it counts against the goal until
   it does — **one sixth of it.** The other four missing points are the `SCAL`
   promises, all PLANNED, which is horizontal scaling and is not measurement at
   all. `bench/` carries the runner and the discipline and `bench/results/`
   holds a schema and no figures, so the measurement half is waiting on
   hardware rather than on design.
5. Decide whether four PLANNED promises under one declared goal, with no item
   describing them, is a gap this tree should name the way it names a missing
   figure. It would be the same mechanism reaching one level further out: from
   *this goal lacks a number* to *this goal rests on behaviour nobody is
   building*. Not done here, because naming it is a claim about what the
   store intends rather than about what it has.

## What the declaring turned up

**No gap could be declared anywhere, and nobody had noticed because none had
been.** `Promise.gap` is a first-class part of the model —
REQ-DBO-PRM-GAP-IS-FIRST-CLASS promises that unstated ground is named rather
than silent — and the requirement-catalogue projection refused every one of
them. Its guard against a promise belonging to no section compared codes
against the area prefixes, and a gap's code is synthetic and carries the
declaring catalogue's namespace, so it matched nothing and was reported as
homeless. The guard is right to exist: it was added after six identification
promises were rendered into nothing. It was simply asking the wrong question of
a gap, which has no area by construction and is placed through its declarer.

The fix is three lines and a second guard, so the silence does not move: a gap
is exempt from the prefix check and refused instead when its declarer names
nothing that has a section. A gap is now rendered once, in the section of the
first promise its declarer lists — the declarer's order rather than the
projection's, because walking the section list filed a missing performance
figure under container and embedding, which is where that quality's last
promise happens to live rather than what the hole is about.

**So the performance goal now says what it lacks.** Its coverage reads 1/6,
and the sixth line is the figures nobody has taken.

**And reading the other five says something the 1/6 alone does not.** Four of
them — `SCAL_DURABLE_ASSIGNMENT`, `SCAL_SINGLE_WRITER_TENANT`,
`SCAL_TRANSPARENT_ROUTING`, `SCAL_TWO_HOP_LOCALITY` — are **PLANNED**, which is
behaviour not built rather than behaviour unproven. The one that is PROVEN is
`CONT_FAST_COLD_START`.

So taking the figures would move this goal from one to two of six, not from one
to six, and the other four are horizontal scaling: work no item describes.
That is worth saying because the step below reads as though item 015 stands
between this goal and its coverage, and it does not — it closes a sixth of it.
A declared goal resting mostly on unbuilt behaviour with nothing tracking that
behaviour is exactly what a quality tree is for making visible, and this is the
tree doing it on its first reading.

**And three other goals turned out to be short.** Nothing was hiding them; the
table simply never said. Total tenant isolation is 5 of 7, process as a storage
concern 7 of 9, portability 6 of 7. Those are promises declared and not yet
proven rather than behaviour missing, and they are now visible on the page that
exists to say how each goal is judged.

## What this is not

It is not a second catalogue of behaviour. A quality declares promises that
already exist; it states no behaviour of its own, and a goal with nothing to
declare is a goal this store has not yet made true.
