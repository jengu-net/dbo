# DBO requirement catalogue

> The whole catalogue, between the `promise:begin`/`promise:end` markers
> below, is **generated** from the promise catalogue (`core/dbo-promises`)
> — edit the enums and run `./gradlew :core:harness:promiseProjection`,
> never the tables ([promise](../arc42-008-crosscutting/promise.md)). Every
> promise carries a derived status: PROVEN where a test cites it, ASSURED
> where review does, GAP where nobody has stated it yet, and PLANNED where
> it is declared but nothing proves it — most of the 2026-08-27 migration
> reads PLANNED, each with a `TODO: prove it in a test` on its constant,
> because porting the prose and wiring the citations are deliberately two
> passes.

The founding requirements ([founding-requirements.md](../arc42-001-introduction/founding-requirements.md)) and concepts
(the §-numbered sections, see [the docs index](../README.md)) distilled into stable REQ-style IDs, grouped by
capability area. Each REQ is a business-readable promise; the parenthesized
source at its end traces it back. Tier-2/3 search features and
migration-program exit criteria
deliberately have no REQs yet — they get them when scheduled.
<!-- promise:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

## SHAPE — shape versioning

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-SHAPE-WRITTEN-UNDER-STAMPED | Every object accepted through the face carries, as a fact of the accept event stored beside the payload, the version of each declared pack profile it was validated against; re-accepting replaces the stamp, never accumulates it. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#acceptStamps<br>cloud.jengu.dbo.harness.ShapeStampIT#packBumpMovesTheStamp<br>cloud.jengu.dbo.harness.ShapeStampIT#undeclaredIsUnstamped |
| REQ-DBO-SHAPE-STAMP-IS-DERIVED | The shape stamp is a per-version fact column in state and history; the envelope's shape dimension is rebuilt from it on reindex, and every history version serves its own stamp. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#packBumpMovesTheStamp<br>cloud.jengu.dbo.harness.ShapeStampIT#reindexKeepsTheStamp |
| REQ-DBO-SHAPE-SERVED-BESIDE-THE-CLAIM | The stamp is served in meta as the published urn:dbo:shape extension beside the unversioned meta.profile canonical; an echoed copy of the engine's own stamp is dropped at accept, so stored bytes stay the author's claims. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#acceptStamps<br>cloud.jengu.dbo.harness.ShapeStampIT#echoIsStampStable |
| REQ-DBO-SHAPE-MIRRORED-KEEPS-ITS-STAMP | The stamp travels the sync wire beside the storage-format version, so a mirrored copy keeps the stamp of the store that validated it; only an authored accept restamps. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#stampRidesTheWire |
| REQ-DBO-SHAPE-QUERYABLE-BY-VERSION | Objects are searchable by shape-stamp bound — below, or at and above, a stated major for a stated profile — pageable like any search, on every serving surface. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#versionBoundsPartition |
| REQ-DBO-SHAPE-STOCK-COUNTED | The tenant inventory counts shape stock per type, profile and stamped version — including objects that declare a profile and carry no stamp at all — so the same report runs before and after a migration and diffs line by line. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#inventoryCountsTheStock |
| REQ-DBO-SHAPE-UNPARSEABLE-VERSION-REFUSED | A pack shape whose version has no parseable leading integer major is refused at accept, by name — it would stamp objects no version bound can ever match. | PROVEN | cloud.jengu.dbo.harness.ShapeStampIT#unparseableVersionRefused |
| REQ-DBO-SHAPE-RESHAPED-IN-PLACE | The store converts stamped objects to a target major in place: each rewrite is an ordinary versioned write, so history keeps the pre-conversion object with its own stamp and the new version carries the new one. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#convertsInPlace |
| REQ-DBO-SHAPE-RESHAPE-RESUMABLE | A reshape is paged and rate-bounded, hands back a cursor and its counts, and a re-run finds only what is still behind. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#reRunConvertsNothing |
| REQ-DBO-SHAPE-REFUSED-OBJECT-LEFT-BEHIND | An object no converter covers is named and left behind rather than stranding the rest; the run reports it and the next run tries again. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#uncoveredIsNamedAndLeftBehind |
| REQ-DBO-SHAPE-STAMP-OUTLIVES-ITS-PACK | A stamp is a fact about a past accept: withdrawing or re-numbering a pack version leaves stock stamped with it findable, countable and convertible. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#stampOutlivesItsPack |
| REQ-DBO-SHAPE-NEWER-DATA-REFUSED | An object stamped above what the tenant's pack declares for that shape is refused on every read — naming the object, the stamp and the pack's version — never served best-effort and never silently omitted from a search. | PROVEN | cloud.jengu.dbo.harness.NewerDataRefusedIT#onlyDemonstrablyAheadIsRefused<br>cloud.jengu.dbo.harness.NewerDataRefusedIT#refusedById<br>cloud.jengu.dbo.harness.NewerDataRefusedIT#searchRefusedRatherThanShortened |
| REQ-DBO-SHAPE-TOO-NEW-IS-ITS-OWN-ANSWER | The refusal is a distinct, documented error a consumer can gate on, told apart from a fault, a permission and a malformed request. | PROVEN | cloud.jengu.dbo.harness.NewerDataRefusedIT#refusedById |
| REQ-DBO-SHAPE-HANDBACK-CLAIMS-WITHOUT-LOCKING | Claiming stock for conversion elsewhere writes nothing and holds nothing: the version check on the way back is the only guard, so an abandoned claim strands no data and a duplicated one converges. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#claimHoldsNothing |
| REQ-DBO-SHAPE-HANDBACK-KEEPS-THE-DISCIPLINE | Converted forms handed back are re-accepted through the face — validated, re-stamped, version-checked — and accounted exactly as the in-process lane accounts, so the hardest conversions do not run with the least discipline. | PROVEN | cloud.jengu.dbo.harness.ReshapeIT#handBackKeepsTheDiscipline |

## PDI — personal-data isolation

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-PDI-STRUCTURAL-VAULT | Identifying elements, declared per type/element, live encrypted in the tenant's person vault; the main store holds pseudonymous records and the engine reassembles full resources for authorized reads — isolation is beneath the API, not a caller discipline. | PROVEN | cloud.jengu.dbo.harness.PdiIT#identifyingValuesAreCiphertextEverywhere<br>cloud.jengu.dbo.harness.PdiIT#theCoarseValueIsWrittenInTheClear |
| REQ-DBO-PDI-CRYPTO-SHREDDING | Erasure destroys the person's key: history stays byte-immutable, existing archives stay valid as files, and the person's data is cryptographically gone from live store, history, envelopes and archives at once. | PROVEN | cloud.jengu.dbo.harness.PdiIT#shredErasesEverywhereAndRestoreCannotResurrect |
| REQ-DBO-PDI-UNFINDABLE-AFTER-ERASURE | Search indexes derived from personal elements are vault-scoped or rebuilt on shred — an erased person is unfindable, not merely unreadable. | PROVEN | cloud.jengu.dbo.harness.PdiIT#aShreddedPersonIsNotResolvableByIdentifier<br>cloud.jengu.dbo.harness.PdiIT#shredErasesEverywhereAndRestoreCannotResurrect |
| REQ-DBO-PDI-BLIND-OPERATIONS | Backup and restore are machinery-driven end to end over ciphertext; the operator can run the whole lifecycle without the ability to read personal data, and opening an archive outside the running system is an owner-only act. | PROVEN | cloud.jengu.dbo.harness.PdiIT#aTenantArchiveCarriesCiphertextWhateverTheRequestWasDoing |
| REQ-DBO-PDI-SHRED-LEDGER | Erasures are recorded without personal data and re-applied on every restore before serving resumes — an old archive cannot silently resurrect an erased person. | PROVEN | cloud.jengu.dbo.harness.PdiIT#shredErasesEverywhereAndRestoreCannotResurrect |
| REQ-DBO-PDI-RIGHTS-AS-OPERATIONS | Access, portability and restriction are standard machinery operations over the vault join, not per-request projects. | PROVEN | cloud.jengu.dbo.harness.PdiIT#restrictionMakesReadsPseudonymous<br>cloud.jengu.dbo.harness.PdiIT#theSubjectsOwnExportStatesItsPurpose |
| REQ-DBO-PDI-EXACT-RESOLUTION | An exact, purpose-stated lookup on a vault-indexed value — a claimed identifier (system|value) or an indexed contact point — resolves through the vault to the records holding it, served under the caller's disclosure mode. The match runs over keyed hashes and every resolution leaves a value fingerprint in the disclosure trail; after erasure the answer is empty. Anything inexact, unsystemed, or combined with other predicates is refused, never half-answered. | PROVEN | cloud.jengu.dbo.harness.DisclosureModesIT#aBareIdentifierValueDoesNotResolve<br>cloud.jengu.dbo.harness.DisclosureModesIT#aValueClaimedByAPatientAnswersNothingForAPractitioner<br>cloud.jengu.dbo.harness.DisclosureModesIT#anExactIdentifierLookupResolvesTheClaimingRecord<br>cloud.jengu.dbo.harness.DisclosureModesIT#anIdentifierCombinedWithAnotherPredicateIsRefused<br>cloud.jengu.dbo.harness.DisclosureModesIT#anIdentifierLookupWithoutAPurposeIsRefused<br>cloud.jengu.dbo.harness.DisclosureModesIT#anUnclaimedIdentifierAnswersNobody<br>cloud.jengu.dbo.harness.PdiIT#aShreddedPersonIsNotResolvableByIdentifier |

