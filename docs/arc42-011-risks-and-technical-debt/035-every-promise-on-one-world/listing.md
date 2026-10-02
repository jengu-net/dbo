# Listing: what each world-booting class proves, and the story it goes to

A snapshot taken on 2026-10-01 from the sources: the classes that boot a dbo world, the `@Proving` citations they carry, and the story whose declaration holds most of those promises. **W** marks a promise no class proves without a world, so it must be proven in a story before its class goes. Status is edited by hand as the work moves: `todo`, `moved`, `in story`, `deleted`. The leg column names methods of the story classes, which now live in `samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories`; a few legs were renamed on the way.

[Back to the item](README.md).

## US-DBO-TENANT-OPENING

Story class: `ATenantOpensAndItsPeopleGetInIT` — exists; today own (lifecycle).

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `ATenantOpensAndItsPeopleGetInIT` (story class) | own (lifecycle) | 17 | 16 | moved, harness class deleted |
| `HumanAuthIT` | own (deployment) | 9 | 9 | deleted |
| `ScimProvisioningIT` | shared | 7 | 7 | deleted |
| `FederatedAuthIT` | own (deployment) | 3 | 3 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `AFaceIsCutOnceAndBroughtUpFromIT` | own (deployment) | 3 | 2 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `ATenantSubscribesToItsVersionIT` | own (lifecycle) | 3 | 2 | trimmed to its two build-counter legs, which read the container's own counters |
| `ADeclaredRelationGrantsIT` | shared | 1 | 1 | deleted |
| `ADeploymentPresentsItsOwnCredentialIT` | own (deployment) | 1 | 1 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `AGrantCanBeTakenBackIT` | shared | 1 | 1 | deleted |
| `APartnerFollowsWorkIT` | shared | 1 | 1 | deleted |
| `AProvisioningClientConvergesTheGrantsIT` | shared | 1 | 1 | deleted |
| `AStreamKeepsMovingWhileATenantComesUpIT` | own (lifecycle) | 1 | 1 | deleted; walked in `BringUpUnderStrainIT` |
| `ATenantComesUpFromTheFaceImageIT` | own (deployment) | 2 | 1 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `ScimNeedsTheMembraneIT` | own (sweep) | 1 | 1 | deleted |
| `SeveralTenantsDeclaredAtOnceComeUpTogetherIT` | own (lifecycle) | 1 | 0 | deleted; walked in `BringUpUnderStrainIT` |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `CONT_DYNAMIC_TENANT_SERVICES` | — | W | `1` `aDeclarationIsTheWholeOfOpeningAClinic` |
| `CONT_EMBEDDED_IN_JVM` | — | W | `1` `aDeclarationIsTheWholeOfOpeningAClinic` |
| `TEN_DEDICATED_DATABASE_TIER` | — | W | `1` `aDeclarationIsTheWholeOfOpeningAClinic` |
| `TEN_CREDENTIAL_BLIND_PROVISIONING` | — | W | `2` `theClinicGetsADatabaseNobodyHereHeldTheKeyTo` |
| `AUTH_DENY_BY_DEFAULT` | — | W | `3` `nothingIsReachableWithoutACredential` |
| `AUTH_BEARER_LOCAL_VALIDATION` | — | W | `4` `theClinicsOwnAuthorityIssuesAndChecksTheToken` |
| `AUTH_SMART_SHAPED_SCOPES` | — | W | `4` `theClinicsOwnAuthorityIssuesAndChecksTheToken` |
| `AUTH_TENANT_SCOPED_ISSUER` | — | W | `4` `theClinicsOwnAuthorityIssuesAndChecksTheToken` |
| `AUTH_ONE_CEREMONY_MANY_TENANTS` | `FederatedAuthIT` | W | `5` `aSecondClinicOpensAndTheTenantsCannotSeeEachOther` |
| `TEN_STRUCTURAL_SCOPING` | — | W | `5` `aSecondClinicOpensAndTheTenantsCannotSeeEachOther` |
| `SCIM_DECLARED_PER_TENANT` | — | W | `6` `theDirectoryProvisionsAClinician` |
| `SCIM_DIRECTORY_CREDENTIAL` | — | W | `6` `theDirectoryProvisionsAClinician` |
| `SCIM_USER_IS_THE_PERSON` | — | W | `6` `theDirectoryProvisionsAClinician` |
| `SCIM_ENUMERATION_STAYS_INSIDE` | — | W | `7` `enumerationStaysBehindTheDirectoryDoor` |
| `SCIM_GROUPS_READ_ONLY` | — | W | `7` `enumerationStaysBehindTheDirectoryDoor` |
| `AUTH_IDENTITY_AS_RECORDS` | — |  | `8` `whatAClinicianMayDoIsDeclaredAndWhoTheyAreIsARecord` |
| `AUTH_ORG_MODEL_IS_THE_AUTH_MODEL` | — | W | `8` `whatAClinicianMayDoIsDeclaredAndWhoTheyAreIsARecord` |
| `AUTH_A_ZONE_IS_ITS_OWN_BROKER` | `TheGuideRunsIT` | W | `35` `aZoneIsItsOwnBroker` |
| `AUTH_BOOTSTRAP_SECRET_IS_CUSTODY` | `ADeploymentPresentsItsOwnCredentialIT` | W | to fit |
| `AUTH_CREDENTIAL_FACTORS_BY_KIND` | without a world: EdgePinIsACredentialIT |  | assert where the story passes it |
| `AUTH_DEACTIVATION_RETIRES_CREDENTIALS` | — | W | `19` `aClinicianChangesTheirOwnSecret`, `20` `aFirstSecretIsSetFromAOneTimeGrant` |
| `AUTH_FEDERATED_HUMANS` | `FederatedAuthIT` | W | `10` `anIdentifierFromOutsideNamesThePerson` |
| `AUTH_FIRST_SECRET_BY_ONE_TIME_GRANT` | — | W | `20` `aFirstSecretIsSetFromAOneTimeGrant`, `21` `aGrantIsNotACredential` |
| `AUTH_GRANTS_ARE_READABLE_TO_CONVERGE` | — | W | `26`–`28`, from `whatWasGrantedReadsBack` |
| `AUTH_NO_SUBJECT_ENUMERATION` | — | W | `19` `aClinicianChangesTheirOwnSecret`, `20` `aFirstSecretIsSetFromAOneTimeGrant` |
| `AUTH_PASSWORD_ONLY_WHERE_WE_ARE_THE_IDP` | `FederatedAuthIT` | W | to fit |
| `AUTH_PORTABLE_AUTHORITY` | without a world: AnAuthorityMovesWithoutRekeyingIT |  | assert where the story passes it |
| `AUTH_PRIVATE_SURFACE` | — |  | PLANNED — nothing cites it |
| `AUTH_PSEUDONYMOUS_TOKENS` | — | W | `15` `aClinicianSignsInAndTheTokenIsAPseudonym` |
| `AUTH_RECOVERY_IS_AN_OPERATOR_ACT` | — | W | `19` `aClinicianChangesTheirOwnSecret` |
| `AUTH_ROLE_GRANTS_AS_RECORDS` | without a world: AGrantIsARecordLikeAnyOtherIT |  | assert where the story passes it |
| `AUTH_SELF_SERVICE_CHANGE` | — | W | `19` `aClinicianChangesTheirOwnSecret` |
| `CONT_FAST_COLD_START` | `ServerDistIT` | W | to fit |
| `CONT_FRAMEWORK_FREE_CORE` | without a world: SqlDisciplineTest |  | assert where the story passes it |
| `CONT_IMPORTS_ARE_COMPUTED_OR_CHECKED` | without a world: EverySlf4jImportNamesItsGenerationTest, TheStackImportsWhatItReachesForTest |  | assert where the story passes it |
| `CONT_PRIVATE_DEPENDENCIES` | without a world: ApiBoundaryTest, EmbeddedContainerIT |  | assert where the story passes it |
| `SCIM_DEPROVISION_IS_A_STATE` | — | W | to fit |
| `SCIM_EVERY_OP_IS_A_DISCLOSURE` | — | W | to fit |
| `TEN_A_PARTNER_MANAGES_TENANTS` | — | W | `30` `aPartnerFollowsTheWorkAndNothingElse` |
| `TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE` | `AFaceIsCutOnceAndBroughtUpFromIT`, `ATenantComesUpFromTheFaceImageIT` |  | to fit |
| `TEN_A_TYPE_DECLARES_ITS_DOMAIN` | `TheVersionIsMeasuredIT` | W | to fit |
| `TEN_COMING_UP_AND_KEEPING_UP_ARE_NOT_ONE_QUEUE` | `BringUpUnderStrainIT` | W | to fit |
| `TEN_DECLARED_TOGETHER_COME_UP_TOGETHER` | `BringUpUnderStrainIT` |  | to fit |
| `TEN_READY_WHEN_ITS_CRITICAL_DEFINITIONS_ARRIVED` | — | W | `25` `aChainWithoutItsCodeSystemsIsRefusedByName` |
| `TEN_REGISTRY_SCOPED_ACCESS` | `TenantOsgiIT` | W | to fit |
| `TEN_SHARED_TIER_ISOLATION` | — |  | PLANNED — nothing cites it |
| `TERM_BINDINGS_ANSWERED_FROM_RECORDS` | — |  | `23` `aBindingIsAnsweredFromTheRecordsTheClinicHolds` |
| `VER_AN_IMAGE_FROM_ANOTHER_RELEASE_IS_REFUSED` | `AFaceIsCutOnceAndBroughtUpFromIT` | W | to fit |
| `VER_AN_IMAGE_IS_CUT_ONLY_WHEN_COMPLETE` | `AFaceIsCutOnceAndBroughtUpFromIT`, `ATenantComesUpFromTheFaceImageIT` | W | to fit |
| `VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS` | `ATenantSubscribesToItsVersionIT` (build counters) | W | `22` `aClinicHoldsItsVersionAsRecords`, `24` `anotherClinicOnTheFaceSharesTheBase` |

