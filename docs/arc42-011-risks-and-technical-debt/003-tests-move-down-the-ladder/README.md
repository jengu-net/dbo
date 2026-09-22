**Open. The cast has started and one class has moved onto it: delegation runs
on the hospital. Four families have been read against it since and each resists
for a reason that can be named. The migration's own measure is finished — the
undecided count is zero, thirty-eight classes build a runtime and every one
names a reason — which also means the ratchet that drove this can never fire
again, and nothing counts the total. Next: the shapes kept apart from a cast
member only by what the sample world declares, and whether the total is worth
ratcheting.**

<!-- The worklist is empty and the ledger is honest, but moving classes onto one runtime made the suite slower before it made it faster: what this machine cannot carry is tenants alive at once. Next: name the shared tenants after the parts they play, so a story's worth of steps runs on a cast several stories share. -->


# Own-world tests move down the ladder

## What moving one actually costs and saves

Worth stating before more classes move, because the obvious arithmetic is
wrong in the direction that would make this item look better than it is.

**A private world does not pay for its face.** `ElementVersion.BY_CODE` is a
static map keyed by version and `FaceBase` is "one worker context per face and
process", so every world in a JVM shares the same definitions. The 225 MB a
carried face costs, and the 101 a records-backed one costs, are paid once by
whichever world got there first — measured in
[item 024](../024-definitions-out-of-the-heap/README.md). A class building its
own runtime is not buying a second copy of the specification.

**What it does pay is per tenant**, and that is 11 MB on a carried face or 3 to
4 on a base.

**And a private world gives it back.** Its `@AfterAll` closes the manager and
retires its databases, so what it held is released when the class finishes. The
shared world's tenants are never dropped by design — so a class moving down the
ladder converts memory that would have been returned into memory held for the
rest of the run.

So the trade is bring-up seconds against megabytes held to the end, and it is
not the trade this item started with. At a 2 GB ceiling it would have been
worth arguing about; at 3g, against a suite that spends forty minutes and most
of it coming tenants up, seconds are the scarcer thing. That is the reason to
keep moving classes — not that it saves memory, because it does not.

`config/worlds-ledger.txt` records every harness class that builds a
runtime of its own. Forty-four predated the ledger and carried no reason; the
[shared-world rule](../../arc42-002-constraints/working-rules/shared-world-tests.md)
says which rung each should be on, and the number may only fall.

**It has fallen to zero**, which is the migration's own measure saying it is
finished. Thirty-eight classes build a runtime and every one names a reason:

| | |
|---|---|
| `sweep` | 14 |
| `deployment` | 11 |
| `lifecycle` | 8 |
| `whole plane` | 2 |
| `first boot` | 2 |
| `container` | 1 |
| **`UNDECIDED`** | **0** |

And that is worth reading twice, because it means the ratchet that governed
this work can never fire again. `AWorldOfItsOwnIsADecisionTest` fails when the
ledger disagrees with the sources, when an entry has no reason, and when the
undecided count rises above the recorded allowance — which is now `0`, a
number nothing can exceed because nothing may take `UNDECIDED`. The record
stays honest; nothing holds the total down.

What holds it down instead is the judgement in each reason, and this item has
already found that judgement given too readily once. Thirteen entries were
recorded as `sweep` under a wording that meant *calls a deployment pass*, which
the scan loop does continuously; re-read under *makes a claim about the pass*,
two of them moved and one was not a sweep at all. Fourteen carry it today.

## Which class, in which order

`./gradlew :core:harness:storyCoverage` reports where each story's legs are
proven. That is the order to work in: take a story, look at the legs it
proves only in a world built for one class, and read those classes before
moving them. Two stories have been read this way.

**Fleet health keeps everything it has.** All three of its private-world
classes call the deployment's own scan, which cannot join a shared world
because it visits every tenant in the runtime. They are recorded as sweeps.

**Person rights is four and three.** Four of its classes sweep and are
recorded. The three that remain are the story's real worklist:
`ThePlaintextInFlightLeavesNoTraceIT`, which wants a tenant nothing else has
written to rather than a runtime of its own, and `HumanAuthIT` and
`FederatedAuthIT`, which want an authority and clients of their own and can
have both on a shared runtime.