## PROC — distributed work

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-PROC-STEP-DECLARES-ITS-SLOTS | A step declaration names its input slots — ordered, named, each an opaque shape reference — and a runner that joins the step has agreed to that API: there is nothing else it can receive. | PROVEN | cloud.jengu.dbo.harness.RunNamesItsInputsIT#aRunFillsTheDeclaredSlots |
| REQ-DBO-PROC-RUN-INPUTS-FILL-THE-SLOTS | A run's inputs fill the step's declared slots, fixed at creation: a slot the step does not declare and a declared slot left unfilled are both refused by name, and the record round-trips them in order. | PROVEN | cloud.jengu.dbo.harness.RunNamesItsInputsIT#aRunFillsTheDeclaredSlots |
| REQ-DBO-PROC-TASK-CARRIES-THE-INPUTS | The face renders each input as Task.input — the slot name as the parameter's code, the reference displayed rather than resolved, exactly as focus is — in every version the face serves. | PROVEN | cloud.jengu.dbo.harness.RunNamesItsInputsIT#theTaskCarriesTheInputs |
| REQ-DBO-PROC-INPUTS-ARRIVE-WITH-THE-WORK | A claimed run's inputs arrive with the work, resolved by the party that holds the objects; the runner's only read takes the run, a run the asking identity has not claimed is refused, and a run without slots delivers exactly nothing. | PROVEN | cloud.jengu.dbo.harness.RunNamesItsInputsIT#inputsArriveWithTheWork |
| REQ-DBO-PROC-MILESTONES-ARE-DECLARED | A step declares its milestones, in order; where declared, a name outside them is refused naming both sides, and a step that has not declared any is not narrowed. | PROVEN | cloud.jengu.dbo.harness.MilestonesOnTheCheckpointIT#emptyMeansHasNotSaid<br>cloud.jengu.dbo.harness.MilestonesOnTheCheckpointIT#positionIsDerivedAndTheNameIsHeldToTheDeclaration |
| REQ-DBO-PROC-PROGRESS-NAMES-THE-MILESTONE | A checkpoint can carry the milestone reached; the run records it replaced-never-accumulated, with its position over the declared order derived by the store rather than asserted by the executor, and it survives release and retake. A service that reports nothing behaves exactly as today. | PROVEN | cloud.jengu.dbo.harness.MilestonesOnTheCheckpointIT#emptyMeansHasNotSaid<br>cloud.jengu.dbo.harness.MilestonesOnTheCheckpointIT#positionIsDerivedAndTheNameIsHeldToTheDeclaration |
| REQ-DBO-PROC-TASK-SAYS-WHERE-THE-WORK-IS | The rendered Task's businessStatus says where the work is — the holder, and when a milestone is recorded the step's own word for it with its derived position — in every version the face serves. | PROVEN | cloud.jengu.dbo.harness.MilestonesOnTheCheckpointIT#theTaskSaysWhereTheWorkIs |
| REQ-DBO-PROC-STEPS-ARRIVE-BY-INTRODUCTION | A linked participant introduces the step declarations it brings beside its own candidacy; the catalogue records them with the introducer's name, and every consumer of the catalogue — validation, actions, milestones, the mandatory-steps classification — sees them the moment presence does. | PROVEN | cloud.jengu.dbo.harness.MandatoryStepsClassifyIncidentsIT#aMandatoryStepCanArriveByIntroduction<br>cloud.jengu.dbo.harness.StepsArriveByIntroductionIT#theRunnerIntroducesWhatItsServiceBrings |
| REQ-DBO-PROC-ONE-ID-ONE-DEFINITION | Two DEFINITIONS of one step id is a collision refused by name, never an override — a conflicting introduction, or a module installed beside one. Scaling stays possible by construction: parallel runners introducing an identical declaration co-introduce without refusal or thrown races (DBOS runs parallel consumers, and a fleet is not a conflict), and re-introduction by the same participant replaces. | PROVEN | cloud.jengu.dbo.harness.StepsArriveByIntroductionIT#oneIdOneDefinition |
| REQ-DBO-PROC-INTRODUCTION-GRANTS-NOTHING | A step introduced over the link grants its introducer nothing: the declaration binds the introducer exactly as it binds anybody, and what it may take stays the intersection of its scopes and what the step admits. | PROVEN | cloud.jengu.dbo.harness.StepsArriveByIntroductionIT#introductionGrantsNothing |
| REQ-DBO-PROC-CLAIM-IS-THE-INTERSECTION | What a participant may claim is the intersection of what its credential covers and what the step admits: the lane narrows the work it offers and refuses a claim outside the entitlement, and the store refuses an executor at a scope the step never opened itself to. A step cannot grant its executor more than the executor already holds. | PROVEN | cloud.jengu.dbo.harness.ClaimIsTheIntersectionIT#theCredentialsHalf<br>cloud.jengu.dbo.harness.ClaimIsTheIntersectionIT#theStepsHalf |
| REQ-DBO-PROC-ENTITLEMENT-IS-DECLARED-NOT-DEFAULTED | A lane's entitlement is stated when the lane is provisioned — everything, because the host is the tenant, or the steps a credential covers. There is no implicit unrestricted, so the reach of a remote participant never depends on a parameter somebody forgot. | PROVEN | cloud.jengu.dbo.harness.ClaimIsTheIntersectionIT#everythingNarrowsNothing<br>cloud.jengu.dbo.harness.ClaimIsTheIntersectionIT#theBareNameTrapIsHandled<br>cloud.jengu.dbo.harness.ClaimIsTheIntersectionIT#theCredentialsHalf |
| REQ-DBO-PROC-STEP-SERVICE-EMBEDDABLE | One embeddable runner registers step services and needs only the participation lane — no orchestrator, no transport, no access to the tenant's dbo — so the same bundle runs inside the platform's container, on a separate machine, or in a pod scaled per step, stateless over the tenants whose lanes it is handed. | PROVEN | cloud.jengu.dbo.harness.StepRunnerIT#registeredServiceConsumes<br>cloud.jengu.dbo.harness.StepRunnerIT#statelessOverTenants |
| REQ-DBO-PROC-FAILURE-IS-RELEASED | A failing or throwing step service releases the run with the reason — never closed, never lost — and a later cycle may take it again. | PROVEN | cloud.jengu.dbo.harness.StepRunnerIT#failureIsReleasedThenRetaken |
| REQ-DBO-PROC-RUNNER-SIGNS-ITS-VITALS | The runner re-declares each service with an extensible metadata block, replaced never accumulated; presence stays derived from the cursor, and vitals annotate it. | PROVEN | cloud.jengu.dbo.harness.StepRunnerIT#registeredServiceConsumes |
| REQ-DBO-PROC-CATALOGUE-IN-STORE | Process and step definitions (with profiles, planes and projections) are part of DBO's own vocabulary; projections are generated, never hand-edited. | PLANNED |  |
| REQ-DBO-PROC-STEP-DECLARES-ITSELF | A step declares its id, version, the storage domains it reads and writes, opaque shape references for what it consumes and produces, the actions it contains, and whether it may be overridden. Ids are <module>.<process>.<step>, globally stable, contributed by being installed, and a step referenced but not installed is refused by name. | PROVEN | cloud.jengu.dbo.harness.StepsAreDeclaredIT#anUndeclaredStepIsRefusedByName<br>cloud.jengu.dbo.harness.StepsAreDeclaredIT#idsAreGloballyStable |
| REQ-DBO-PROC-RUN-NAMES-THE-STEP-VERSION | A run records the version of the step declaration it ran under, beside the executor's version and provider: reproducing a decision needs the definition as well as the runner. | PROVEN | cloud.jengu.dbo.harness.StepsAreDeclaredIT#aRunNamesTheStepVersion |
| REQ-DBO-PROC-MANDATORY-STEPS-CLASSIFY-INCIDENTS | A tenant's spec declares the steps its work cannot do without. The tenant serves and its runs queue regardless — the system is asynchronous by design — and what the list decides is classification: a mandatory step nothing has contributed is an incident, named and cleared as contributions come and go, while every undeclared step's absence is no incident at all. | PROVEN | cloud.jengu.dbo.harness.MandatoryStepsClassifyIncidentsIT#aMandatoryStepCanArriveByIntroduction<br>cloud.jengu.dbo.harness.MandatoryStepsClassifyIncidentsIT#aMissingMandatoryStepIsAnIncidentByName<br>cloud.jengu.dbo.harness.MandatoryStepsClassifyIncidentsIT#aTenantServesEvenWhenAMandatoryStepIsMissing<br>cloud.jengu.dbo.harness.MandatoryStepsClassifyIncidentsIT#theIncidentClearsWhenTheStepArrives |
| REQ-DBO-PROC-REPORT-THROUGH-DECLARED-ACTIONS | A report lands through the actions the step declares: closing needs close, reopening needs reopen, and a verb the step does not declare is refused naming both sides. A step that has not declared actions is not narrowed, and releasing is never narrowed — failure honesty must not be refusable. | PROVEN | cloud.jengu.dbo.harness.ReportsGoThroughDeclaredActionsIT#aClosedRunReopensThroughTheDeclaredAction<br>cloud.jengu.dbo.harness.ReportsGoThroughDeclaredActionsIT#aStepWithoutCloseCannotBeClosed<br>cloud.jengu.dbo.harness.ReportsGoThroughDeclaredActionsIT#aStepWithoutReopenKeepsItsClosesFinal<br>cloud.jengu.dbo.harness.ReportsGoThroughDeclaredActionsIT#anUndeclaredStepIsNotNarrowed |
| REQ-DBO-PROC-CLOSED-CAN-BE-REOPENED | A closed run can be reopened — a deliberate, recorded act through the step's declared reopen action — making the run claimable again with the reason on the record, instead of a second run invented to disagree with the first. | PROVEN | cloud.jengu.dbo.harness.ReportsGoThroughDeclaredActionsIT#aClosedRunReopensThroughTheDeclaredAction |
| REQ-DBO-PROC-STEP-SHAPE-VALIDATION | A payload is validated against the shape a step declares through the face's existing payload capability, and a shape the face cannot resolve is an issue rather than a pass. | PROVEN | cloud.jengu.dbo.harness.StepsAreDeclaredIT#aPayloadIsHeldToTheStepsShape |
| REQ-DBO-PROC-DOMAIN-CODE-FILTER | Every process and step carries a free-string process-domain code; views and projections filter by it. | PLANNED |  |
| REQ-DBO-PROC-RUN-HAS-A-RECORD | Every run of a step is a record in a tenant's own store — a registered type, so it is envelope-queryable, versioned, carried by the backup and dropped with the tenant. A run in a private table has none of those, and cannot be seen or acted on. | PLANNED |  |
| REQ-DBO-PROC-RUN-SAYS-WHO-HOLDS-IT | A run's load-bearing field is who holds it now: automation running, automation with a retry scheduled, a person, or nobody. Every other field answers a question somebody asks after that one. | PLANNED |  |
| REQ-DBO-PROC-RUN-TALLY-AND-ITEM-OUTCOMES | A run over N items where K fail records one run with a tally and K item outcomes, and does not abandon the remaining N minus K. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#aRunOverItemsKeepsItsTally<br>cloud.jengu.dbo.harness.RunsAreRecordsIT#childrenAreExceptionsNotAnEnumeration |
| REQ-DBO-PROC-ESCALATION-BY-FAILURE-CLASS | A record that is wrong reaches a person; a store that is unavailable is a retry and nobody's card. Only record-class failures make work, or the queue becomes a graveyard and stops being read. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#escalationFollowsTheFailureClass |
| REQ-DBO-PROC-CLOSE-BY-RE-EVALUATION | Where a condition is machine-checkable, fixing the cause closes the run on the next pass; closing by hand exists only for conditions nothing can re-check. Closing by click is how a card reads resolved while the fault is live. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#aSweepClosesByReEvaluation |
| REQ-DBO-PROC-RUN-KINDS | A pipeline closes when every item is terminal; a sweep closes when the world agrees. A reconciler modelled as a pipeline never ends, and its needs-a-person queue fills with work that is merely still converging. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#aSweepIsOnePerScope |
| REQ-DBO-PROC-ONE-PARENT-NEVER-ACROSS-A-BOUNDARY | A run has at most one parent, and parenthood never crosses a domain or a system: items are children, subprocesses and continuations are references. A parent's close must mean something for its children, and cannot across a boundary this runtime does not control. | PLANNED |  |
| REQ-DBO-PROC-CORRELATION-TRAVELS-OPAQUE | A correlation carried from another system is echoed and never interpreted, so a cross-system join is queryable from either side without that system's vocabulary entering the engine. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#aCorrelationIsEchoed |
| REQ-DBO-PROC-RUN-ENVELOPE-DISCLOSES-STATE-NOT-SUBJECT | A run's envelope carries holder, step, state and counts — never item references or messages. The envelope is a disclosure surface, and progress must not name what was being processed. | PROVEN | cloud.jengu.dbo.harness.RunsAreRecordsIT#theEnvelopeDisclosesStateNotSubject |
| REQ-DBO-PROC-EXECUTOR-RESOLUTION-IS-DETERMINISTIC | Resolution walks the overlay chain — baseline, zone, organisation — and the most local willing and permitted candidate runs, one at a time in declared order. Racing candidates makes the same input behave differently under load and doubles effects nothing outside the store can undo. | PROVEN | cloud.jengu.dbo.work.ExecutorResolutionTest#aWithdrawnProviderStopsBeingSelected<br>cloud.jengu.dbo.work.ExecutorResolutionTest#anUnwillingCandidateIsPassedOver<br>cloud.jengu.dbo.work.ExecutorResolutionTest#candidatesAreTriedOneAtATime<br>cloud.jengu.dbo.work.ExecutorResolutionTest#theMostLocalPermittedCandidateRuns |
| REQ-DBO-PROC-A-STEP-GRANTS-THE-RIGHT-TO-OVERRIDE | Precedence selects; the step declares whether it may be overridden and by which scope class, and not overridable is the default. Specificity is self-declared, so precedence alone lets any party displace a national rule by narrowing its scope. | PROVEN | cloud.jengu.dbo.work.ExecutorResolutionTest#aGrantAdmitsWiderScopesThanTheOneItNames<br>cloud.jengu.dbo.work.ExecutorResolutionTest#aNarrowerScopeCannotShadowAStepThatForbidsIt |
| REQ-DBO-PROC-RUN-NAMES-WHAT-RAN-IT | A run records the executor, its version, its provider and the scope it was chosen at. A provider can be withdrawn and a scope re-declared, so a resolution nobody wrote down is a decision nobody can reproduce. | PROVEN | cloud.jengu.dbo.harness.ExecutorsDeclareThemselvesIT#localAndRemoteResolveByTheChain |
| REQ-DBO-PROC-AUTOMATION-IS-DECLARED | Whether a step is automated here is declared configuration on the same chain, as visible and as auditable as a terminology overlay — never a code path that happens to be unreachable. | PROVEN | cloud.jengu.dbo.work.ExecutorResolutionTest#aCandidateFromAnotherChainIsNotConsidered<br>cloud.jengu.dbo.work.ExecutorResolutionTest#theMostLocalSwitchWins |
| REQ-DBO-PROC-FALL-THROUGH-IS-COUNTABLE | Work no executor took is held by a person and counted per step and per zone. That number is the automation backlog stated as a fact rather than an opinion. | PROVEN | cloud.jengu.dbo.work.ExecutorResolutionTest#automationSwitchedOffFallsThrough |
| REQ-DBO-PROC-EXECUTOR-DECLARES-ITSELF | A participant announces process, step, scope, version and provider as a record in the tenant's store, and resolution walks those declarations rather than the bundles installed in one container. A candidate that can only come from a local bundle makes a tenant a single machine. | PROVEN | cloud.jengu.dbo.harness.ExecutorsDeclareThemselvesIT#aRemoteParticipantIsACandidate |
| REQ-DBO-PROC-PRESENCE-IS-DERIVED | A participant is present while its named feed cursor moves; a declaration whose consumer is behind and unmoving is declared-but-not-present, skipped by resolution and shown as such. No heartbeat and no lease — and a caught-up participant's cursor does not move either, so silence with nothing waiting is not absence. | PROVEN | cloud.jengu.dbo.harness.ExecutorsDeclareThemselvesIT#silenceWithWorkWaitingIsAbsence |
| REQ-DBO-PROC-LANE-APPLY-IS-REPLAY-AND-REORDER-SAFE | What a peer sends applies once however often it is sent, and a batch arriving behind a newer one does not put the older version back. The comparison is the source version, so neither property depends on the transport being careful. | PROVEN | cloud.jengu.dbo.harness.TwoAppliancesOneTenantIT#replayAndReorderAreSafe |
| REQ-DBO-PROC-LANE-EPOCH | A lane carries an epoch, and a peer resuming a cursor issued by another lane instance is refused rather than replayed — an appliance restored from a copy looks healthy while resuming a position that no longer means anything. | PROVEN | cloud.jengu.dbo.harness.TwoAppliancesOneTenantIT#aRestoredPeerIsRefused |
| REQ-DBO-PROC-WORK-DRIVEN-ARRIVAL-AND-EXPIRY | A record travels to an appliance because a piece of work names it, and is removed when no open run there still names it. Work-driven arrival without work-driven expiry is a bench accumulating a register one task at a time. | PROVEN | cloud.jengu.dbo.harness.TwoAppliancesOneTenantIT#closingTheTaskRemovesWhatCameWithIt<br>cloud.jengu.dbo.harness.TwoAppliancesOneTenantIT#workTravelsWithWhatItNames |
| REQ-DBO-PROC-MIRRORED-RUNS-ARE-FILED-BY-APPLIANCE | A run arriving from another appliance of the same tenant is stored under that appliance, beside the local run of the same key rather than on top of it. | PROVEN | cloud.jengu.dbo.harness.TwoAppliancesOneTenantIT#aMirroredRunDoesNotReplaceTheLocalOne |
| REQ-DBO-PROC-CONTENT-CHANGES-INSIDE-WORK | A type may declare that every change to it belongs to a run; a write with no run in scope is refused, naming the rule. A change that belongs to nothing is visible and unexplainable — history has it and audit names who, and nobody can say what it was for. | PROVEN | cloud.jengu.dbo.harness.ContentChangesInsideWorkIT#aBulkPathIsARun<br>cloud.jengu.dbo.harness.ContentChangesInsideWorkIT#aChangeOutsideWorkIsRefused |
| REQ-DBO-PROC-A-RUN-NAMES-WHAT-IT-PRODUCED | A run records the versions it produced, individually up to a cap and as a per-type high-water mark past it, and says which of the two it is. Reading runs in order then reads the content changes in order, so another appliance asks for what it is missing rather than comparing two stores. | PROVEN | cloud.jengu.dbo.harness.ContentChangesInsideWorkIT#aLargeRunKeepsAWatermark<br>cloud.jengu.dbo.harness.ContentChangesInsideWorkIT#aRunNamesWhatItProduced |
| REQ-DBO-PROC-NETWORK-MAP | The network answers which processes are known and running, where and in which version — scanned from bundles and accumulated across nodes. | PLANNED |  |
| REQ-DBO-PROC-TRACE-JOIN | From any process instance, the steps and the exact resource diffs and audit records they produced are navigable. | PROVEN | cloud.jengu.dbo.harness.PolicyIT#retentionRemovesExpiredObjectsAndAuditsTheRemoval |