## US-DBO-CLINICAL-RECORD

Story class: `TheClinicRecordsCareAndAccountsForItIT` — exists; today shared.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `TheClinicRecordsCareAndAccountsForItIT` (story class) | shared | 28 | 13 | moved, harness class deleted |
| `ATenantAuthorsItsOwnSearchParameterIT` | own (sweep) | 4 | 3 | deleted |
| `ATenantDeliversWhatItSubscribedToIT` | shared | 2 | 2 | deleted |
| `TheEnvelopeIsTheSameFromEitherSideIT` | shared | 2 | 2 | stays — trimmed to the two in-process store legs; the version measured |
| `ABlobIsTenantDataIT` | own (lifecycle) | 1 | 1 | deleted; walked in `AClinicIsErasedIT` (story, on the world) |
| `ATenantHoldsItsOwnDeclarationIT` | shared | 2 | 1 | deleted |
| `ATypeSaysWhereItsEnvelopeIsComputedIT` | shared | 1 | 1 | deleted |
| `ContentHeldWholeIsReachableOverTheWireIT` | shared | 1 | 1 | deleted |
| `R6TenantIT` | shared | 2 | 0 | deleted |
| `TenantProfilesValidateIT` | shared | 1 | 0 | deleted |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `CORE_DECLARED_TRUTH_FORM` | — |  | `1` `whatWasWrittenIsWhatIsRead` |
| `CORE_PAYLOAD_IS_TRUTH` | — |  | `1` `whatWasWrittenIsWhatIsRead` |
| `CORE_READ_YOUR_WRITES` | — | W | `1` `whatWasWrittenIsWhatIsRead` |
| `CORE_CONDITIONAL_UPSERT` | — | W | `2` `thesamePatientArrivingTwiceIsOnePatient` |
| `CORE_EXTERNAL_IDENTIFIERS` | — | W | `2` `thesamePatientArrivingTwiceIsOnePatient` |
| `CORE_IDENTITY_KEYED_CONDITIONALS` | — | W | `2` `thesamePatientArrivingTwiceIsOnePatient` |
| `CORE_NO_IMPLICIT_MERGE` | — | W | `2` `thesamePatientArrivingTwiceIsOnePatient` |
| `CORE_VERSIONED_HISTORY` | — | W | `3` `everyVersionIsKept` |
| `CORE_ATOMIC_TRANSACTION_BUNDLE` | — | W | `4` `aVisitLandsWhole` |
| `CORE_CONDITIONAL_REFERENCES` | — | W | `4` `aVisitLandsWhole` |
| `CORE_REFERENCE_EDGES` | — | W | `4` `aVisitLandsWhole` |
| `CORE_BATCH_ANSWERS_PER_ENTRY` | — | W | `5` `allOfItOrNoneOfIt` |
| `TERM_EVERY_TENANT_ANSWERS` | — | W | `8` `aCodeMeansWhatThisClinicSaysItMeans` |
| `TERM_NATIVE_FORM` | — |  | `8` `aCodeMeansWhatThisClinicSaysItMeans` |
| `TERM_OPERATIONS_FROM_NATIVE_FORM` | — |  | `8` `aCodeMeansWhatThisClinicSaysItMeans` |
| `VAL_UNRESOLVABLE_IS_NOT_INVALID` | — |  | `8` `aCodeMeansWhatThisClinicSaysItMeans` |
| `SRCH_HONEST_CAPABILITY` | — | W | `9` `theStoreSaysWhatItCanSearch` |
| `SRCH_STRICT_BY_DEFAULT` | `R6TenantIT` |  | `9` `theStoreSaysWhatItCanSearch` |
| `SRCH_TIER1_PARITY` | — |  | `10` `findingHerAgain` |
| `POL_ACTOR_FROM_AUTHORITY` | — |  | `11` `theTrailHoldsItAndNobodyCanEditIt` |
| `POL_AUDIT_AS_RECORDS` | — |  | `11` `theTrailHoldsItAndNobodyCanEditIt` |
| `POL_AUDIT_UNCONDITIONALLY_APPEND_ONLY` | — |  | `11` `theTrailHoldsItAndNobodyCanEditIt` |
| `POL_DECLARED_AT_CONFIGURATION` | — |  | `11` `theTrailHoldsItAndNobodyCanEditIt` |
| `POL_FHIR_AUDIT_PROJECTION` | — |  | `11` `theTrailHoldsItAndNobodyCanEditIt` |
| `EVT_TRANSACTIONAL_OUTBOX` | — | W | `12` `oneFeedCarriesItAll` |
| `FEED_KEYSET_CURSORS` | — |  | `12` `oneFeedCarriesItAll` |
| `FEED_NAMED_CONSUMERS` | — |  | `12` `oneFeedCarriesItAll` |
| `FEED_ONE_PRIMITIVE` | — |  | `12` `oneFeedCarriesItAll` |
| `CORE_DECLARED_IDENTITY` | without a world: EveryTypeDeclaresItsIdentityTest, TheGuideRunsIT |  | assert where the story passes it |
| `CORE_PARAMETERIZED_SQL` | without a world: AFatalNamesWhatKindOfWrongItIsIT, SqlDisciplineTest |  | assert where the story passes it |
| `CORE_SIBLING_MODELS` | without a world: SiblingModelsRideTheSameEngineIT |  | assert where the story passes it |
| `EVT_A_TENANT_DELIVERS` | — | W | to fit |
| `EVT_DURABLE_DELIVERY` | — |  | PLANNED — nothing cites it |
| `EVT_FHIR_SUBSCRIPTIONS` | — | W | to fit |
| `EVT_IN_PROCESS_SURFACE` | — |  | PLANNED — nothing cites it |
| `OPS_TENANT_BLOBS_ARE_TENANT_DATA` | `AClinicIsErasedIT` | | US-DBO-A-TENANT-IS-ERASED |
| `OPS_TENANT_BLOB_STORAGE` | — |  | PLANNED — nothing cites it |
| `POL_APPEND_ONLY_DISCIPLINE` | without a world: PolicyIT |  | assert where the story passes it |
| `POL_CUSTOM_AUDIT_EVENTS` | without a world: ContributedAuditEventTest, PolicyIT |  | assert where the story passes it |
| `POL_DECLARATIVE_RETENTION` | without a world: PolicyIT |  | assert where the story passes it |
| `POL_RETENTION_SWEEP` | without a world: PolicyIT |  | assert where the story passes it |
| `SRCH_A_PARAMETER_IS_COMPILED_WHEN_IT_ARRIVES` | — | W | to fit |
| `SRCH_A_REINDEX_HOLDS_NO_TRANSACTION_WHILE_IT_EXTRACTS` | without a world: AReindexDoesNotHoldATransactionOpenIT |  | assert where the story passes it |
| `SRCH_CUSTOM_PARAMETERS` | — | W | to fit |
| `SRCH_DECLARED_INDEXES` | — |  | to fit |
| `SRCH_SEVERAL_VALUES_MEAN_ANY_OF_THEM` | `TheClinicRecordsCareAndAccountsForItIT` |  | moved; `ACommaMeansOrInASearchIT` deleted |
| `SRCH_THE_DATABASE_ENVELOPE_LOSES_NOTHING_BEFORE_IT_IS_USED` | `TheEnvelopeIsTheSameFromEitherSideIT` | W | to fit |
| `SRCH_THE_ENVELOPE_IS_EXTRACTED_WHERE_THE_BYTES_ARE` | `TheEnvelopeIsTheSameFromEitherSideIT` | W | to fit |
| `SRCH_TYPED_ORDERING` | without a world: ADateIsTheSpanItNamesTest, FhirR4IT … |  | assert where the story passes it |
| `TEN_A_FEED_SAYS_WHAT_CHANGED_NOT_WHAT_IT_SAYS` | without a world: AFeedSaysWhatChangedNotWhatItSaysTest |  | assert where the story passes it |
| `TERM_BINDINGS_ANSWERED_FROM_RECORDS` | without a world: ABindingIsAnsweredFromRecordsTest, ATenantSubscribesToItsVersionIT |  | assert where the story passes it |
| `TERM_BULK_LOAD` | without a world: TerminologyIT |  | assert where the story passes it |
| `TERM_VALIDATION_USES_TENANT_TERMINOLOGY` | without a world: CodedValuesAreCheckedIT, TenantTermsValidationTest |  | assert where the story passes it |
| `VAL_BINDING_STRENGTH_IS_THE_ANSWER` | without a world: CodedValuesAreCheckedIT, TheGuideRunsIT |  | assert where the story passes it |
| `VER_ONE_READ_PER_REQUEST` | without a world: OneReadPerRequestTest, ReadOnceTest |  | assert where the story passes it |
| `VER_PERSONALITY_OWNS_MEANING` | `R6TenantIT` |  | to fit |
| `VER_SPECIFIED_VALIDATION` | — |  | to fit |
| `VER_VALIDATION_WITHOUT_WRITING` | `TheGuideRunsIT` | W | `34` `askingForTheVerdictWithoutWriting` |
| `VER_VERSION_AGNOSTIC_CORE` | without a world: ADomainThatIsNotHealthcareHasAFaceIT, EngineKnowsNoFaceIT … |  | assert where the story passes it |

