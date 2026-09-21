**Open. The cast has started and the first class has moved onto it: delegation now runs on the hospital. Next: read the remaining fourteen single-use shapes against what a cast can hold.**

<!-- The worklist is empty and the ledger is honest, but moving classes onto one runtime made the suite slower before it made it faster: what this machine cannot carry is tenants alive at once. Next: name the shared tenants after the parts they play, so a story's worth of steps runs on a cast several stories share. -->


# Own-world tests move down the ladder

`config/worlds-ledger.txt` records every harness class that builds a
runtime of its own. Forty-four predate the ledger and carry no reason; the
[shared-world rule](../../arc42-002-constraints/working-rules/shared-world-tests.md)
says which rung each should be on, and the number may only fall.

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

1. Classify the 44 into rungs from their source: a guide step, a shared
   shape, a private tenant on the shared runtime, or one of the five
   reasons to keep a runtime. Record the result as the worklist here.
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