## CORE — object engine

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-CORE-PAYLOAD-IS-TRUTH | A stored object's payload is the single source of truth; every searchable projection is derived from it and can always be rebuilt. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#aWriteIsImmediatelyReadableAndEveryVersionIsKept<br>cloud.jengu.dbo.harness.UpgradeOnReadIT#storedBytesRemainR4 |
| REQ-DBO-CORE-DECLARED-TRUTH-FORM | Which representation is authoritative for a type (payload or normalized form) is declared by its personality, never implicit. | PROVEN | cloud.jengu.dbo.harness.TerminologyIT#shellIsConceptFreeAndReassemblyRestoresTheTree |
| REQ-DBO-CORE-REINDEX-IS-AN-OPERATION | Changing how objects are indexed is a background operation, never a data migration. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#reindexAddsASearchDimensionWithoutTouchingPayloads |
| REQ-DBO-CORE-EXTERNAL-IDENTIFIERS | Every object has one internal id and any number of `{system, value}` identifiers, rebuilt from the payload on each write and searchable together. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#objectsAreFoundByAnyOfTheirIdentifiers |
| REQ-DBO-CORE-REFERENCE-EDGES | References between objects are extracted as owned edges on write and power referential reads. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#objectsAreSelectableByTheObjectsTheyReference |
| REQ-DBO-CORE-VERSIONED-HISTORY | Every write appends an immutable version; version-aware reads and optimistic concurrency (ETag) are first-class. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#aWriteIsImmediatelyReadableAndEveryVersionIsKept |
| REQ-DBO-CORE-READ-YOUR-WRITES | A write returns only after its data and its change event are committed in one transaction. (D1) | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#aWriteIsImmediatelyReadableAndEveryVersionIsKept |
| REQ-DBO-CORE-UPGRADE-ON-READ | Old payload versions are upgraded lazily by registered converters; a schema-version transition never requires a big-bang rewrite. | PROVEN | cloud.jengu.dbo.harness.UpgradeOnReadIT#r4WrittenEncounterReadsAsR5<br>cloud.jengu.dbo.harness.UpgradeOnReadIT#reindexMakesR5SearchLiveOverR4Data |
| REQ-DBO-CORE-PARAMETERIZED-SQL | No value is ever concatenated into SQL text. (D2) | PROVEN | cloud.jengu.dbo.harness.SqlDisciplineTest#noClassInDboPostgresUsesRawStatements |
| REQ-DBO-CORE-SIBLING-MODELS | Non-FHIR object models ride the same engine as FHIR resources, not beside it. (R6) | PROVEN | cloud.jengu.dbo.harness.SiblingModelsRideTheSameEngineIT#bothFamiliesAnswerThroughTheSamePrimitives<br>cloud.jengu.dbo.harness.SiblingModelsRideTheSameEngineIT#oneSetOfRulesNotTwo |
| REQ-DBO-CORE-DECLARED-IDENTITY | Every type in every personality declares exactly one primary identity class — canonical url, designated identifiers, or internal — and the contract fails closed at registration without it. | PROVEN | cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#aSpecTypeWithoutAnIdentityIsRefused<br>cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#aTypoIsNotAnIdentityClass<br>cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#canonicalIdentityCarriesNoSecondIdentity<br>cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#identifierIdentityRequiresItsSystems<br>cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#internalIdentityCarriesNoSecondIdentity<br>cloud.jengu.dbo.harness.EveryTypeDeclaresItsIdentityTest#noIdentityClassIsRefused |
| REQ-DBO-CORE-IDENTITY-SURVIVES-CONVERSION | Conversion between FHIR versions or object shapes never changes identity; canonical urls and identity-bearing identifiers are preserved bit-exact and verified after every conversion. | PROVEN | cloud.jengu.dbo.harness.UpgradeOnReadIT#identitySurvivesConversion |
| REQ-DBO-CORE-NO-IMPLICIT-MERGE | Two objects claiming the same identity-bearing identifier are a conflict surfaced to the owner, never an implicit merge. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#aSecondClaimOnTheSameSerialSurfacesAConflictInsteadOfMerging |
| REQ-DBO-CORE-IDENTITY-KEYED-CONDITIONALS | Conditional writes are accepted only when keyed on the type's primary identity; a conditional write on any other criterion is rejected. | PROVEN | cloud.jengu.dbo.harness.CoreEngineIT#aConditionalWriteOnANonIdentitySystemIsRejected<br>cloud.jengu.dbo.harness.CoreEngineIT#conditionalCreateReturnsTheExistingObjectUntouched |
| REQ-DBO-CORE-CONDITIONAL-REFERENCES | A reference may be a question — `Type?identifier=system\|value` — and it is answered when the document is written: exactly one match becomes the concrete reference, none or several refuse the write naming the question. Inside a transaction, the entries' own claimed identities answer before the store: a reference to an identity exactly one entry claims resolves to that entry, wherever it sits in the document — a hierarchy authored as one document lands whole. The question may ask only by the identity its type is claimed under, so what a write means does not depend on what else happens to match today, and no unanswered question — one neither the document nor the store answers — is ever stored. | PROVEN | cloud.jengu.dbo.harness.ConditionalReferencesIT#aReferenceMatchingNothingIsRefusedRatherThanStoredBroken<br>cloud.jengu.dbo.harness.ConditionalReferencesIT#aReferenceMayAskByIdentityAndNotByGeneralSearch<br>cloud.jengu.dbo.harness.ConditionalReferencesIT#aWriterThatKnowsAnIdentifierNeedNotKnowAnId<br>cloud.jengu.dbo.harness.ConditionalReferencesIT#itWorksInsideABundleToo<br>cloud.jengu.dbo.harness.TransactionAnswersItsOwnReferencesIT#aHierarchyAuthoredAsOneDocumentLandsWhole<br>cloud.jengu.dbo.harness.TransactionAnswersItsOwnReferencesIT#aReferenceNeitherAnswersKeepsTheRefusal<br>cloud.jengu.dbo.harness.TransactionAnswersItsOwnReferencesIT#twoEntriesClaimingOneIdentityAreRefused |
| REQ-DBO-CORE-CONDITIONAL-UPSERT | A write may be addressed by identity rather than by id: `PUT [type]?identifier=…` or `?url=…` creates the resource when absent and replaces it when present, standalone and inside a bundle. Configuration that must match a source can therefore be expressed as itself, rather than as a create that silently does nothing when the record already exists. | PROVEN | cloud.jengu.dbo.harness.ConditionalUpdateIT#aCatalogueSyncsAsABatchWithNothingRejected<br>cloud.jengu.dbo.harness.ConditionalUpdateIT#absentItCreatesAndPresentItReplaces<br>cloud.jengu.dbo.harness.CoreEngineIT#canonicalUpsertCreatesThenUpdatesTheSameObject<br>cloud.jengu.dbo.harness.TransactionAnswersItsOwnReferencesIT#aClaimTheStoreAlreadyHoldsBecomesAnUpdateOntoIt<br>cloud.jengu.dbo.harness.TransactionAnswersItsOwnReferencesIT#reApplyingTheSameDocumentConverges |
| REQ-DBO-CORE-ATOMIC-TRANSACTION-BUNDLE | A transaction bundle lands whole or not at all: every entry validated before anything is written, all writes in one engine transaction with data, history and outbox together, and entries may reference each other by `urn:uuid` — resolved to the allocated ids, never stored dangling. What a transaction does not serve is refused by name with nothing applied. | PROVEN | cloud.jengu.dbo.harness.BundleIT#aTransactionLandsWholeOrNotAtAll<br>cloud.jengu.dbo.harness.BundleIT#entriesReferenceEachOtherByUrnAndTheStoredReferenceIsReal<br>cloud.jengu.dbo.harness.BundleIT#theConsumersOwnReproIsAnsweredNotErrored<br>cloud.jengu.dbo.harness.BundleIT#whatIsNotServedIsRefusedByNameNeverA500 |
| REQ-DBO-CORE-BATCH-ANSWERS-PER-ENTRY | A batch bundle applies each entry independently through the same path the standalone request takes, and answers one response entry per request entry, in order, each with its own status — a failing entry says nothing about its neighbours, and the statuses are the ones the standalone requests would have answered. | PROVEN | cloud.jengu.dbo.harness.BundleIT#aBatchAnswersPerEntryAndAFailureIsLocal<br>cloud.jengu.dbo.harness.ConditionalUpdateIT#aCatalogueSyncsAsABatchWithNothingRejected |