## US-DBO-PERSON-RIGHTS

Story class: `WhatAPersonCanAskForIT` — on the world, at Hogwarts.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `AHumanHeldAsTwoRecordsIT` | shared | 6 | 3 | deleted |
| `DelegationIT` | shared | 2 | 2 | deleted |
| `TenantRuntimeIT` | own (sweep) | 4 | 2 | deleted; walked in `AClinicIsErasedIT` (story, on the world) |
| `APseudonymResolvesBackToItsPersonIT` | shared | 2 | 1 | deleted; its page-boundary walk is `PdiIT#theWalkCrossesItsOwnPageBoundary`, without a world |
| `AStoreWithoutSuperuserStillMountsIT` | own (sweep) | 1 | 1 | stays: it needs a store role that is not superuser, and the world's is; it is also the proof that an isolated tenant refuses a database that would log its people |
| `IdentificationIsReachableFromOutsideIT` | shared | 1 | 1 | deleted |
| `ThePlaintextInFlightLeavesNoTraceIT` | own (deployment) | 1 | 1 | deleted; the pin is read on Hogwarts, the refusal is `AStoreWithoutSuperuserStillMountsIT`'s |
| `APseudonymIsDerivedAndNeverKeptIT` | shared | 2 | 0 | deleted |
| `ContentIsSealedToThePersonItIsAboutIT` | shared | 1 | 0 | deleted |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `AUTH_FEDERATED_HUMANS` | `FederatedAuthIT`, `ZoneIT` | W | to fit |
| `AUTH_NO_SUBJECT_ENUMERATION` | — | W | to fit |
| `AUTH_ON_BEHALF_OF` | — | W | `13` `aProcessActsInAClinicianName`, `14`, `15` |
| `AUTH_PSEUDONYMOUS_TOKENS` | — | W | to fit |
| `AUTH_PURPOSE_IS_STATED_PER_REQUEST` | — | W | `14` `aStandingDelegationOutlivesTheTokenAndNeverWidens` |
| `CORE_IDENTITY_KEYED_CONDITIONALS` | — | W | `5` `aNumberTheHospitalHoldsIsNeverAnsweredEmpty` |
| `CORE_NO_IMPLICIT_MERGE` | — | W | `3` `sheIsOneHumanHeldAsTwoRecords` |
| `IDN_ANONYMITY_IS_DECLARED_NOT_INFERRED` | without a world: AnonymityIT |  | `7` `whoSheIsIsDecidedAndCanBeUndone` |
| `IDN_ASSURANCE_IS_THE_WEAKER_OF_THE_TWO` | without a world: AssuranceIT |  | assert where the story passes it |
| `IDN_A_DECISION_IS_EVIDENCE` | without a world: AdjudicationIT, AdjudicationPersistedIT |  | `7` `whoSheIsIsDecidedAndCanBeUndone` |
| `IDN_BINDING_IS_REVERSIBLE_AND_KEEPS_ITS_EVIDENCE` | without a world: BindingIT |  | `7` `whoSheIsIsDecidedAndCanBeUndone` |
| `IDN_CLAIM_STRENGTH_BOUNDS_THE_CONCLUSION` | without a world: IdentityLookupIT, IdentityResolutionIT |  | `6` `identifyingHerIsADoorOfItsOwn` |
| `IDN_IDENTIFICATION_IS_REACHABLE` | — | W | `6` `identifyingHerIsADoorOfItsOwn`, `7` |
| `IDN_WHAT_A_RECIPIENT_SEES_IS_DECLARED` | `TheGuideRunsIT` | W | `1` `readingHerIsNotTheSameAsWritingHer` |
| `PDI_AN_ID_THE_STORE_NEVER_ASSIGNED_IS_NOT_A_FAULT` | without a world: PdiIT |  | assert where the story passes it |
| `PDI_A_REFUSAL_ANSWERS_AS_A_REFUSAL` | without a world: PdiIT, TheGuideRunsIT |  | `4` `lookingSomebodyUpIsAnActWithAReason` |
| `PDI_BLIND_OPERATIONS` | without a world: PdiIT, TheGuideRunsIT |  | assert where the story passes it |
| `PDI_CRYPTO_SHREDDING` | without a world: PdiIT |  | `10` `herRecordingIsSealedToHer`, `12` `afterwardsNothingReachesHer` |
| `PDI_ERASURE_IS_A_RUN` | `TheGuideRunsIT` | W | `11` `sheAsksToBeForgotten` |
| `PDI_ERASURE_SAYS_HOW_FAR_IT_GOT` | `TheGuideRunsIT` | W | `11` `sheAsksToBeForgotten` |
| `PDI_EXACT_RESOLUTION` | without a world: PdiIT |  | `4` `lookingSomebodyUpIsAnActWithAReason`, `5` |
| `PDI_PLAINTEXT_IN_FLIGHT_LEAVES_NO_TRACE` | `AStoreWithoutSuperuserStillMountsIT` | W | `2` `herPlaintextPassesThroughAndLeavesNoTrace` |
| `PDI_PSEUDONYM_RESOLVED_BY_SCAN` | without a world: PdiIT |  | `9` `herPseudonymResolvesBackToHer`, `12` |
| `PDI_RIGHTS_AS_OPERATIONS` | without a world: PdiIT |  | assert where the story passes it |
| `PDI_SHRED_LEDGER` | without a world: PdiIT |  | assert where the story passes it |
| `PDI_STRUCTURAL_VAULT` | without a world: PdiIT |  | `1` `readingHerIsNotTheSameAsWritingHer`, `3`, `8` |
| `PDI_UNFINDABLE_AFTER_ERASURE` | without a world: PdiIT, TheGuideRunsIT |  | `12` `afterwardsNothingReachesHer` |
| `POL_ERASURE_COMPATIBLE` | without a world: PdiIT, TheGuideRunsIT |  | `12` `afterwardsNothingReachesHer` |
| `PROC_CONFIG_APPLIES_AS_A_SWEEP` | `TenantRuntimeIT` |  | to fit |
| `SCIM_DEPROVISION_IS_A_STATE` | `TheGuideRunsIT` | W | to fit |
| `SRCH_HONEST_CAPABILITY` | — | W | in clinical record's legs |
| `TEN_ERASURE_BY_DROP` | `AClinicIsErasedIT` | | US-DBO-A-TENANT-IS-ERASED |
| `TERM_EVERY_TENANT_ANSWERS` | `TenantRuntimeIT` | W | to fit |
| `VER_CONCURRENT_VERSIONS` | `TenantRuntimeIT` |  | to fit |

## US-DBO-TWO-PLACES

Story class: `OneTenantInTwoPlacesIT` — exists; today shared.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `SpecDeclaredSyncIT` | own (sweep) | 6 | 5 | deleted |
| `ZoneIT` | own (deployment) | 5 | 5 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `AZoneReachesAnotherFaceThroughOneProjectionIT` | own (sweep) | 4 | 3 | deleted — folded into `ADeploymentIsEquippedBeforeItStartsIT` |
| `OneTenantInTwoPlacesIT` (story class) | shared | 9 | 2 | zone half moved; appliance half is AnApplianceCarriesPatientDataByWorkIT, no runtime |
| `EachTypeStreamsAtItsOwnGrainIT` | shared | 1 | 1 | deleted |
| `ReplicationDrivenOverHttpIT` | shared | 4 | 1 | deleted |
| `MetaSaysTheEnginesFactsIT` | shared | 1 | 0 | deleted |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `SYNC_DECLARED_ONLY` | — |  | `1` `theClinicDeclaresWhatItTakes` |
| `SYNC_SPEC_DECLARED` | — | W | `1` `theClinicDeclaresWhatItTakes` |
| `ZONE_DECLARATIONS_AS_RECORDS` | — | W | `1` `theClinicDeclaresWhatItTakes` |
| `SYNC_DIRECT_UPSTREAM_ONLY` | — |  | `2` `nothingUndeclaredArrives` |
| `PROC_MIRRORED_RUNS_ARE_FILED_BY_APPLIANCE` | — |  | `4` `whatTheApplianceProducedTravelsWithItsRun` |
| `PROC_THE_LANE_HAS_TWO_BOUNDS` | — |  | `4` `whatTheApplianceProducedTravelsWithItsRun` |
| `FEED_IDEMPOTENT_DELIVERY` | — |  | `5` `aReSentBatchAppliesOnce` |
| `PROC_LANE_APPLY_IS_REPLAY_AND_REORDER_SAFE` | — |  | `5` `aReSentBatchAppliesOnce` |
| `PROC_LANE_EPOCH` | — |  | `6` `aCursorFromAnotherLaneIsRefused` |
| `AUTH_FEDERATED_HUMANS` | `ZoneIT` | W | to fit |
| `AUTH_ONE_CEREMONY_MANY_TENANTS` | `ZoneIT` | W | to fit |
| `FEED_LEAN_WIRE_OPTION` | — |  | PLANNED — nothing cites it |
| `FEED_PUSH_ACK_RESUME` | without a world: FeedIT, SubscriptionsIT |  | assert where the story passes it |
| `PROC_AUDIT_REPLICATES_AS_RECORDED` | without a world: AuditReplicatesAsRecordedIT |  | assert where the story passes it |
| `PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED` | — | W | to fit |
| `PROC_WORK_DRIVEN_ARRIVAL_AND_EXPIRY` | — |  | to fit |
| `SYNC_ANY_TYPE` | — | W | to fit |
| `SYNC_CONVERT_ON_APPLY` | without a world: SyncStreamsIT |  | assert where the story passes it |
| `SYNC_FULL_HISTORY_CATCH_UP` | — | W | to fit |
| `SYNC_LOCAL_SHADOWING` | `AZoneReachesAnotherFaceThroughOneProjectionIT` |  | to fit |
| `SYNC_PROVENANCE_COPIES` | without a world: SyncStreamsIT, TheGuideRunsIT |  | assert where the story passes it |
| `SYNC_TERMINOLOGY_GRAIN_SURVIVES` | — | W | to fit |
| `TEN_A_CHANGE_IS_NOT_A_RETRACTION` | — | W | to fit |
| `TEN_WHAT_A_TENANT_CARES_ABOUT_IS_EDITABLE` | — | W | to fit |
| `ZONE_AN_UNSERVABLE_ZONE_IS_SAID_AT_BRING_UP` | `AZoneReachesAnotherFaceThroughOneProjectionIT` | W | to fit |
| `ZONE_A_ZONE_IS_SERVED_TO_A_FACE_THROUGH_ONE_PROJECTION` | `AZoneReachesAnotherFaceThroughOneProjectionIT` | W | to fit |
| `ZONE_BROKER_CHOICE` | `ZoneIT` | W | to fit |
| `ZONE_SESSIONS_ACCUMULATE` | `ZoneIT` | W | to fit |
| `ZONE_SUBJECT_DOMAINS` | `ZoneIT` | W | to fit |
| `ZONE_WHAT_CONVERSION_CANNOT_CARRY_IS_REFUSED_BY_NAME` | `AZoneReachesAnotherFaceThroughOneProjectionIT` | W | to fit |