**Two places is all sweeps.** Every leg it proves privately is proven by a
class that calls a deployment pass, and the four not already recorded are
now. Read the report's rows carefully here: it lists every place a leg is
proven, so a row naming a private world may still have a cheaper proof
beside it. What is owed is the rows naming a private world and nothing else.

**Clinical record is one sweep and two unproven legs.** Its single private
world belongs to a class that runs a shapes pass, so it stays. Its other two
outstanding legs are proven nowhere because nothing cites them yet: both are
PLANNED in the catalogue, which is a gap in what is built rather than a test
in the wrong world, and moving tests will never close it.

**Edge roundtrip is one sweep and two whole-plane assertions.** The sweep is
the usual case. The other two read every table of a substrate to say nothing
readable landed there, which is a claim about the entire plane rather than
about their own tenant — on a shared runtime it would be a claim about every
other class's work. That is a sixth reason, added to the ledger by finding
it rather than by imagining it.

All eight stories have now been read this way, and not one has produced a
test to move. What the reading produced instead is three reasons the ledger
did not have, stories whose private worlds are correct, one defect in the
report, and one defect in the store.

The three classes named here as candidates were read last and are not
candidates either. One configures redirect uris on its provisioner, one
stands up a zone hub with a broker and counts its ceremonies, and one
deliberately leaves a database unpinned to prove an isolated tenant refuses
it. Each needs a runtime built differently from the shared one, which is
the third reason: what a tenant cannot share is its configuration, and nor
can a deployment.

## How it is run

The migration has a branch that outlives its merges, a worktree of its own,
and a trimmed CI job that runs the moved tests together. That is described
in [how the migration is run](how-it-is-run.md), which moved here from
`docs/tasks/`.

## Steps

1. ~~Classify the 44 into rungs from their source: a guide step, a shared
   shape, a private tenant on the shared runtime, or one of the five
   reasons to keep a runtime.~~ Done, and the worklist it produced is empty:
   `UNDECIDED` is zero and every entry names a reason.
2. Move them, one or a few per change, re-recording the ledger each time so
   the allowance falls.
3. ~~Give the classes that keep a runtime their reason in the ledger, so
   undecided reaches zero.~~ Done: every entry carries one.