## CONT — container & embedding

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-CONT-FRAMEWORK-FREE-CORE | The core is plain Java; no Spring/Micronaut-class framework dependency anywhere in the engine. (R1, R2) | PLANNED |  |
| REQ-DBO-CONT-DYNAMIC-TENANT-SERVICES | Tenants arrive, move and leave as OSGi service-registry dynamics — never a process restart. (R2, §4) | PLANNED |  |
| REQ-DBO-CONT-EMBEDDED-IN-JVM | A host application can boot the full store inside its own JVM for dev/test; the only shared dependencies are Felix and the OSGi API. (R2) | PLANNED |  |
| REQ-DBO-CONT-PRIVATE-DEPENDENCIES | Heavy third-party stacks (DBOS, HAPI) are embedded as private packages and served through DBO-owned whiteboard interfaces; their types never cross bundle boundaries. | PLANNED |  |
| REQ-DBO-CONT-FAST-COLD-START | Store startup against an already-current schema is fast enough for embedded test use; schema setup detects currency instead of replaying changelogs. | PLANNED |  |

## TEN — tenancy & isolation

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-TEN-STRUCTURAL-SCOPING | No code path can read or write data without an explicit tenant context. (R3) | PLANNED |  |
| REQ-DBO-TEN-DEDICATED-DATABASE-TIER | A tenant can run on a dedicated database; this tier is the design anchor. (R5) | PLANNED |  |
| REQ-DBO-TEN-CREDENTIAL-BLIND-PROVISIONING | Tenant databases and buckets are provisioned by an external operator; credentials exist only as platform secrets and are never readable by tenant-manager code. (R5, §4) | PLANNED |  |
| REQ-DBO-TEN-REGISTRY-SCOPED-ACCESS | Application code obtains a tenant's data services from the service registry and can use them without ever seeing credentials. (R5, §4) | PLANNED |  |
| REQ-DBO-TEN-ERASURE-BY-DROP | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. | PLANNED |  |
| REQ-DBO-TEN-SHARED-TIER-ISOLATION | Tenants on the shared tier are isolated by tenant-keyed schemas and row-level security with the same API surface as the dedicated tier. | PLANNED |  |
| REQ-DBO-TEN-FAIRNESS-QUOTAS | Per-tenant quotas and rate limits are first-class configuration, enforced at the serving pod. | PLANNED |  |

