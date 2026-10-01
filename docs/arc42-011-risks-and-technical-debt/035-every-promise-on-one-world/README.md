**Open, and step 1 is built. 127 test classes boot a dbo world and four of them
do it through `@DboSpringBootTest`; the suite takes over an hour, most of it
tenants coming up. The plan is one world in one JVM, one class per user story
walking that story's legs in order, the stories running at the same time, and
every promise proven inside a leg.
Steps 1 and 2 are built. Eight story classes walk the sample world
concurrently, 55 legs in about four minutes, and five harness story classes
are gone. Moving them found four defects in the store, all fixed; one of them
held every tenant's sync for four minutes after each bring-up. Next: step 3,
fitting the promises the stories do not yet prove.**

# Every promise proven on one world, inside its story

## What this is

A test that builds its own world, or prepares a state of its own on a shared
one, pays for a bring-up nobody else uses. Repeated across 127 classes, that is
why the suite takes over an hour. Item 003 moved classes onto the harness's
shared runtime one at a time. It stopped with 38 classes still holding a
runtime, each for a recorded reason, and with 22 shapes and a six-member cast
on the shared one.

This item changes what the target is. The target is no longer the cheapest
world for a class. It is one world, the sample world, served by one Spring
context in one JVM through `@DboSpringBootTest`. Each promise is proven at the
point of a user story where it already applies. A promise does not get a world
arranged for it.

**This is not a port.** No class is moved. Its promise is proven in a story
leg, and its arrangement is used as preparation for that leg where it helps.
The class is deleted once nothing it proves is proven only by it.

It supersedes [item 003](../003-tests-move-down-the-ladder/README.md). Item
003's findings are the traps this item starts from, and 003 is deleted when
step 2 lands.

## The rules