4. ~~Re-read the entries whose reason is `sweep`.~~ Done; see below.
5. Name the tenants after their parts. Fifteen of the first twenty-three
   shapes were used by one class, because each was named for its mechanism
   and so could not be recognised by the next test that wanted the same
   thing. Eighteen of the twenty-three declare nothing but `operational`
   types, which is to say they are one kind of tenant under different names.
   A cast — a hospital, an insurer, a zone, a face root — is what the guide
   world already has and what lets a story's worth of steps share tenants
   with the story beside it. What genuinely resists a cast is small and
   known: a type declares one handling per tenant, so replication needs its
   own upstream and downstream; a relation is declared at creation; and some
   tenants prove something by being poor.

   Started. `st-jerome` is a private clinic that keys nobody by a national
   number and takes the hospital's encounters, which fills five of the six
   combinations the suite wanted and the cast did not have; the sixth is a
   mirrored code system, which is a mechanism rather than a part and has not
   earned a sentence in the guide. `SharedTenants.cast` brings a member up
   from the sample's own spec file, with its upstreams first, so a tenant is
   defined once and the guide's container and this suite read the same
   definition.

   The first class has moved. `DelegationIT` had a shape of its own —
   people keyed by a login, the role that joins them, an encounter to write
   and the trail on — which is the hospital, spelled differently. It now
   asks for the hospital, keys its person by the national number the
   hospital already keys people by, and writes its encounter on r5. Seven
   tests, all green, and green again beside the two classes already on that
   tenant. One shape and one database left the suite.

   Reading the other fourteen single-use shapes against the cast gives a
   sharper version of the sentence above about what resists. Ten of them are
   a pair or half a pair — a mirror, a grain, a managed tenant, a zone with a
   type projected rather than written, a clinic asking for fewer types than
   its zone publishes — and each names its counterpart at creation, which a
   cast member cannot do for a single class. Three are a tenant the cast
   has no version or no extractor for: r6, a ValueSet whose envelope is
   computed in the database, and a tenant that authors profiles without
   being a face root. The fourteenth is subscriptions, which no cast member
   declares.

   That last group is the one worth looking at again, because it is the only
   one where the obstacle is what the sample world happens to declare rather
   than what a tenant can be. Adding a type to a cast member is adding it to
   the guide's world, so it is a sentence in the guide before it is a line in
   a spec — which is the right order and the reason it is not done here.

   One tempting move was read and refused: the profile-replication pair
   looks like the face root and the hospital, which already stand in exactly
   that relation. They do not, because the hospital takes that root as a
   FACE, and a face is cut once and cached — a profile written to the root
   afterwards would arrive by a path the test is not about, or not at all.

   Three families with more than one class were then read the same way, and
   all three resist for reasons worth writing down rather than rediscovering.

   **The clinic in two places** takes one type from a zone that publishes
   two, and proves it by asserting the other type is EMPTY in the clinic.
   The cast has that relation already — the insurer takes only code systems
   from the zone — but the insurer's value sets are replicated from its face
   root and are therefore never empty. An emptiness assertion is the one
   thing a shared tenant cannot carry, which is the shared-world rule's
   opening sentence arriving from a new direction.

   **The people behind the membrane** want patients and capacities with no
   national number, because what they are about is a human the store holds
   without one. The hospital keys both by that number. A type declares one
   identity class per tenant, so this is not a flag to turn off: the two
   tenants are different tenants, and the clinic that keys nobody is r5 with
   its membrane down.

   **The three identifier-keyed classes** each AUTHOR canonical content — a
   profile, a code system, a value set handed over in bulk. Every cast member
   but a face root takes its canonical types by replication, and one of the
   three counts value sets besides. Authorship and replication are the same
   type declared two ways, which is the same wall as above.

   What that leaves is the shapes kept apart from a cast member only by what
   the sample world happens to declare: subscriptions, and a face root with
   an ordinary record or two beside its definitions. Both are a change to the
   guide's world, which is a sentence in the guide before it is a line in a
   spec, and neither has earned one yet. That is the next thing to weigh, and
   it is a smaller prize than it looks — the second is one tenant, and the
   expensive one.

7. Ratchet the TOTAL, not only the undecided count. The allowance that drove
   this migration is spent — it reads `0` and nothing may take `UNDECIDED`, so
   it cannot fail again — and a class arriving tomorrow needs only to name one
   of seven reasons. The `sweep` re-reading is the evidence that a reason is
   taken too readily when nothing counts them: thirteen were recorded under a
   wording that admitted any class calling a pass. The same mechanism the
   allowance already uses would do it — a recorded number that regeneration
   lowers and the build refuses to exceed — and it costs a line in the ledger
   and a third assertion in the test that is already there. Not built here
   because it turns a judgement into a build failure, which is a decision about
   how this repository wants to be argued with rather than a defect to fix.

6. The old fourth step, kept because it is what was actually done:
   re-read the entries whose reason is `sweep`. That reason used to say a
   class needs its own runtime when it RUNS a deployment-wide pass, which is
   wrong — the scan loop runs them continuously, and a class that needs the
   effect on its own tenant can run one on the shared runtime. It now says a
   class needs one when its CLAIM is about the pass: the number it returned,
   or the troubles it left. Thirteen entries were recorded under the old
   wording and some of them will move. This item is deleted when they have
   been read.

   All thirteen are read. Eleven make a claim the old wording did not
   capture and stay: two assert the set a scan returned, six read the
   troubles or the states a scan left, one asserts the count a shapes round
   returned, one drives a fleet of runtimes rather than a runtime, and one
   takes a tenant away. One was recorded under the wrong reason
   altogether — `ZoneIT` holds two brokers' secrets, which is a deployment's
   custody and not a sweep. Two only called a round and have moved:
   `MetaSaysTheEnginesFactsIT` and `OneTenantInTwoPlacesIT`.