## AUTH — tenant authority & surface protection

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-AUTH-TENANT-SCOPED-ISSUER | Every tenant is its own OIDC authority with its own issuer URL, discovery document, key set and token endpoint; relying parties trust exactly one tenant's authority, never the store's. A token from any other tenant fails signature verification before any claim is read. | PLANNED |  |
| REQ-DBO-AUTH-IDENTITY-AS-RECORDS | Client applications, grants and signing keys are regular records in the tenant's own store — versioned, provenance-stamped, visible to feeds, and carried by the maintenance export: restoring a tenant restores who may access it. | PLANNED |  |
| REQ-DBO-AUTH-PRIVATE-SURFACE | The raw store surface is never publicly routed; public interaction with dbo-held data goes through process-based surfaces. The authority exists so authorized services reach the private surface with tenant-rooted trust. | PLANNED |  |
| REQ-DBO-AUTH-DENY-BY-DEFAULT | A serving deployment without a working authority refuses to serve tenant endpoints; disabling auth is an explicit embedded/test flag, never a default. | PLANNED |  |
| REQ-DBO-AUTH-BEARER-LOCAL-VALIDATION | The serving surface accepts OAuth2 bearer JWTs validated locally against the tenant's own cached key set — no per-request dependency on any other service. | PLANNED |  |
| REQ-DBO-AUTH-CREDENTIAL-FACTORS-BY-KIND | A local credential holds factors named by kind (RFC 8176 `amr`), and what may be held is decided per kind: a password only where the tenant is the identity provider for that subject, a bench PIN alongside federation because it serves the case federation cannot. | PLANNED |  |
| REQ-DBO-AUTH-SELF-SERVICE-CHANGE | A signed-in subject can replace their own password by proving possession of the current one. No ticket, no second channel, and no other factor is touched. | PLANNED |  |
| REQ-DBO-AUTH-RECOVERY-IS-AN-OPERATOR-ACT | A subject who cannot sign in is recovered by provisioning or an operator write, never by a self-service ceremony: recovery needs a channel the authority does not have, and acquiring one would put delivery inside the trust root. | PLANNED |  |
| REQ-DBO-AUTH-DEACTIVATION-RETIRES-CREDENTIALS | Deactivating a subject retires its credentials — every factor, at once, and never by deletion: history and audit need the record, and a login that vanishes cannot be told from one that never existed. | PLANNED |  |
| REQ-DBO-AUTH-FIRST-SECRET-BY-ONE-TIME-GRANT | A subject sets their own first secret by redeeming a one-time, short-lived grant the authority mints and never delivers: the consumer owns the address and the mail, so no delivery enters the trust root. Minting resolves nothing, redemption burns the grant on presentation rather than on success, and a grant authenticates nothing and cannot be exchanged for a token. | PLANNED |  |
| REQ-DBO-AUTH-NO-SUBJECT-ENUMERATION | No authority answer distinguishes a subject that exists from one that does not — not in what it says, not in how long it takes. The authority is the only party that knows, which is why it must not say. | PLANNED |  |
| REQ-DBO-AUTH-SMART-SHAPED-SCOPES | Authorization vocabulary is the SMART system-scope grammar, so finer service permissions and the future read-only public capability need no new language. | PLANNED |  |
| REQ-DBO-AUTH-PORTABLE-AUTHORITY | The issuer string is per-tenant configuration and the key material lives in the tenant database — a tenant can move deployments or present a custom domain without re-keying. | PLANNED |  |
| REQ-DBO-AUTH-ORG-MODEL-IS-THE-AUTH-MODEL | Human authorization derives from the tenant's own records — Practitioner is the subject, an active PractitionerRole is the grant, the Organization tree is the scope structure; there is no parallel user database to drift. | PLANNED |  |
| REQ-DBO-AUTH-FEDERATED-HUMANS | Human authentication is federated to the configured identity broker; the authority resolves the verified national identifier to a Practitioner through the vault index and owns authorization only. Local credentials are an embedded/dev fallback, never the production path. | PLANNED |  |
| REQ-DBO-AUTH-ROLE-GRANTS-AS-RECORDS | The role-to-scope mapping is tenant-administered regular records — auditable, feed-visible, exported; changing who may do what is a recorded act. | PLANNED |  |
| REQ-DBO-AUTH-PSEUDONYMOUS-TOKENS | Human tokens carry the practitioner's record id and SMART user scopes — no name, no national code; a captured token identifies no one. | PLANNED |  |
| REQ-DBO-AUTH-ONE-CEREMONY-MANY-TENANTS | One national authentication serves every tenant authority in the deployment through the identity hub's session — the upstream broker is invoked once per session, not per tenant; authorization remains strictly per-tenant. | PLANNED |  |
| REQ-DBO-AUTH-ON-BEHALF-OF | Automated processes act in the name of a human via token exchange — subject stays the practitioner, an act claim names the client, scopes attenuate; durable workflows delegate through Delegation records that outlive tokens and are revocable by ending their period. Every delegated mutation is attributable to both the process and the person. | PLANNED |  |