1. **One world.** It is
   [`samples/sample-world`](https://github.com/jengu-net/dbo/tree/main/samples/sample-world)
   (`mom` and six tenants), served by one context. It is also the guide's
   world, and `sample/` is deprecated. The world may change to fit the
   stories (a type, a relation, a zone setting, a deployment property), but
   only in ways the guide could show a reader.
   That change goes into the world's spec files. A story never makes it at
   run time.
2. **The stories run at once.** Story classes run concurrently in that one
   context. A class's own legs run in order on one thread. A failure that
   shows only when the stories run together is a defect in the store, and it
   is fixed. Running the stories one after another to avoid it is not
   allowed.
3. **A story owns what it makes, and its name says so.** Everything a story
   creates carries its story's prefix, so no other story can find it, change
   it or count it. See [keeping stories apart](#keeping-stories-apart).
4. **Few databases.** Every tenant is a database and a bring-up of 25 to 60
   seconds. A leg uses a world member when one can play the part. A leg that
   needs a tenant the world does not hold declares it with
   `DboTestContext.declare` under its story's prefix, and retracts it before
   the class ends.
5. **Reuse before writing.** A source class's arrangement is preparation for
   the leg its promise moves into. A leg the story already walks is extended
   with assertions. It is not repeated.
6. **One class per story.** Test methods are ordered, and each method is one
   leg of the journey. The leg before a method is its setup.
7. **A story proves what its journey touches, not what its prose says.**
   Audit, validation, encryption, disclosure and refusals apply on every leg
   whether the story's text mentions them or not. The leg where they apply is
   where they are asserted. This is what makes a fixed number of actions prove
   many promises.
8. **A story may grow.** A promise with no leg is given one. The story's page
   gains the scene, and its `DboStories` constant gains the promise.
9. **The joins may be stale.** `DboStories` is the starting point and is
   corrected while the work goes on, not taken as true.
10. **Assertions are scoped to what the leg made**, as the
    [shared-world rule](../../arc42-002-constraints/working-rules/shared-world-tests.md)
    already requires. Nothing is counted. While several stories run at once,
    a count is wrong even when it happens to pass.
11. **Long classes are composed.** A story class that grows past reading gets
    helpers per leg family, and those helpers are built on `DboTestContext`'s
    verbs (`write`, `read`, `search`, `says`, `capability`, `asking`,
    `declare`). They are not a second harness.

## Keeping stories apart

The world's members are shared by every story at once, and they hold data
from earlier runs too. The database outlives a run wherever the container is
reused. So two things separate what one story makes from everything else:

- **A prefix:** the story's code in lowercase, such as `clinical-record` or
  `edge-roundtrip`, taken from its `DboStories` constant so that it cannot
  drift from the story it names.
- **A run mark:** a short value minted once per JVM and appended to the
  prefix, so a rerun never meets the data a previous run left behind.

| What a story makes | How it is kept apart |
|---|---|
| A tenant it declares | Code `<prefix>-<role>-<run>`, retracted in `@AfterAll`. Only a tenant the story declared is ever retracted |
| A person, patient or practitioner | An identifier in the story's own system, `urn:dbo:story:<prefix>`, whose value carries the run mark. Every lookup goes through that system |
| A resource whose id the client assigns | The id carries the prefix and the run mark |
| Canonical content: a profile, code system, value set or search parameter | A canonical URL under `https://story.dbo.test/<prefix>/<run>/` |
| A step, a client, an audience, a subscription | A code or id carrying the prefix and the run mark |
| A SCIM user or group | `externalId` and `userName` carrying the prefix and the run mark |
| A search or a read of the trail | Filtered to the story's own identifiers or subjects. Nothing unfiltered |
| A wait | Polls for its own record's state, and drives its own tenant's stream rather than a deployment-wide round |

**The world's members are read-only in configuration.** A story writes records
to the hospital. It never changes what the hospital declares, because every
other story running at that moment would see the change. A leg that needs a
member configured differently declares a tenant of its own. When the
difference is general enough to be wanted by more than one story, it goes into
the world's spec (rule 1).

**Deployment-wide state is shared and has to stay neutral.** The scan loop,
the definitions cache, the fleet steps declared on `mom`, the worker's identity
and its lanes belong to the whole deployment. A story uses them and asserts
only on its own share: its run, its tenant in a pass's answer, its row in a
register.

The prefix, the run mark and the identifier system come from one place, a
helper the story base gives each class (step 1). A story never assembles them
by hand, so two stories cannot both choose the same code by accident.

## Where it stands

Counted on 2026-10-01 from the sources. The class-by-class and
promise-by-promise picture is in the [listing](listing.md).

| | |
|---|---|
| Classes booting a dbo world | 127 (64 on the harness's shared tenants, 47 with a runtime of their own, 8 starting Felix, 4 through Spring without the annotation, 3 as a process or container) |
| …of them on `@DboSpringBootTest` | 4, all assembly or sample tests |
| Promises in the catalogue | 342 |
| …proven only by a class that boots a world | 164 |
| …cited by nothing (PLANNED) | 21 |
| …declared by no story | 21; 17 of them are proven only in a world |
| Story classes that exist | 7 of 8 (person rights has none). Built-or-planned reads the catalogue and boots nothing |
| Story classes on the one world | 8 of 8 |

| Story | Story class | Classes feeding it | Already a leg | To fit | Assert in passing | PLANNED | State |
|---|---|---|---|---|---|---|---|
| [TENANT-OPENING](../../arc42-003-context/user-stories/us-dbo-tenant-opening.md) | `ATenantOpensAndItsPeopleGetInIT` (on the world) | 15 | 17 | 25 | 6 | 2 | moved |
| [CLINICAL-RECORD](../../arc42-003-context/user-stories/us-dbo-clinical-record.md) | `TheClinicRecordsCareAndAccountsForItIT` (on the world) | 10 | 28 | 11 | 17 | 3 | moved |
| [PERSON-RIGHTS](../../arc42-003-context/user-stories/us-dbo-person-rights.md) | `WhatAPersonCanAskForIT` (on the world, two legs) | 9 | 3 | 22 | 12 | 0 | started |
| [TWO-PLACES](../../arc42-003-context/user-stories/us-dbo-two-places.md) | `OneTenantInTwoPlacesIT` (on the world, zone half) | 7 | 9 | 16 | 4 | 1 | moved |
| [STANDARD-MOVES](../../arc42-003-context/user-stories/us-dbo-standard-moves.md) | `TheStandardMovesUnderTheDataIT` (on the world) | 19 | 7 | 29 | 9 | 0 | moved |
| [VENDOR-CHANGE](../../arc42-003-context/user-stories/us-dbo-vendor-change.md) | `TheClinicChangesVendorIT` (on the world) | 1 | 6 | 0 | 7 | 0 | moved |
| [EDGE-ROUNDTRIP](../../arc42-003-context/user-stories/us-dbo-edge-roundtrip.md) | `WorkLeavesTheClinicAndComesBackIT` (on the world) | 14 | 23 | 16 | 19 | 4 | moved |
| [FLEET-HEALTH](../../arc42-003-context/user-stories/us-dbo-fleet-health.md) | `AnOperatorReadsAndSteersTheFleetIT` (on the world, one node) | 9 | 14 | 16 | 3 | 7 | moved but for two nodes |
| Fleet steps (new story, step 5) | none | 11 | 0 | 17 | 0 | 0 | todo |

The columns:

- **Already a leg:** the story class proves the promise now.
- **To fit:** proven only in a world, by some other class. A leg has to take
  it before that class can go.
- **Assert in passing:** proven without a world too. That proof stays, and the
  story asserts the promise where a leg passes through it, per rule 7.

Another 33 classes do not feed a story:

- 14 cite no promise.
- 11 may not fit one world at all. They are left to the end (see
  decisions).
- 8 are assembly and sample tests. The three sample tests fold into the
  stories, which become the sample application's tests. The five assembly
  tests stay, because they prove the wrapper
  ([item 028](../028-the-embedded-host-proves-itself/README.md)).

## Steps

1. ~~**The story base, in the sample application's tests.**~~ Built. Add one
   meta-annotation for story classes. It carries `@DboSpringBootTest`, one
   profile, `PER_CLASS`, method ordering, class-level concurrency and the
   integration tag, so no story class can differ in configuration and get a
   second context. Classes run concurrently and each class's methods run on
   one thread. Add the naming helper from
   [keeping stories apart](#keeping-stories-apart) at the same time. Two first
   classes, run together, assert that the world serves and that neither can
   see what the other wrote. The run shows one context and one bring-up of the
   seven tenants, brought up along their dependencies as the decision on
   bring-up describes.

   What was built: `AUserStory` (the meta-annotation), `StoryNames`,
   `TheWholeWorldServes` (holds every story until the world serves), and the
   `stories` profile. The first legs are clinical record at St Jerome and
   person rights at Hogwarts. A run is one context and one bring-up, and takes
   four minutes.

   **What it found**, both fixed in the same change:

   - **The Spring servlet adapter escaped a query twice.** It rebuilt the
     request URI from parts, which quotes every `%` again, so a client's
     `%7C` reached the surface as the text `%7C` and not as the `|` between
     a system and a value. On St Jerome, a Patient identifier search answered
     an empty bundle for a match. On Hogwarts, a purpose-stated lookup by
     national number was refused as unmatchable, against
     `PDI_EXACT_RESOLUTION`. The server sample's own test had pinned that
     refusal as correct. The JDK server the harness uses never escaped twice,
     which is why nothing else saw it.
   - **Two callers creating one identity at once got a conflict.**
     `putIfAbsent` read, then wrote, and a caller that lost the race to the
     insert was told the identity was claimed instead of being given the
     record the winner made. Two stories ensuring the test client at once hit
     it on the first concurrent run. Two replicas ensuring one client would
     hit it the same way.

   The stories also needed one dial changed. `dboTestParallelism=1` in
   `gradle.properties` turns JUnit's parallelism off for every test task,
   because concurrent classes there each build a world. It no longer applies
   to `storyTest`: concurrent stories share one world, so running them
   together costs threads and not worlds.
2. **Move the story classes that already exist,** keeping their legs. Go in
   order of how little the world has to change: clinical record, edge
   roundtrip, standard moves, two places, vendor change, tenant opening, fleet
   health. Each class lands with its own legs green while running beside the
   classes already moved, its data renamed to the story's prefix, and nothing
   new is fitted yet.

   Done for all seven, and person rights has its first two legs. The harness classes
   for clinical record, edge roundtrip, standard moves, vendor change and
   tenant opening are deleted. The catalogue now reads the stories' citations
   from the worker sample's test classes, so no promise lost its proof in the
   move. What each story needed from the world:

   | Story | Where it walks |
   |---|---|
   | Clinical record | St Jerome, which now keys patients by its own record number (`urn:st-jerome:mrn`); its terminology comes from the zone |
   | Person rights | Hogwarts |
   | Edge roundtrip | Hogwarts, with a bench lane of the story's own |
   | Standard moves | A clinic it declares: every world member takes its profiles by replication, and this one authors them |
   | Vendor change | Two clinics it declares, because what leaves is a whole estate |
   | Tenant opening | Two clinics it declares, because opening one is the story |
   | Fleet health | Hogwarts and the node itself, through the deployment's ops token |
   | Two places | The zone, with a clinic it declares after the zone already holds content |

   **Left behind on purpose.** Fleet health's rolling-upgrade and network-map
   legs need two nodes, so the harness class stays for them; they go with the
   eleven left to the end. Tenant opening no longer asserts the provisioner's
   bootstrap secret, which no application reaches; the promise is still
   proven by `OperatorIT` and `ServerDistIT`.

   **What moving them found**, each fixed with a test that fails without it:

   - **The r5 face root reindexed its whole version after every bring-up.**
     It stores the version's own search parameters as records, and its first
     shapes round counted them as authored: 6,379 definitions rebuilt into
     the envelopes they already had, in 221 to 232 seconds, on the reconciler
     thread. For those four minutes no tenant in the deployment synced
     anything, which is why a code system the zone published took two
     minutes to reach the clinic. A parameter identical to one the version
     carries no longer counts as authored: 0 reindexed, in 24 ms.
   - **The servlet adapter escaped a query twice**, and **two callers
     creating one identity at once got a conflict**: both in the step 1
     commits.
   **Two places is split.** Its zone half walks the world. Its appliance half
   is one tenant in two places, and the world is one place, so it stays in
   the harness as `AnApplianceCarriesPatientDataByWorkIT`: two databases and a
   lane between them, with no runtime. Moving it onto the application would
   put the store's engine and the driver on the application's classpath,
   which is the two-class-space trouble item 033 is about.
3. **Fit the promises, one story at a time.** Work from the listing's
   `to fit` rows. For each promise, find the leg where the journey already
   does what it is about, use the source class's arrangement as that leg's
   preparation, and assert it. A promise with no such leg grows the story
   (rule 8). Fill in the listing's leg column as each is taken.
4. **Delete what the stories made redundant.** A source class goes when every
   promise it cites is proven in a story. Re-record the worlds ledger and the
   catalogue in the same change, and confirm the new citation is listed before
   the deletion, as the shared-world rule requires.
5. **The two stories without a class get one.**
   - **Person rights:** its legs are already proven across nine classes on
     the hospital, which is a world member, so it starts from fitting rather
     than moving.
   - **Fleet steps:** the story is written first. That means a page under
     `docs/arc42-003-context/user-stories/` and a `DboStories` constant
     declaring the seventeen promises, with the joins projected. Then comes
     its class. Its scene is the management tenant declaring a step and one
     bean in the worker sample performing it for the hospital and the
     clinic. Along the way it covers what each tenant admits, the register of
     what is opened, enrolment and posture.
6. **Read the 14 classes that cite no promise.** Each is a measurement whose
   finding is recorded somewhere, a fold into a leg under the promise it
   should have cited, or a deletion.
7. **Last, the 11 that may not fit.** Nothing is done with them until steps
   1 to 6 are finished. Then each one is read again against a world that has
   grown by then. What fits goes into a story. What really does not fit gets
   a technical user story: one or more, each a scene about a deployment other
   than the one under test, with its own world.
8. **Close.** When `core/harness` holds no class that boots a world outside
   a story or a technical story, delete
   `SharedTenants`, the worlds ledger and its test. Retire the CLAUDE.md
   allowance that keeps bring-up proofs off the shared runtime: tenant opening
   proves bring-up on the one world. Delete this item.

## Decisions

**One context, so one configuration.** Spring caches a context by its
configuration. A story class that differs in anything (a property, a profile,
a mocked bean) gets a second context. That context brings up its own world
over the same database, and Spring does not close it. The meta-annotation in
step 1 exists so that this cannot happen by accident.

**The stories are the sample application's integration tests.** DBO is a
component for building applications, so what it does shows up only through
an application built on it. The
[context chapter](../../arc42-003-context/README.md) gives the store a
system built on top of it for exactly that reason. The applications under
[`samples/`](https://github.com/jengu-net/dbo/tree/main/samples) are that
system: a serving application and a performing one, which the guide teaches
from. The user stories are scenes in that system. So the story classes live
in the samples' own tests, and they serve two purposes at once:

- They are DBO's integration tests.
- They are the worked example of how the sample application was built,
  which is what the guide shows a reader.

That shapes how they are written. A leg acts through the application the way
an integrator would: through its endpoints, its beans and `DboTestContext`'s
verbs, never through the store's internals. A class should read as a chapter
could quote it.

The suite needs both halves in one context. The worker application's tests
already boot it that way, so the stories live there, in
`samples/spring-boot-worker-app`, under the `story` tag. They run in a
`storyTest` task of their own, which `check` depends on, and the module's
`test` task excludes them. That gives the stories a JVM holding their one
context and nothing else: the module's other tests each build a context of
their own, and in the same JVM they would be a second world over the same
database. One context loads one `application.yaml`, so the serving half's
needs are stated in the `stories` profile, as the worker sample's tests
already explain.

**One world, for the guide and for the tests.** `samples/sample-world` is
the world the guide's chapters and the stories both read; `sample/` is
deprecated. So a change rule 1 makes to the world is a change to what the
guide shows a reader, and it has to make sense there: a world member is a
part a reader can recognise, never a fixture one story needed. A tenant only
one story wants is declared by that story under its prefix. Moving the guide
off `sample/` is [item 027](../027-the-guide-moves-onto-the-samples/README.md);
until it lands, the guide's container still mounts `sample/world`, and a
change made here reaches the guide when 027 does.

**The 11 that may not fit are left to the end.** These are:

- the distribution, started as a process
- the guide, run against its pinned image
- a k3s operator
- Felix packaging, and a bundle stack resolved in Felix
- the three OSGi ratchets
- a driver bundle, and telemetry leaving the container
- two `core/dbo-tenant` unit tests that build a runtime over stub
  provisioners. They may simply stay as unit tests once read

Each claims something about a deployment other than the one the stories run
on. They are not challenged now. They are read once everything else has
moved and the world has grown, because some may fit by then: the Spring world
boots the embedded container too. Those that still do not fit get one or more
technical user stories, written the same way as the others but about a
builder or operator handling the deployment itself. Until then, the classes
stay as they are, and so does what they prove. That includes
`TheGuideRunsIT`'s 40 promises that no other world proves. The stories still
prove those 40 themselves where a leg passes through them, so the guide is
never the only proof.

**The world comes up as fast as it can, once.** Bring-up is most of the
suite's time, so the goal is to make it cheap, not to measure it. This item
does the following:

- **Up before any story starts.** The context is ready only once every world
  member serves. No story waits on a member another story is still bringing
  up.
- **Up along its dependencies, not in a line.** The scan already does this.
  It brings every declared tenant up at once, and a dependent whose upstream
  is not serving yet is retried on the next pass. The world serves about two
  and a half minutes after the context starts: the face roots together in
  about a minute, then the zone, its r4 projection, the hospital and the
  insurer, then the clinic.
- **Each cost paid once per JVM.** The terminology baseline and a face's
  definitions are shared wherever the store allows it. Where a second tenant
  on the same face pays again for something the first already loaded, that
  is a defect to fix, not a cost to accept. Items 024 and 025 hold what is
  already known about it.
- **Story tenants are few, small, and started early.** A story declares a
  tenant only when no world member can play the part. It declares only the
  types its legs use, and declares it at the start of the class. The bring-up
  then overlaps the legs the story walks on world members, and the leg that
  needs the tenant waits for it there.

**Lifecycle legs are declared, not built.** `DboTestContext.declare` and
`retract` bring a tenant up and take it away on the running deployment, and
nothing is written to disk. That is what a tenant opening is, so the lifecycle
reason in the worlds ledger stops needing a runtime of its own. The cost is a
database for the class's lifetime, which rule 4 accounts for.

**A whole-plane claim holds on a shared world.** "Nothing readable lands in
the substrate" is stronger when every story wrote to that substrate, not
weaker. Item 003 kept two classes off the shared runtime for asserting about a
whole plane, and that reason is withdrawn here.

**A sweep claim is a claim about the pass's answer for this leg's tenant.**
While the stories run, other stories' tenants come and go in every pass. So
the leg asserts what a pass returned for the tenant it made: that it is
included, or that it is left out. It never asserts the pass's total.

**Every story runs at once, with no limit.** That is the most realistic
load the suite can produce. It is also the highest peak of heap and of
bring-ups. A cap is set only if the JVM cannot carry the peak, and then it is
the smallest cap that it can carry.

**The fleet steps are a story of their own.** Seventeen `PROC_*` promises
are declared by no story. They are the
[item 032](../032-one-lane-for-the-fleet/README.md) family: a step the
deployment performs for every tenant, with its register, enrolment and
posture. They form one scene with one subject, the deployment acting for many
tenants. Edge roundtrip's subject is work leaving one tenant, and bolting
these onto it would blur both.

**No story runs first.** The classes run at once, so nothing may depend on
their order, and only the deployment saw the world untouched. A first-boot
claim is asserted from what the deployment recorded at its bring-up, and it
holds whichever class asks.

## Open decisions

None at present.

## Traps

- **What a tenant costs is seconds, not memory.** Definitions are shared per
  face and process. A tenant costs 3 to 11 MB, and a world's tenants are held
  to the end of the run (item 003). The managing tenant takes about two and a
  half minutes to bring up and each further tenant 25 to 60 seconds, almost
  all of it the terminology baseline.
- **One JVM carries every face's definitions at once.** The heap ceiling and
  what fills it are in
  [item 023](../023-the-suite-runs-out-of-heap/README.md). The R5 validator
  needs 2g, and a suite that also loads r4 and r6 needs more.
- **Emptiness cannot be shared.** Two places proves that a clinic takes one of
  a zone's two types by asserting that the other type is empty there. While
  stories run at once, that holds only for a type no story writes into that
  tenant. The world has to keep that true on purpose, or the leg asserts on
  the story's own records instead.
- **Two lane carriers today mean two contexts.** The worker sample has an
  `http` profile and a `substrate` profile, and each one is a context of its
  own. Edge roundtrip proves the two carriers indistinguishable, so it needs
  both in the one context: a worker holding one lane over each, toward two
  different world members.
- **A tenant declared mid-run waits for the pass in progress.** `scanOnce`
  is synchronized, and a pass returns only when everything it brought up is
  up. A story that declares a tenant while another story's tenant is coming
  up waits for that bring-up to finish before its own starts.
- **A wait that drives the deployment slows every story.** A poll that runs
  `syncRound` or `scanOnce` in a loop pays for the whole world on each pass.
  With every story in one JVM, that cost lands on all of them. Drive the
  story's own tenant (shared-world rule).
- **Authorship and replication are one type declared two ways.** A tenant
  either authors canonical content or receives it by replication. Classes that
  author profiles, code systems or value sets need a world member declared as
  the author, not one that only receives.
- **A face is cut once and cached.** A profile written to a face root after
  the face was cut does not reach a tenant on that face by the path being
  tested (item 003's refused move).

## Not being done

- **Porting classes.** A class is never translated into a story method. Its
  promise moves; its code is read for what it arranges.
- **Proving a promise twice in the stories.** A promise is proven at the first
  leg that passes through it.
- **Keeping the shapes.** `SharedTenants.Shape` goes with the classes that use
  it, and nothing new is added to it.

## How it is proven

```bash
./gradlew :<story-module>:integrationTest   # the stories; one context, one world
./gradlew :core:harness:storyCoverage       # where each story's legs are proven
./gradlew :core:harness:worldsLedger        # falls as source classes go
```

The item is finished when three things are true:

- `storyCoverage` names a story class for every proven leg.
- The worlds ledger is gone.
- The suite's wall time is measured beside today's figure of over an hour.