## US-DBO-STANDARD-MOVES

Story class: `TheStandardMovesUnderTheDataIT` — exists; today shared.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `ShapeStampIT` | shared | 7 | 7 | deleted |
| `ReshapeIT` | shared | 6 | 6 | deleted |
| `TheFaceSqlShipsWithTheReleaseIT` | shared | 6 | 6 | deleted |
| `TheStandardMovesUnderTheDataIT` (story class) | shared | 7 | 5 | moved, harness class deleted |
| `ADefinitionMovesOnItsOwnFeedIT` | shared | 3 | 3 | deleted — folded into `TheVersionIsMeasuredIT` |
| `AValueIsTheKindOfThingItIsDeclaredToBeIT` | shared | 2 | 2 | deleted — folded into `TheVersionIsMeasuredIT` |
| `NewerDataRefusedIT` | shared | 2 | 2 | deleted |
| `TheTwoAnswersAreComparedOverTheVersionIT` | shared | 2 | 2 | deleted — folded into `TheVersionIsMeasuredIT` |
| `ADefinitionIsExpandedWhenItArrivesIT` | own (first boot) | 7 | 1 | stays — trimmed to the two tampering legs; bring-up made to go wrong |
| `AFaceRootHoldsItsVersionAsRecordsIT` | own (lifecycle) | 1 | 1 | deleted |
| `AReplicatedProfileCanBeValidatedAgainstIT` | shared | 1 | 1 | deleted |
| `AReshapeConvergesOnlyWhatItWasAimedAtIT` | shared | 1 | 1 | deleted |
| `ATypeSaysWhoDecidesAWriteOfItIT` | shared | 1 | 1 | deleted |
| `AWriteIsJudgedFromTheIndexIT` | own (a global dial: the face is chosen by a system property rather than by a declaration, so any tenant whose payloads were first built inside this class's window would keep the index face for the rest of the run. A shared tenant caught that way would leave a neighbouring class quietly testing something else. It joins a shared world when the selector becomes per-tenant) | 1 | 1 | stays — the version measured |
| `OneEnvelopeFromEitherSetOfParametersIT` | shared | 1 | 1 | deleted — folded into `TheVersionIsMeasuredIT` |
| `TwoFacesOverOneDocumentIT` | shared | 1 | 1 | deleted — folded into `TheVersionIsMeasuredIT` |
| `WhatAProfilePinsIsAnsweredFromTheIndexIT` | shared | 1 | 1 | deleted — folded into `TheVersionIsMeasuredIT` |
| `AnIndexBuiltFromTheRowsSaysWhatThePackagesSayIT` | shared | 1 | 0 | deleted — folded into `TheVersionIsMeasuredIT` |
| `AnUpstreamSelectsByNameIT` | shared | 1 | 0 | deleted — folded into `TheVersionIsMeasuredIT` |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `SHAPE_SERVED_BESIDE_THE_CLAIM` | — | W | `1` `whatItWasValidatedUnderIsRecorded` |
| `SHAPE_WRITTEN_UNDER_STAMPED` | — | W | `1` `whatItWasValidatedUnderIsRecorded` |
| `SHAPE_STAMP_IS_DERIVED` | — | W | `2` `theStampIsReplacedNeverAccumulated` |
| `SHAPE_QUERYABLE_BY_VERSION` | — | W | `5` `stockIsFindableByBound` |
| `SHAPE_UNPARSEABLE_VERSION_REFUSED` | — | W | `6` `anUnparseableVersionIsRefusedAtTheDoor` |
| `VER_CONCURRENT_VERSIONS` | — |  | `7` `definitionsTravelWithTheFace` |
| `VER_DEFINITIONS_TRAVEL_WITH_THE_FACE` | — |  | `7` `definitionsTravelWithTheFace` |
| `CORE_IDENTITY_SURVIVES_CONVERSION` | without a world: UpgradeOnReadIT |  | assert where the story passes it |
| `CORE_PAYLOAD_IS_TRUTH` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `CORE_UPGRADE_ON_READ` | without a world: UpgradeOnReadIT |  | assert where the story passes it |
| `FEED_DEFINITIONS_MOVE_ON_A_FEED_OF_THEIR_OWN` | `TheVersionIsMeasuredIT` | W | to fit |
| `SHAPE_HANDBACK_CLAIMS_WITHOUT_LOCKING` | — | W | to fit |
| `SHAPE_HANDBACK_KEEPS_THE_DISCIPLINE` | — | W | to fit |
| `SHAPE_HELD_IS_ANSWERED_HOWEVER_IT_ARRIVED` | — | W | to fit |
| `SHAPE_MIRRORED_KEEPS_ITS_STAMP` | — | W | to fit |
| `SHAPE_NEWER_DATA_REFUSED` | — | W | to fit |
| `SHAPE_REFUSED_OBJECT_LEFT_BEHIND` | — | W | to fit |
| `SHAPE_RESHAPED_IN_PLACE` | — | W | to fit |
| `SHAPE_RESHAPE_RESUMABLE` | — | W | to fit |
| `SHAPE_RESHAPE_TAKES_THE_SEARCH_NARROWING` | — | W | to fit |
| `SHAPE_STAMP_OUTLIVES_ITS_PACK` | — | W | to fit |
| `SHAPE_STOCK_COUNTED` | — | W | to fit |
| `SHAPE_TOO_NEW_IS_ITS_OWN_ANSWER` | — | W | to fit |
| `TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `TEN_A_TYPE_DECLARES_ITS_DOMAIN` | `TheVersionIsMeasuredIT` | W | to fit |
| `VAL_AN_INVARIANT_IS_ANSWERED_IN_THE_DATABASE` | — | W | to fit |
| `VAL_AN_INVARIANT_IS_COMPILED_WHEN_IT_ARRIVES` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `VAL_AN_INVARIANT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `VAL_A_THIRD_ANSWERER_READS_THE_INDEX` | `TheVersionIsMeasuredIT`, `AWriteIsJudgedFromTheIndexIT` | W | to fit |
| `VAL_DIVERGENCE_IS_MEASURED_OVER_THE_VERSION` | `TheVersionIsMeasuredIT` | W | to fit |
| `VAL_THE_DATABASE_ANSWER_IS_ADVISORY_UNTIL_IT_IS_NOT` | `TheVersionIsMeasuredIT` | W | to fit |
| `VAL_THE_INDEX_IS_A_PROJECTION_OF_THE_EXPANDED_ROWS` | `TheVersionIsMeasuredIT` |  | to fit |
| `VAL_TIER_ONE_IS_ANSWERED_IN_THE_DATABASE` | `ADefinitionIsExpandedWhenItArrivesIT` | W | to fit |
| `VER_AN_ELEMENT_THAT_DOES_NOT_TRANSLATE_IS_REFUSED_BY_NAME` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `VER_AN_EXPRESSION_THAT_YIELDS_A_VALUE_IS_COMPILED` | without a world: WhatTheSearchCompilerAcceptsTest |  | assert where the story passes it |
| `VER_A_DEFINITION_IS_EXPANDED_WHEN_IT_ARRIVES` | `ADefinitionIsExpandedWhenItArrivesIT` |  | to fit |
| `VER_BALLOT_RECORDED_PER_VERSION` | without a world: DefinitionsTravelWithTheFaceTest, OneFaceOverTheElementModelTest … |  | assert where the story passes it |
| `VER_BALLOT_SERVED_AS_AUTHORED` | without a world: OneFaceOverTheElementModelTest, WhatWasStoredReachesTheReaderTest |  | assert where the story passes it |
| `VER_CONVERSION_RUNS_BOTH_WAYS` | without a world: WhatConversionCarriesBothWaysIT |  | assert where the story passes it |
| `VER_DEFINITIONS_INDEXED_WITHOUT_THE_TOOLCHAIN` | without a world: DefinitionsAreIndexedWithoutTheToolchainTest |  | assert where the story passes it |
| `VER_DEFINITIONS_LIVE_IN_A_SCHEMA_OF_THEIR_OWN` | `TheVersionIsMeasuredIT` | W | to fit |
| `VER_FACE_ROOT_HOLDS_THE_VERSION_AS_RECORDS` | — | W | to fit |
| `VER_THE_FACE_SQL_SHIPS_WITH_THE_RELEASE` | — | W | to fit |
| `VER_TRANSITION_BY_CONVERTERS` | without a world: UpgradeOnReadIT |  | assert where the story passes it |
| `VER_WHAT_THIS_FACE_CANNOT_READ_IS_REFUSED` | without a world: ATypeSaysWhatItDoesWithWhatItCannotReadTest, TheGuideRunsIT |  | assert where the story passes it |

## US-DBO-VENDOR-CHANGE

Story class: `TheClinicChangesVendorIT` — exists; today databases opened directly, no runtime.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `TheClinicChangesVendorIT` (story class) | databases opened directly, no runtime | 6 | 0 | moved, harness class deleted |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `MNT_BACKUP_IS_EXPORT` | — |  | `1` `everythingLeavesSealed` |
| `MNT_OWNER_KEY_ENCRYPTION` | — |  | `1` `everythingLeavesSealed` |
| `MNT_SNAPSHOT_CONSISTENT` | — |  | `1` `everythingLeavesSealed` |
| `CORE_REINDEX_IS_AN_OPERATION` | — |  | `3` `itRestoresElsewhereWithTheSameIdentities` |
| `MNT_PORTABLE_STATE_EXPORT` | — |  | `3` `itRestoresElsewhereWithTheSameIdentities` |
| `MNT_HISTORY_BY_SCHEMA` | — |  | `4` `historyIsRestoredByChoice` |
| `CORE_A_SEPARABLE_DOMAIN_HAS_A_SCHEMA_OF_ITS_OWN` | without a world: ASeparableDomainIsMovedOnItsOwnIT |  | assert where the story passes it |
| `MNT_ACCEPTED_ROOT_RECORDED` | without a world: ExportStreamsIT |  | assert where the story passes it |
| `MNT_ARCHIVE_ROOT_OVER_CONTENTS` | without a world: ArchiveAttestationIT |  | assert where the story passes it |
| `MNT_ATTESTATION_READS_AS_FHIR` | without a world: ArchiveProvenanceIT |  | assert where the story passes it |
| `MNT_BOTH_PARTIES_ATTEST` | without a world: ArchiveAttestationIT |  | assert where the story passes it |
| `MNT_IMPORT_REFUSES_UNATTESTED` | without a world: ArchiveAttestationIT, ArchiveKindIT … |  | assert where the story passes it |
| `POL_POLICY_REPLAY_ON_RESTORE` | without a world: PolicyIT |  | assert where the story passes it |

## US-DBO-EDGE-ROUNDTRIP

Story class: `WorkLeavesTheClinicAndComesBackIT` — exists; today shared.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `WorkLeavesTheClinicAndComesBackIT` (story class) | shared | 23 | 13 | moved, harness class deleted |
| `ALaneOverHttpIsIndistinguishableIT` | shared | 5 | 4 | deleted |
| `ARouterHoldsTheClaimIT` | shared | 3 | 3 | deleted |
| `AFaceAuthoredRunReachesTheLaneIT` | shared | 1 | 1 | deleted |
| `AHostHoldsALaneByInstallingABundleIT` | osgi+own (container) | 2 | 1 | stays — a second container |
| `ALaneOverTheStreamIsIndistinguishableIT` | own (deployment) | 2 | 1 | deleted; legs `4`, `5` of `AParticipantHoldsItsLaneOnTheStreamIT` |
| `ALargePayloadTravelsByReferenceIT` | own (whole plane) | 2 | 1 | deleted; legs `1`, `2` of `AParticipantHoldsItsLaneOnTheStreamIT` |
| `AParticipantOffersItsKeyAtEnrolmentIT` | shared | 1 | 1 | deleted |
| `ARunsTrailIsChainedFromTheTaskIT` | shared | 1 | 1 | in story but for the pruned-trail leg |
| `MandatoryStepsClassifyIncidentsIT` | own (sweep) | 1 | 1 | deleted; walked in `BringUpUnderStrainIT` |
| `NothingReadableLandsInTheSubstrateIT` | own (whole plane) | 2 | 1 | deleted; legs `6`, `7` of `AParticipantHoldsItsLaneOnTheStreamIT` |
| `OneRunIsOneChainAcrossTwoProcessesIT` | shared | 1 | 1 | deleted |
| `WorkTravelsSealedIT` | shared | 2 | 1 | deleted |
| `AStreamLaneIsToldItHasWorkIT` | own (deployment) | 1 | 0 | deleted; leg `3` of `AParticipantHoldsItsLaneOnTheStreamIT` |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `PROC_MILESTONES_ARE_DECLARED` | — |  | `1` `theBenchIntroducesWhatItPerforms` |
| `PROC_STEPS_ARRIVE_BY_INTRODUCTION` | — |  | `1` `theBenchIntroducesWhatItPerforms` |
| `PROC_STEP_DECLARES_ITSELF` | — |  | `1` `theBenchIntroducesWhatItPerforms` |
| `PROC_STEP_DECLARES_ITS_SLOTS` | — | W | `1` `theBenchIntroducesWhatItPerforms` |
| `PROC_INTRODUCTION_GRANTS_NOTHING` | — |  | `2` `introducingAStepGrantsNothing` |
| `PROC_WORK_IS_AUTHORED_ON_THE_SURFACE` | — | W | `2` `introducingAStepGrantsNothing` |
| `PROC_RUN_HAS_A_RECORD` | — |  | `3` `theClinicAuthorsTheAssay` |
| `PROC_RUN_INPUTS_FILL_THE_SLOTS` | — | W | `3` `theClinicAuthorsTheAssay` |
| `PROC_TASK_CARRIES_THE_INPUTS` | — | W | `3` `theClinicAuthorsTheAssay` |
| `PROC_CLAIM_IS_THE_INTERSECTION` | — | W | `5` `whatItMayTakeIsTheIntersection` |
| `PROC_ENTITLEMENT_IS_DECLARED_NOT_DEFAULTED` | — | W | `5` `whatItMayTakeIsTheIntersection` |
| `PROC_EXECUTOR_DECLARES_ITSELF` | — | W | `6` `takingItSaysWhoHoldsIt` |
| `PROC_RUN_NAMES_THE_STEP_VERSION` | — |  | `6` `takingItSaysWhoHoldsIt` |
| `PROC_RUN_NAMES_WHAT_RAN_IT` | — | W | `6` `takingItSaysWhoHoldsIt` |
| `PROC_RUN_SAYS_WHO_HOLDS_IT` | — |  | `6` `takingItSaysWhoHoldsIt` |
| `PROC_INPUTS_ARRIVE_WITH_THE_WORK` | — | W | `7` `theInputsArriveWithTheWork` |
| `PROC_PROGRESS_NAMES_THE_MILESTONE` | — |  | `8` `progressNamesADeclaredMilestone` |
| `PROC_REPORT_THROUGH_DECLARED_ACTIONS` | — | W | `9` `aVerbTheStepDoesNotDeclareIsRefused` |
| `PROC_DONE_MEANS_DONE` | — | W | `10` `aFailureIsReleasedNotClosed` |
| `PROC_FAILURE_IS_RELEASED` | — |  | `10` `aFailureIsReleasedNotClosed` |
| `PROC_RUN_ENVELOPE_DISCLOSES_STATE_NOT_SUBJECT` | — |  | `11` `theEnvelopeDisclosesStateAndNotSubject` |
| `POL_TRAVEL_AND_ACCESS_ARE_DIFFERENT_ENTRIES` | — | W | `12` `theTrailTellsCarryingFromReading` |
| `WF_HOPS_AUDITED` | — | W | `12` `theTrailTellsCarryingFromReading` |
| `POL_A_RUNS_TRAIL_IS_CHAINED_FROM_THE_TASK` | `ARunsTrailIsChainedFromTheTaskIT` | W | to fit |
| `PROC_AUTOMATION_IS_A_DECLARED_SWITCH` | without a world: ASwitchSaysWhetherAStepIsAutomatedHereIT |  | assert where the story passes it |
| `PROC_AUTOMATION_IS_DECLARED` | without a world: ExecutorResolutionTest |  | assert where the story passes it |
| `PROC_A_FAULT_THE_CALLER_IS_NOT_TOLD_IS_STILL_RECORDED` | without a world: AFailedClaimIsNotALostRaceTest, AFailedVerbSaysWhyItFailedTest |  | assert where the story passes it |
| `PROC_A_HOST_HOLDS_A_LANE_WHEREVER_IT_IS` | `AHostHoldsALaneByInstallingABundleIT`, `AParticipantHoldsItsLaneOnTheStreamIT` | W | US-DBO-ON-THE-STREAM |
| `PROC_A_LANE_OVER_THE_STREAM` | `AHostHoldsALaneByInstallingABundleIT`, `AParticipantHoldsItsLaneOnTheStreamIT` |  | US-DBO-ON-THE-STREAM |
| `PROC_A_PARTICIPANT_OFFERS_ITS_KEY_AT_ENROLMENT` | — | W | to fit |
| `PROC_A_RUN_ANSWERS_ONLY_FOR_ITS_INPUTS` | `TheGuideRunsIT` | W | `31` `aRunReachesWhatItWasStartedOver` |
| `PROC_A_RUN_CONTEXT_ENDS_WITH_ITS_RUN` | `TheGuideRunsIT` | W | `32` `theWayInClosesWhenTheWorkEnds` |
| `PROC_A_RUN_NAMES_WHAT_IT_PRODUCED` | without a world: ContentChangesInsideWorkIT, TheLaneHasTwoBoundsIT |  | assert where the story passes it |
| `PROC_A_STEP_GRANTS_THE_RIGHT_TO_OVERRIDE` | without a world: ExecutorResolutionTest |  | assert where the story passes it |
| `PROC_A_WAKE_UP_IS_NOT_HOW_WORK_ARRIVES` | `AParticipantHoldsItsLaneOnTheStreamIT` |  | US-DBO-ON-THE-STREAM |
| `PROC_CATALOGUE_IN_STORE` | — |  | PLANNED — nothing cites it |
| `PROC_CLOSE_BY_RE_EVALUATION` | without a world: ConfigAppliesAsASweepIT, RunsAreRecordsIT |  | assert where the story passes it |
| `PROC_CONTENT_CHANGES_INSIDE_WORK` | without a world: ContentChangesInsideWorkIT |  | assert where the story passes it |
| `PROC_CORRELATION_TRAVELS_OPAQUE` | without a world: RunsAreRecordsIT |  | assert where the story passes it |
| `PROC_DOMAIN_CODE_FILTER` | — |  | PLANNED — nothing cites it |
| `PROC_ESCALATION_BY_FAILURE_CLASS` | without a world: RunsAreRecordsIT |  | assert where the story passes it |
| `PROC_EXECUTOR_RESOLUTION_IS_DETERMINISTIC` | without a world: ExecutorResolutionTest |  | assert where the story passes it |
| `PROC_FALL_THROUGH_IS_COUNTABLE` | without a world: ExecutorResolutionTest |  | assert where the story passes it |
| `PROC_IDENTITY_IS_REASSEMBLED_AT_THE_TENANT` | without a world: IdentityIsPutBackTogetherAtTheTenantIT |  | assert where the story passes it |
| `PROC_LANE_IS_A_TENANT_SERVICE` | `TenantOsgiIT` | W | to fit |
| `PROC_MANDATORY_STEPS_CLASSIFY_INCIDENTS` | `BringUpUnderStrainIT` | W | to fit |
| `PROC_ONE_ID_ONE_DEFINITION` | without a world: StepsArriveByIntroductionIT |  | assert where the story passes it |
| `PROC_ONE_PARENT_NEVER_ACROSS_A_BOUNDARY` | — |  | PLANNED — nothing cites it |
| `PROC_REFUSED_IS_NOT_UNANSWERED` | — | W | to fit |
| `PROC_RUN_KINDS` | without a world: RunsAreRecordsIT |  | assert where the story passes it |
| `PROC_RUN_TALLY_AND_ITEM_OUTCOMES` | without a world: RunsAreRecordsIT |  | assert where the story passes it |
| `PROC_STEP_SERVICE_EMBEDDABLE` | — |  | to fit |
| `PROC_STEP_SHAPE_VALIDATION` | without a world: StepsAreDeclaredIT |  | assert where the story passes it |
| `PROC_TASK_SAYS_WHERE_THE_WORK_IS` | without a world: MilestonesOnTheCheckpointIT |  | assert where the story passes it |
| `PROC_THE_ROUTER_HOLDS_THE_CLAIM` | — | W | to fit |
| `PROC_TRACE_JOIN` | without a world: PolicyIT |  | assert where the story passes it |
| `PROC_TRACE_RIDES_THE_LANE` | — | W | to fit |
| `PROC_WORK_TRAVELS_SEALED` | — |  | to fit |
| `WF_CONTENT_FREE_PLATFORM_PLANE` | `AParticipantHoldsItsLaneOnTheStreamIT` | W | US-DBO-ON-THE-STREAM |
| `WF_PLATFORM_COORDINATED_HOPS` | — |  | PLANNED — nothing cites it |
| `WF_POSTGRES_SUBSTRATE` | without a world: SubscriptionsIT |  | assert where the story passes it |
| `WF_TWO_PLANES` | `AParticipantHoldsItsLaneOnTheStreamIT` |  | US-DBO-ON-THE-STREAM |

## US-DBO-FLEET-HEALTH

Story class: `AnOperatorReadsAndSteersTheFleetIT`, on the world.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `AnOperatorReadsAndSteersTheFleetIT` (harness class) | own (deployment) | 14 | 12 | moved; the two-node network map stays as `ARollingUpgradeReadsAsOneStepIT` |
| `ADeploymentRecordsWhatItWasToldToServeIT` | own (sweep) | 6 | 3 | deleted |
| `ADeclarationIsAppliedThroughTheFaceIT` | shared | 6 | 2 | deleted |
| `ATenantDeclaredDifferentlyIsNoticedIT` | own (sweep) | 3 | 2 | deleted |
| `AChangeCanBeAskedAboutBeforeItIsMadeIT` | own (deployment) | 1 | 1 | deleted |
| `ADeclarationNamesWhatTheSameApplyCreatesIT` | shared | 1 | 1 | deleted |
| `ATenantThatIsNotUpSaysWhyIT` | own (sweep) | 1 | 1 | deleted; storage not yet arrived is walked in `BringUpUnderStrainIT`, the halfway failure is fleet leg `19` |
| `AZoneHandsOverItsContentIT` | shared | 1 | 1 | deleted |
| `WhatTheLoadedSpecificationCostsIT` | own (first boot) | 2 | 0 | stays — what a deployment is given before it starts |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `OPS_RUNTIME_SAYS_WHAT_IT_SERVES` | `BringUpUnderStrainIT` | W | `1` `aNodeSaysWhatItIsServing` |
| `PROC_A_NODE_ANSWERS_ITS_CATALOGUE` | — |  | `2` `aNodeSaysWhatItKnowsHowToDo` |
| `OPS_FLEET_IS_READ_FROM_OUTSIDE` | — | W | `3` `oneProcessReadsTheWholeDeployment` |
| `PROC_NETWORK_MAP` | — | W | `4` `theMapIsOneAnswerAcrossNodes` |
| `PROC_PRESENCE_IS_DERIVED` | — | W | `5` `aBenchAnnouncesItselfAndPresenceIsDerived` |
| `PROC_RUNNER_DECLARES_ITS_VITALS` | — |  | `5` `aBenchAnnouncesItselfAndPresenceIsDerived` |
| `PROC_A_DEPARTED_ROUTEE_IS_A_STATEMENT` | — | W | `6` `whatSitsBehindTheBench` |
| `PROC_A_ROUTED_TREE_TRAVELS_AS_A_LANE_VERB` | — | W | `6` `whatSitsBehindTheBench` |
| `PROC_A_TRACKABLE_MAY_ROUTE_OTHERS` | — | W | `6` `whatSitsBehindTheBench` |
| `PROC_NUMBERS_LEAVE_AS_LABELS_NEVER_AS_TEXT` | — | W | `7` `numbersLeaveAsLabelsAndNeverAsText` |
| `PROC_REPORTING_RUNS_WHERE_NOTHING_COLLECTS` | — | W | `7` `numbersLeaveAsLabelsAndNeverAsText` |
| `OPS_FLEET_IS_ACTED_ON_THROUGH_THE_LANE` | — | W | `8` `aWrongClosureIsUndoneThroughTheLane` |
| `PROC_CLOSED_CAN_BE_REOPENED` | — | W | `8` `aWrongClosureIsUndoneThroughTheLane` |
| `PROC_SUPERVISION_IS_ITS_OWN_ENTITLEMENT` | — | W | `8` `aWrongClosureIsUndoneThroughTheLane` |
| `OPS_MIGRATION_AS_DEPLOYMENT` | — |  | PLANNED — nothing cites it |
| `OPS_NUMBERS_LEAVE_THE_NODE` | `WhatTheLoadedSpecificationCostsIT` |  | to fit |
| `PROC_CONFIG_APPLIES_AS_A_SWEEP` | — |  | `14` `anUnreadableDeclarationIsACardForAPerson` |
| `PROC_CONFIG_READ_FROM_A_SOURCE` | — |  | `18` `aDirectoryThatCannotBeReadRefuses` |
| `PROC_CONFIG_WITHDRAWAL_IS_DECLARED` | — |  | `15` `anUnreadableSourceRetractsNothing`, `16` `aWithdrawnDeclarationLeavesTheRecord` |
| `SCAL_DURABLE_ASSIGNMENT` | — |  | PLANNED — nothing cites it |
| `SCAL_NO_SHARED_STATE_BROKER` | — |  | PLANNED — nothing cites it |
| `SCAL_SINGLE_WRITER_TENANT` | — |  | PLANNED — nothing cites it |
| `SCAL_TRANSPARENT_ROUTING` | — |  | PLANNED — nothing cites it |
| `SCAL_TWO_HOP_LOCALITY` | — |  | PLANNED — nothing cites it |
| `TEN_AN_ACTIVITY_DECLARES_WHERE_IT_APPLIES` | without a world: AnActivitySaysWhichTenantsItIsForTest |  | assert where the story passes it |
| `TEN_APPLYING_IS_ASKED_FOR_AND_RECORDED` | — | W | `17` `applyingIsAskedForByWhoeverWasGrantedIt` |
| `TEN_A_CHANGE_CAN_BE_CLASSIFIED_WITHOUT_APPLYING` | — | W | to fit |
| `TEN_A_CHANGE_IS_NOT_A_RETRACTION` | — | W | `10` `aNewTypeIsARebuildNotARetraction` |
| `TEN_A_DECLARATION_IS_A_RECORD` | — | W | `13` `aDeclarationIsARecordAndAChangeReplacesIt` |
| `TEN_A_DECLARATION_NAMES_ITS_REFERENT` | — | W | to fit |
| `TEN_A_DECLARED_SET_IS_APPLIED_AS_ONE_PASS` | — | W | to fit |
| `TEN_A_PARTNER_MANAGES_TENANTS` | — | W | to fit |
| `TEN_A_REDECLARATION_IS_NOTICED` | — |  | `8`–`12`, from `aTenantServingWhatWasDeclaredSaysSo` |
| `TEN_A_REFUSED_DECLARATION_IS_SAID_ONCE` | without a world: ARefusedDeclarationIsSaidOnceTest |  | assert where the story passes it |
| `TEN_A_STALE_INDEX_IS_REMEMBERED_UNTIL_IT_IS_REBUILT` | without a world: AStaleIndexComesBackTest |  | assert where the story passes it |
| `TEN_A_TENANT_COMES_UP_FROM_THE_FACE_IMAGE` | `WhatTheLoadedSpecificationCostsIT` |  | to fit |
| `TEN_FAIRNESS_QUOTAS` | — |  | PLANNED — nothing cites it |
| `TEN_SERVED_FROM_WHAT_WAS_APPLIED` | — | W | `15` `anUnreadableSourceRetractsNothing` |
| `TERM_EVERY_TENANT_ANSWERS` | — | W | to fit |
| `TERM_NATIVE_FORM` | — |  | to fit |

## US-DBO-FLEET-STEP

Story class: `OneStepIsPerformedForEveryTenantIT` — on the world. Hogwarts and a clinic the story declares, with `mom`'s `fleet.directory.check` and the serving sample's bean performing it.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `OneStepIsPerformedForEveryTenantIT` (story class) | world | 12 | 12 | in story |
| `ABeanIsFoundRatherThanWiredIT` | own (deployment) | 4 | 4 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `OneBeanPerformsForEveryTenantIT` | shared | 3 | 3 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `AProcessorIsEnrolledPerTenantIT` | own (deployment) | 2 | 2 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `ATenantReadsWhatIsOpenedOfItsDataIT` | shared | 2 | 2 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `ASlotIsReferredOrGivenIT` | shared | 1 | 1 | deleted |
| `AStepCodeBelongsToOneLevelIT` | own (sweep) | 1 | 1 | deleted |
| `ATenantAdmitsOrDeclinesWhatIsDoneToItIT` | own (deployment) | 1 | 1 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `AnUnauthorisedRowObeysItsPostureIT` | own (deployment) | 1 | 1 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `DeclaringAStepPreparesItsSubstrateIT` | own (deployment) | 1 | 1 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `TheWritebackPassesTheTenantsRulesIT` | shared | 1 | 1 | deleted; walked in `AStepIsRunForTheFleetIT` |
| `TheJoinerOffersEveryTenantsWorkIT` | shared | 1 | 0 | deleted |

| Promise | Proven now by | W | Leg in the story class |
|---|---|---|---|
| `PROC_AN_APPLICATION_STEP_IS_THE_DEPLOYMENTS_TO_DECLARE` | `ADeploymentDeclaresItsOwnStepsTest` |  | `1` `aClinicMayNotOfferTheDeploymentsStep`, `4` `theHospitalIsAskedForACheck` |
| `PROC_AN_UNAUTHORISED_ROW_OBEYS_ITS_POSTURE` | `AStepIsRunForTheFleetIT` | W | `11` `whatRanUnauthorisedIsNamed`, `12` `theClinicAuthorisesWhatItRead` (processed and named); the other two postures not on the world |
| `PROC_A_BEAN_IS_FOUND_RATHER_THAN_WIRED` | `AStepIsRunForTheFleetIT` | W | `6` `theBeanPerformsTheHospitalsCheck` |
| `PROC_A_CONSUMER_TAKES_ONLY_ITS_OWN_STEPS` | `AStepIsRunForTheFleetIT` | W | none: not on the world |
| `PROC_A_DISAGREEMENT_IS_AN_INCIDENT_NOT_A_REFUSAL` | `AStepIsRunForTheFleetIT` | W | none: `mom` declares no router that opens what it said it would not |
| `PROC_A_FLEET_PERFORMER_IS_HANDED_ITS_OBJECTS` | `AStepIsRunForTheFleetIT` | W | `6` `theBeanPerformsTheHospitalsCheck` |
| `PROC_A_PARTICIPANT_ASKS_FOR_WORK_IT_NEED_NOT_PERFORM` | `AStepIsRunForTheFleetIT` | W | `4` `theHospitalIsAskedForACheck` |
| `PROC_A_PROCESSOR_IS_ENROLLED_PER_TENANT` | `AStepIsRunForTheFleetIT` | W | none: no configuration |
| `PROC_A_REFERENCE_MAY_BE_A_SEARCH` | `AStepIsRunForTheFleetIT` | W | `4` `theHospitalIsAskedForACheck`, `5` `aSearchMustNameOne` |
| `PROC_A_SLOT_IS_REFERRED_OR_GIVEN_AND_MAY_REPEAT` | — | W | `4` `theHospitalIsAskedForACheck`, `9` `allThreeShapesArrive` |
| `PROC_A_STEP_CODE_BELONGS_TO_ONE_LEVEL` | — | W | `1` `aClinicMayNotOfferTheDeploymentsStep`, `2` `renamingItIsTheWayIn` |
| `PROC_A_TENANT_ADMITS_OR_DECLINES_WHAT_IS_DONE_TO_IT` | `AStepIsRunForTheFleetIT` | W | `13` `theClinicDeclines` |
| `PROC_A_TENANT_AUTHORISES_A_REGISTER_AND_SEES_IT_CHANGE` | `WhatARegisterSaysTest` (posture in the digest) | | `12` `theClinicAuthorisesWhatItRead` |
| `PROC_A_TENANT_READS_WHAT_IS_OPENED_OF_ITS_DATA` | `AStepIsRunForTheFleetIT` (a router is not on it) | W | `10` `theClinicReadsItsRegister`, `13` `theClinicDeclines` |
| `PROC_DECLARING_A_STEP_PREPARES_ITS_SUBSTRATE` | `AStepIsRunForTheFleetIT` | W | `3` `theStepHasSomewhereForItsWork` |
| `PROC_ONE_BEAN_PERFORMS_FOR_EVERY_TENANT` | `AStepIsRunForTheFleetIT` | W | `7` `theSameBeanPerformsTheClinicsCheck` |
| `PROC_THE_JOINER_OFFERS_EVERY_TENANTS_WORK` | — |  | `6`, `7`, `8` `readingAgainOffersOnce` |
| `PROC_THE_WRITEBACK_PASSES_THE_TENANTS_RULES` | `AStepIsRunForTheFleetIT` | W | `6` `theBeanPerformsTheHospitalsCheck` |

## Classes that cite no promise

Each is read before it is moved or deleted: a class that proves nothing the catalogue names is either a measurement whose finding is recorded elsewhere, a fold into a story leg under a promise it should have cited, or a deletion.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `AShutdownIsQuietIT` | own (lifecycle) | 0 | 0 | deleted; walked in `BringUpUnderStrainIT` |
| `ATenantGoingAwayIsNotOneThatFailedIT` | own (lifecycle) | 0 | 0 | deleted; walked in `BringUpUnderStrainIT` |
| `ATenantsAuthorityIsOnWhatItPublishesIT` | shared | 0 | 0 | deleted |
| `AnArchiveCanBeGivenBackIT` | shared | 0 | 0 | deleted |
| `AskingAboutRecordsIT` | shared | 0 | 0 | deleted |
| `EngineVocabularyDoesNotCollideWithItselfIT` | own (sweep) | 0 | 0 | deleted; walked in `BringUpUnderStrainIT` |
| `FaceRefusalIT` | own (deployment) | 0 | 0 | deleted; walked in `BringUpUnderStrainIT` |
| `OneVocabularyTwoBindingsIT` | shared | 0 | 0 | deleted |
| `ProfilesArrivingOutOfBandTakeEffectIT` | own (sweep) | 0 | 0 | deleted |
| `ProvisionedPdiCoarsensIT` | shared | 0 | 0 | deleted |
| `TheCastComesUpFromTheSamplesWorldIT` | shared | 0 | 0 | deleted |
| `WhatATenantNeedsFromAFaceIsDerivedIT` | shared | 0 | 0 | deleted — folded into `TheVersionIsMeasuredIT` |
| `WhatAnEnvelopeWouldNeedFromTheIndexIT` | shared | 0 | 0 | deleted |
| `WhatTheRestOfACheckerWouldNeedIT` | shared | 0 | 0 | deleted — folded into `TheVersionIsMeasuredIT` |

## Classes the world may not hold

Left to the end, after every story has moved (step 7). What still does not fit then gets a technical user story.

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `ADeploymentReadsItsDeclarationsFromWhereItWasToldTest` | own — unit test over stub provisioners | 0 | 0 | later |
| `ADriverBundleContributesAStepIT` | osgi — a bundle installed into Felix | 1 | 0 | later |
| `AHostHoldsTheWebTierAndTheRuntimeMountsOnItTest` | own — unit test over stub provisioners | 0 | 0 | later |
| `EmbeddedContainerIT` | osgi — OSGi ratchet | 2 | 1 | later |
| `FelixPackagingIT` | osgi — Felix packaging, no tenant | 0 | 0 | later |
| `NumbersLeaveTheContainerIT` | osgi — telemetry leaving the container | 1 | 0 | later |
| `OperatorIT` | own+process (sweep) — a k3s cluster the operator drives | 4 | 3 | later |
| `ServerDistIT` | osgi+process — the distribution, started as a process | 5 | 5 | later |
| `TenantOsgiIT` | osgi — OSGi ratchet | 3 | 3 | later |
| `TheGuideRunsIT` | process — the guide against the pinned image | 66 | 40 | deleted, with the guide's own tests; the five it alone proved are story legs |
| `TheStackSatisfiesAStatedRangeIT` | osgi — a bundle stack resolved in Felix, no tenant | 0 | 0 | later |

## What stays, and the technical story each group becomes

Everything below was looked at against the one world and does not fit it, for
a reason a reader can check. Each group is a candidate technical story; none
is a class kept by habit. A class trimmed to the legs that need its own
arrangement is listed with only those legs.

**The version measured.** In-process libraries compared with the carried
corpus or with the database's answer over a whole version — a property of a
release, not of a deployment, and reachable only by putting the face's own
libraries beside a test. The group is now the technical story
[US-DBO-VERSION-MEASURED](../../arc42-003-context/user-stories/us-dbo-version-measured.md),
walked on the harness's shared runtime in `TheVersionIsMeasuredIT`, which folds
in `OneEnvelopeFromEitherSetOfParametersIT`, `TwoFacesOverOneDocumentIT`,
`AnIndexBuiltFromTheRowsSaysWhatThePackagesSayIT`,
`TheTwoAnswersAreComparedOverTheVersionIT`, `AValueIsTheKindOfThingItIsDeclaredToBeIT`,
`WhatAProfilePinsIsAnsweredFromTheIndexIT`, `WhatATenantNeedsFromAFaceIsDerivedIT`,
`WhatTheRestOfACheckerWouldNeedIT`, `ADefinitionMovesOnItsOwnFeedIT` (the
definitions feed and the misplaced-type refusal, neither behind a door) and
`AnUpstreamSelectsByNameIT` (the feed's selection, not behind a door). Two
classes of the group stay outside it: `AWriteIsJudgedFromTheIndexIT` (a global
dial) and `TheEnvelopeIsTheSameFromEitherSideIT` (its two legs that build a
store with a database extractor in-process).

**Bring-up made to go wrong.** A provisioner that is behind or races, a
catalogue or a face registry built by hand, a process stopped mid-sync, the
database's own log. The group is now the technical story
[US-DBO-BRING-UP-UNDER-STRAIN](../../arc42-003-context/user-stories/us-dbo-bring-up-under-strain.md),
walked in `BringUpUnderStrainIT` on one runtime scripted by tenant code, with
a node of its own for the shutdown leg and a racing node inside the teardown
leg. It folds `SeveralTenantsDeclaredAtOnceComeUpTogetherIT`,
`AStreamKeepsMovingWhileATenantComesUpIT`, `ATenantThatIsNotUpSaysWhyIT`
(storage not yet arrived), `ATenantGoingAwayIsNotOneThatFailedIT`,
`FaceRefusalIT`, `AShutdownIsQuietIT`, `MandatoryStepsClassifyIncidentsIT` and
`EngineVocabularyDoesNotCollideWithItselfIT`. `ADefinitionIsExpandedWhenItArrivesIT`
stays where it is (trimmed to the two legs that delete rows behind the store
and force a rebuild).

**What a deployment is given before it starts.** Face images, a secret it
chose, the brokers it federates to and their secrets. The group is the
technical story US-DBO-A-DEPLOYMENT-IS-EQUIPPED, walked by
`ADeploymentIsEquippedBeforeItStartsIT` on one runtime of its own: one image
directory set between bring-ups, one authority configuration carrying both the
hub's upstream and the zone's broker secrets, one stub server for every
broker, and one r4 face root under both the image legs and the projection
legs. It folds `AFaceIsCutOnceAndBroughtUpFromIT`,
`ATenantComesUpFromTheFaceImageIT`, `AZoneReachesAnotherFaceThroughOneProjectionIT`,
`ADeploymentPresentsItsOwnCredentialIT`, `ZoneIT` and `FederatedAuthIT`, which
are deleted. The hub legs could move to Rowling Land with a world change rather
than a test change: `rl` brokers its members but declares no person identifier
domain, so a member cannot resolve the hub's subject. Two classes stay on their
own: `WhatTheLoadedSpecificationCostsIT`, which measures the specification's
cost in a fresh process, and `ATenantSubscribesToItsVersionIT` (trimmed to the
build counters, which live inside the container).

**A deployment with a substrate.** The lane over the stream, and what lands
on the shared plane, are now the technical story US-DBO-ON-THE-STREAM, walked
by `AParticipantHoldsItsLaneOnTheStreamIT` on one runtime with one substrate
and one tenant, because its legs read the whole plane after their own traffic.
`AHostHoldsALaneByInstallingABundleIT` stays apart, because it installs the
lane into a Felix of its own. Rowling Land first given a substrate ran every
story about two and a half times slower, because every tenant's door on the
stream launched a durable-workflow instance of its own. A door now opens only
when a participant that signs its asks is enrolled on the tenant, at bring-up
or later, and Rowling Land has a substrate: St Jerome's lane is carried by it
and Hogwarts' by HTTP, one worker holding both. The story suite took about
ten minutes with it, as without. So `TheWorkArrivesOverTheSubstrateIT` is
deleted: its promise is leg `33` of the edge-roundtrip story, the same bean and
outcome over each carrier. With the world loaded, the wake-up leg also slept
through a released run — the door coalesces nudges inside 200 ms and sends
none after the window, so on a busy tenant the last run of a burst may go
unannounced until the poll. Not proven: the story's wake-up leg runs on a
quiet tenant, before any other leg has made a run of its step.

**Erasure has a door.** `POST /runtime/erase/<code>`, under a token of its
own rather than the operator's, with a reason, refusing a tenant still
declared and answering the same when asked again. US-DBO-A-TENANT-IS-ERASED is
walked on the world by `AClinicIsErasedIT`, on a clinic the story declares,
retracts and erases, and `ATenantIsErasedIT` is deleted.

**Libraries proven without a runtime.** `ARunsTrailIsChainedFromTheTaskIT`
(the pruned trail), `AnApplianceCarriesPatientDataByWorkIT` (the appliance
half of two places).

**A second container.** `AHostHoldsALaneByInstallingABundleIT` installs the
lane into a Felix of its own; it joins the OSGi classes above.

**What only the deployment's own declaration can show.** The fleet-step
story's leftovers, now the technical story
[US-DBO-A-STEP-IS-RUN-FOR-THE-FLEET](../../arc42-003-context/user-stories/us-dbo-a-step-is-run-for-the-fleet.md),
walked in `AStepIsRunForTheFleetIT`. A tenant's register and its incidents
have a door now (`/t/<code>/register`, and `/runtime/fleet` for the operator),
so reading the register, authorising it and the processed-and-named incident
are legs `10` to `13` of the fleet-step story on the world. What stays needs
the management tenant to declare more than `mom` does — a required step, one
not until approved, one withdrawn, two placed together, a named processor —
and a fleet step is declared for the whole deployment: the store cannot scope
one to tenants a leg makes, so declaring any of these on the world would change
every shared tenant. The class keeps its own runtime for those, and walks the
consumer, router and writeback legs on the harness's shared deployment.

## The assemblies and samples proving themselves

The sample tests (`TheApplicationServesItsWorldIT`, `ABeanOfThisApplicationPerformsTheWorkIT`, `TheWorkArrivesOverTheSubstrateIT`) are folded into the stories, which are the sample application's tests. The assembly tests stay: they prove the wrapper (item 028).

| Class | Boots | Promises | W | Status |
|---|---|---|---|---|
| `ABeanIsAStepThisApplicationPerformsIT` | spring | 0 | 0 | stays |
| `ABeanOfThisApplicationPerformsTheWorkIT` | spring | 6 | 3 | deleted |
| `ATestDeclaresTheDeploymentItRunsAgainstIT` | spring | 0 | 0 | deleted; a leg of US-DBO-TENANT-OPENING declares a clinic held only in memory |
| `AddingThisJarMakesTheApplicationANodeIT` | spring | 0 | 0 | stays |
| `AnApplicationThatAddedThisJarServesATenantIT` | spring | 0 | 0 | stays |
| `TheApplicationServesItsWorldIT` | spring | 1 | 1 | deleted |
| `TheContainerComesUpInsideTheApplicationIT` | spring | 0 | 0 | stays |
| `TheWorkArrivesOverTheSubstrateIT` | spring | 1 | 1 | deleted; leg `33` of `WorkLeavesTheClinicAndComesBackIT`, one worker holding a lane over each carrier |