## POL — tenant policies (audit & write discipline)

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-POL-DECLARED-AT-CONFIGURATION | Audit level and write discipline are declared in the tenant's configuration next to its FHIR version, validated at registration, and visible in the capability statement. | PLANNED |  |
| REQ-DBO-POL-AUDIT-AS-RECORDS | Audit entries are regular, pseudonymous records in the tenant's own store — feed-visible, exported and restored with the tenant, re-identifiable only through the vault. | PLANNED |  |
| REQ-DBO-POL-ACTOR-FROM-AUTHORITY | Every audit entry names its actor from the tenant authority's token (client and subject) — no anonymous mutations under any audited policy. | PLANNED |  |
| REQ-DBO-POL-APPEND-ONLY-DISCIPLINE | Under append-only discipline the engine rejects tombstones (and per-type in-place updates where declared); correction is supersession or entered-in-error, never removal. | PLANNED |  |
| REQ-DBO-POL-ERASURE-COMPATIBLE | Append-only discipline and the right to erasure coexist: shredding never rewrites a record — the record remains, the person evaporates. | PLANNED |  |
| REQ-DBO-POL-DECLARATIVE-RETENTION | Retention is declared per tenant and type as a floor and a ceiling — keepAtLeast (append-only holds even against policy) and removeAfter (the engine must remove) — composing with write discipline without conflict. | PLANNED |  |
| REQ-DBO-POL-RETENTION-SWEEP | A durable scheduled sweep executes removal as the one sanctioned mutation of history, and every removal is audited without retaining the removed data. | PLANNED |  |
| REQ-DBO-POL-POLICY-REPLAY-ON-RESTORE | Before a restored tenant serves, the machinery re-applies the shred ledger and the retention sweep — an archive cannot resurrect what policy required gone; archives carry removeAfter themselves. | PLANNED |  |
| REQ-DBO-POL-CUSTOM-AUDIT-EVENTS | Applications contribute business-level audit events; the machinery stamps actor and time from the validated token and its own clock, overriding caller claims — the trail can be enriched, never impersonated or backdated. | PLANNED |  |
| REQ-DBO-POL-AUDIT-UNCONDITIONALLY-APPEND-ONLY | Audit entries are exempt from the tenant's write discipline: no update, no tombstone under any policy; retention's sweep is the only removal. | PLANNED |  |
| REQ-DBO-POL-FHIR-AUDIT-PROJECTION | On a FHIR tenant the audit stream is served as AuditEvent — native records as the truth form, rendered per personality on read, contribution via mapped POST; write access is scope-gated. | PLANNED |  |

## ZONE — jurisdiction overlay

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-ZONE-DECLARATIONS-AS-RECORDS | A zone is a tenant whose declarations — identity brokers, identifier domains — are regular records: versioned, audited, exported, and streamable down the same chains as any content. Secrets are never in a record. | PLANNED |  |
| REQ-DBO-ZONE-BROKER-CHOICE | The broker set is jurisdictional, the choice organizational: the zone declares the available national brokers; a tenant selects its contracted one and may restrict what it accepts. | PLANNED |  |
| REQ-DBO-ZONE-SESSIONS-ACCUMULATE | The per-zone hub's session records which broker performed each ceremony and accumulates ceremonies; cross-broker reuse is the default, tenant acceptance policy the restriction — the strictest tenant is satisfied without invalidating anyone else's session. | PLANNED |  |
| REQ-DBO-ZONE-SUBJECT-DOMAINS | Subject-resolution identifier systems come from the zone's declared domains — the official national terminology — never from dbo code. | PLANNED |  |

## VER — version plurality (personalities)

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-VER-VERSION-AGNOSTIC-CORE | The engine has no knowledge of any FHIR version; all version meaning lives in personality bundles. (R6, §1) | PLANNED |  |
| REQ-DBO-VER-CONCURRENT-VERSIONS | Tenants (and domains within a tenant) on different FHIR versions run concurrently in one container. (R6) | PLANNED |  |
| REQ-DBO-VER-PERSONALITY-OWNS-MEANING | Parsing, validation, search-parameter extraction and subscription evaluation are personality responsibilities, per version. | PLANNED |  |
| REQ-DBO-VER-SPECIFIED-VALIDATION | Profile-resolution and validation semantics are specified by DBO — a malformed or versioned canonical reference can never silently disable validation. | PLANNED |  |
| REQ-DBO-VER-VALIDATION-WITHOUT-WRITING | A caller can ask whether a resource would be accepted without writing it (`[Type]/$validate`), and the answer is the write path's own: what it accepts a write accepts, what it rejects a write rejects. Issues carry the locations a refusal carries, so a caller is told what to fix. The verdict is the resource's shape — state a write settles (an identity already claimed, a version moved on) is not promised. | PLANNED |  |
| REQ-DBO-VER-ONE-READ-PER-REQUEST | Accepting a write reads its payload once, however many parts of the write ask about it — the type, the verdict and the searchable envelope come from one read. A payload rewritten on its way into the engine is read as it now stands, so what is indexed is what is stored. | PLANNED |  |
| REQ-DBO-VER-BALLOT-RECORDED-PER-VERSION | A stored version records the exact version it was authored under — a ballot by its full spelling, never the release it anticipates — so a later version has something to convert from and a reader is never told a guess. | PLANNED |  |
| REQ-DBO-VER-DEFINITIONS-TRAVEL-WITH-THE-FACE | A face brings the definitions it validates and extracts against. Bringing a tenant up fetches nothing over the network and needs no writable cache outside the store's own state. | PLANNED |  |
| REQ-DBO-VER-BALLOT-SERVED-AS-AUTHORED | A version still at ballot promises no normalized truth form and no conversion to or from another version: what an author wrote is what a reader receives. Normalising under a ballot's understanding would bake it into bytes that are never rewritten, and the next ballot moving an element would lose what it moved. | PLANNED |  |
| REQ-DBO-VER-TRANSITION-BY-CONVERTERS | Moving a tenant between FHIR versions is converters plus reindex, not a data migration ceremony. | PLANNED |  |

## SRCH — search

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-SRCH-TIER1-PARITY | Every search feature a production healthcare platform actually issues works identically ([inventory](../evidence/search-usage-inventory.md)). | PLANNED |  |
| REQ-DBO-SRCH-STRICT-BY-DEFAULT | An unsupported search parameter is rejected, never silently ignored. | PLANNED |  |
| REQ-DBO-SRCH-HONEST-CAPABILITY | The CapabilityStatement is generated from what the server actually serves — the configured types, the interactions their declared handling permits, the conditional writes their identity class allows, the history their durability keeps, the search parameters accepted, and the operations registered by the facades that were wired. An operation is declared because it is routable: the router and the statement read one list, so neither a served-but-undeclared operation nor a declared-but-unanswered one is expressible. | PLANNED |  |
| REQ-DBO-SRCH-TYPED-ORDERING | Sorting and range filtering are typed — numeric, date and token semantics are correct, with matching indexes. (D3) | PLANNED |  |
| REQ-DBO-SRCH-DECLARED-INDEXES | Indexing (including side tables for hard parameters) is declared by the personality as part of its search contract, from day one. | PLANNED |  |
| REQ-DBO-SRCH-CUSTOM-PARAMETERS | A tenant or module can register a custom search parameter; extraction, reindex and the new index follow automatically. | PLANNED |  |

## FEED — feeds, pagination, synchronization

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-FEED-ONE-PRIMITIVE | Pagination, subscription delivery, content streams and edge sync are all the same primitive: an ordered, replayable sequence with an opaque durable cursor. | PLANNED |  |
| REQ-DBO-FEED-KEYSET-CURSORS | Cursors are keyset positions, never offsets; a page is stable under concurrent writes. | PLANNED |  |
| REQ-DBO-FEED-PUSH-ACK-RESUME | Push consumers acknowledge with the cursor; any interrupted stream resumes from the last acknowledged position. | PLANNED |  |
| REQ-DBO-FEED-IDEMPOTENT-DELIVERY | Delivery is at-least-once with idempotent apply by identity and version. | PLANNED |  |
| REQ-DBO-FEED-NAMED-CONSUMERS | Every durable consumer holds a named cursor in the store; progress, lag and replay are uniformly observable. | PLANNED |  |
| REQ-DBO-FEED-LEAN-WIRE-OPTION | Between DBO-speaking parties, feeds stream lean frames; FHIR Bundles are assembled only at the FHIR surface. | PLANNED |  |

## EVT — eventing & subscriptions

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-EVT-TRANSACTIONAL-OUTBOX | Every change event originates as an outbox row committed with the write. (R8, §6) | PLANNED |  |
| REQ-DBO-EVT-FHIR-SUBSCRIPTIONS | Topic-based FHIR Subscriptions (R5/R6 style, backported to the R4 personality) are a core capability. (R8) | PLANNED |  |
| REQ-DBO-EVT-DURABLE-DELIVERY | Subscription delivery is durable, tenant-scoped and replayable, with retries, backoff and dead-lettering. (R8, §9) | PLANNED |  |
| REQ-DBO-EVT-IN-PROCESS-SURFACE | Co-located consumers get the same topics with identical semantics through the in-process/OSGi surface. (R8) | PLANNED |  |

## WF — durable work & planes

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-WF-POSTGRES-SUBSTRATE | Durable tasks, streams and inter-instance communication run on the DBOS/Postgres substrate; no external broker. (R4) | PLANNED |  |
| REQ-DBO-WF-TWO-PLANES | Workflow state lives where its content belongs: platform plane for coordination, tenant plane for anything carrying resource content. | PLANNED |  |
| REQ-DBO-WF-CONTENT-FREE-PLATFORM-PLANE | Platform-plane workflow parameters and checkpoints never contain tenant credentials or resource content. | PLANNED |  |
| REQ-DBO-WF-DECLARED-STEP-PLANE | Every workflow step declares its plane at definition time. | PLANNED |  |
| REQ-DBO-WF-PLATFORM-COORDINATED-HOPS | Every cross-plane or cross-tenant hop is coordinated by the platform; no direct tenant-to-tenant connection exists. | PLANNED |  |
| REQ-DBO-WF-HOPS-AUDITED | Every hop produces sender egress, receiver ingress and platform coordination records — audit is structural, not per-integration. | PLANNED |  |
| REQ-DBO-WF-GRANTS-FROM-CATALOGUE | A hop grant can only be issued for a hop the declared process shape contains. | PLANNED |  |

## SCAL — scaling & routing

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-SCAL-DURABLE-ASSIGNMENT | The tenant→pod assignment is durable state with version-driven takeover. | PLANNED |  |
| REQ-DBO-SCAL-SINGLE-WRITER-TENANT | A tenant's serving pod is its single writer, making local caching and local subscription state correct by construction. | PLANNED |  |
| REQ-DBO-SCAL-TRANSPARENT-ROUTING | Callers look up a tenant's service in the registry; local instance or remote proxy is indistinguishable. | PLANNED |  |
| REQ-DBO-SCAL-TWO-HOP-LOCALITY | Requests enter at the closest public node (Kubernetes locality), then route to the serving pod (tenant assignment). | PLANNED |  |
| REQ-DBO-SCAL-NO-SHARED-STATE-BROKER | The architecture requires no Redis-class shared-state service. | PLANNED |  |

## TERM — terminology

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-TERM-NATIVE-FORM | Terminology lives in a normalized, query-optimized form; the FHIR resource form is a wire projection assembled on demand. | PLANNED |  |
| REQ-DBO-TERM-BULK-LOAD | Loading a large CodeSystem is a native bulk operation — no chunking workarounds, no parameter-cap ceilings. | PLANNED |  |
| REQ-DBO-TERM-EVERY-TENANT-ANSWERS | Every served tenant answers `$lookup`, `$expand` and `$validate-code` from its own store's native form, whichever FHIR version it speaks; no tenant is a second-class reader. A terminology write reaches that form rather than being stored whole — a resource that is present and answers nothing is worse than one that is absent. | PLANNED |  |
| REQ-DBO-TERM-OPERATIONS-FROM-NATIVE-FORM | `$expand`, `$lookup` and `validate-code` are served from the normalized form at tenant-local speed. | PLANNED |  |
| REQ-DBO-TERM-VALIDATION-USES-TENANT-TERMINOLOGY | Validation resolves coded values against the tenant's own terminology where the carried definitions are silent: a code from a system the tenant holds either exists in it or the write is refused, value-set membership respects the binding's declared strength, and a system nobody holds is reported as unresolvable — a coverage fact, never an invalidity. | PLANNED |  |

## SYNC — canonical content dependencies

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-SYNC-DECLARED-ONLY | Cross-tenant content synchronization happens only for declared dependencies; nothing syncs undeclared. | PLANNED |  |
| REQ-DBO-SYNC-ANY-TYPE | Any resource type can be declared as a cross-tenant dependency; each type defines its grain — for terminology, the CodeSystem together with its related ValueSets. | PLANNED |  |
| REQ-DBO-SYNC-TERMINOLOGY-GRAIN-SURVIVES | A streamed terminology dependency rebuilds the receiving tenant's native form: the source sends the whole CodeSystem even though it stores a shell, and the dependent takes it apart into its own concepts. After catch-up the dependent answers `$lookup` and `$expand` locally, which is the only proof that the grain survived the hop — a copy's stored payload never contains a concept at either end. | PLANNED |  |
| REQ-DBO-SYNC-CONVERT-ON-APPLY | Streamed objects are converted at apply into the receiving tenant's FHIR version and object shape by the registered converter chains; an unconvertible object dead-letters visibly and degrades the dependency, never silently skips. | PLANNED |  |
| REQ-DBO-SYNC-PROVENANCE-COPIES | Streamed copies are read-only and provenance-tagged with source tenant and version; updates and retirements propagate through the same stream. | PLANNED |  |
| REQ-DBO-SYNC-LOCAL-SHADOWING | A tenant's own object with the same base identity overrides the streamed copy — version-neutrally, across FHIR versions and business versions; removing the override falls back to the live upstream version. | PLANNED |  |
| REQ-DBO-SYNC-DIRECT-UPSTREAM-ONLY | A tenant declares dependencies only against its direct upstream; chains compose hop by hop. | PLANNED |  |
| REQ-DBO-SYNC-SPEC-DECLARED | A tenant's content dependencies are part of its tenant spec (configuration); the runtime wires declared streams at bring-up and removes them when undeclared. | PLANNED |  |
| REQ-DBO-SYNC-FULL-HISTORY-CATCH-UP | A newly declared dependency catches up from the upstream's full history; pre-existing content arrives the same way live changes do. | PLANNED |  |

## VAL — coded-value validation

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-VAL-BINDING-STRENGTH-IS-THE-ANSWER | A coded value is checked against the terminology the store holds, and the answer follows the binding's strength: required violated is a refusal, weaker bindings are advice a caller is given rather than refused for, and everything the face had to say reaches the outcome rather than only what would refuse. | PLANNED |  |
| REQ-DBO-VAL-UNRESOLVABLE-IS-NOT-INVALID | A code from a system the store does not hold is reported as unresolvable, never as invalid: one says this store's content is incomplete and the other says the caller's data is wrong, and they are fixed by different people. | PLANNED |  |

## OPS — operations

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-OPS-TENANT-BLOB-STORAGE | Binary content lives in per-tenant blob storage provisioned credential-blind; erasure-by-drop extends to it; small deployments fall back to Postgres behind the same interface. | PLANNED |  |
| REQ-DBO-OPS-RUNTIME-SAYS-WHAT-IT-SERVES | A runtime can be asked which tenants it is serving, and what it is doing about the ones it is not: serving, coming up, failed to come up — one state per tenant it has been told about. The answer comes from runtime state, never from re-reading the declarations, so a caller comparing the two can find a disagreement rather than confirming its own writes. Cross-tenant, so no tenant credential buys it. | PLANNED |  |
| REQ-DBO-OPS-MIGRATION-AS-DEPLOYMENT | Schema and engine upgrades ride rolling deployment: the highest-version node leads, migrates, and older nodes passivate. (D5) | PLANNED |  |

## MNT — maintenance

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-MNT-BACKUP-IS-EXPORT | Backup and export are one mechanism, restore and import another single one; every backup is restorable by the everyday import path. | PLANNED |  |
| REQ-DBO-MNT-PORTABLE-STATE-EXPORT | The latest-state export is idempotent, store-independent FHIR (with blob content, hash-verified) — importable into a fresh tenant, the same tenant, or any other FHIR store. It travels as Bulk Data: NDJSON per type whose resources carry their own id and version, beside the manifest that spec defines — same digests the archive was attested over, so a stranger checking the export and a party checking the signatures cannot get different answers. | PLANNED |  |
| REQ-DBO-MNT-HISTORY-BY-SCHEMA | Version history, audit and consumer state live in their own database schemas, so the high-fidelity history element is a schema-scoped dump, restorable byte-exact. | PLANNED |  |
| REQ-DBO-MNT-OWNER-KEY-ENCRYPTION | An export bundle is encrypted so that only the tenant owner's master key can open it; the platform operates backups it cannot read, and restore requires the owner. | PLANNED |  |
| REQ-DBO-MNT-SNAPSHOT-CONSISTENT | The state element is cut at a single consistent snapshot; incremental export is the feed from that snapshot's cursor. | PLANNED |  |
| REQ-DBO-MNT-ARCHIVE-ROOT-OVER-CONTENTS | An archive's attested root is computed over the manifest's per-entry digests rather than over the archive's bytes, so re-packing, re-compressing or reordering does not invalidate what was attested. | PLANNED |  |
| REQ-DBO-MNT-BOTH-PARTIES-ATTEST | An archive carries two detached signatures over that root — the vendor's and the tenant's — and the tenant countersigns without resealing, so neither party can produce an attested archive alone. | PLANNED |  |
| REQ-DBO-MNT-IMPORT-REFUSES-UNATTESTED | Objects enter a store from an archive by one path only: the root recomputes and both signatures verify, or nothing is written. A refusal names what was wrong with the archive rather than failing part-way through it. | PLANNED |  |
| REQ-DBO-MNT-ATTESTATION-READS-AS-FHIR | An archive's attestation renders as a `Provenance` carrying FHIR's `Signature`, so a customer's own tooling can check what it was handed without learning this store's JSON. A view rendered by the face, never the truth form — an archive of a non-FHIR domain is attested the same way and has no Provenance. | PLANNED |  |
| REQ-DBO-MNT-ACCEPTED-ROOT-RECORDED | A destination records the root it accepted and the two keys that signed it, in the tenant's own audit trail, so what was imported and what both parties said it was stays answerable without the archive. | PLANNED |  |

## PRM — promise (requirements as code)

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-PRM-NAME-IS-THE-CODE | A promise is declared exactly once, as an enum constant; its code derives from the constant's name and its catalogue's namespace, so a citation cannot drift from a declaration — there is no string to mistype and no generator to trust. | PLANNED |  |
| REQ-DBO-PRM-GAP-IS-FIRST-CLASS | Unstated ground is declared as a gap with plain text; a gap registers, carries a stable code, and counts against coverage until promoted to a named promise. | PLANNED |  |
| REQ-DBO-PRM-REGISTERED-AT-COMPILE-TIME | An annotated catalogue is registered during its own component's compilation — no classpath is swept, and a registration regenerated on every compile cannot drift or be lost. | PLANNED |  |
| REQ-DBO-PRM-CATALOGUE-READ-WHOLE | The registry reads a catalogue's constants whole — proven, planned and gap alike — never as a side effect of what happened to be class-loaded. | PLANNED |  |
| REQ-DBO-PRM-DOWN-LINKS-ONLY | A classification declares the promises that fulfil it; a promise never names its classifications; the inverse is derived. One direction, one truth. | PLANNED |  |
| REQ-DBO-PRM-AREAS-MERGE-BY-CODE | Composition merges same-code areas across catalogues and refuses two with conflicting prose rather than picking one. | PLANNED |  |
| REQ-DBO-PRM-CITATION-IS-TYPED | A test cites promises through its product's own enum-typed annotation, recognised by meta-annotation — a mistyped citation is a compile error, and the framework never learns a product's types. | PLANNED |  |
| REQ-DBO-PRM-PROOFS-INDEXED-AT-COMPILE-TIME | Citation sites are indexed during the product's own compilation; a renamed or deleted proof site cannot leave a stale citation behind. | PLANNED |  |
| REQ-DBO-PRM-STATUS-IS-DERIVED | A promise's status is computed — cited is proven, named-uncited is planned, assurance is declared on the constant, a gap is a gap — never asserted at a proof site. | PLANNED |  |
| REQ-DBO-PRM-COVERAGE-IS-A-FOLD | A classification's coverage is the fold of its declared promises' statuses, gaps included; an area's is the fold of its classifications. | PLANNED |  |
| REQ-DBO-PRM-PROJECTION-IS-GENERATED | The catalogue's prose form is generated from the composed model, never a second source; a hand-edit or a stale projection fails the build. | PLANNED |  |
| REQ-DBO-PRM-COVERAGE-ON-THE-RESULTS-PAGE | Every CI run's results page leads with the composed promise coverage report. | PLANNED |  |

## SCIM — staff provisioning surface

| REQ | Promise | Status | Proven by |
|---|---|---|---|
| REQ-DBO-SCIM-DECLARED-PER-TENANT | A tenant serves SCIM 2.0 only when its spec declares it (the block naming the externalId system); absent the block, the endpoints do not exist. | PLANNED |  |
| REQ-DBO-SCIM-USER-IS-THE-PERSON | A SCIM User is the human: the externalId claimed and identifying data authored on the Person, with a linked Practitioner capacity ensured on create — the same linkage the authority walks at token time. | PLANNED |  |
| REQ-DBO-SCIM-ENUMERATION-STAYS-INSIDE | The by-system enumeration answering the user list is a vault method inside this server; no store API, face or FHIR search gains it, and an enumeration-shaped search stays refused at the front door. | PLANNED |  |
| REQ-DBO-SCIM-DIRECTORY-CREDENTIAL | The SCIM client's scope admits the SCIM surface and nothing else; its token is refused by the FHIR surface and a store token is refused by SCIM. | PLANNED |  |
| REQ-DBO-SCIM-DEPROVISION-IS-A-STATE | Deactivation sets active=false on the person and the capacity; it is never erasure — that remains the vault's own ceremony with its own audit shape. | PLANNED |  |
| REQ-DBO-SCIM-EVERY-OP-IS-A-DISCLOSURE | Every SCIM operation runs with the client as caller and an administrative purpose stated, so it lands in the trail as one recorded provisioning disclosure. | PLANNED |  |
| REQ-DBO-SCIM-GROUPS-READ-ONLY | Groups render from active role grants and refuse writes permanently — who works here is the identity provider's call; who is an admin here is not. | PLANNED |  |

<!-- promise:end -->
