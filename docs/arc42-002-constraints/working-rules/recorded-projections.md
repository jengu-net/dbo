# Recorded projections

Five artefacts are generated from something else and committed beside it.
Each has a ratchet that fails the build when what is committed disagrees
with its source.

| Artefact | Source | Re-record |
|---|---|---|
| The requirement catalogue | promise constants and `@Proving` citations | `./gradlew :core:harness:promiseProjection` |
| The exported-API ledger | public and protected signatures in exported packages | `./gradlew :core:harness:apiLedger` |
| The skills and the trap section of `CLAUDE.md` | skill-blocks and the marked region in the constraints documents | `./gradlew generateSkills` |
| The diagram SVGs | `.lini` sources and `.desc` descriptions | `./gradlew siteDiagrams` |
| The worlds ledger | harness classes that construct a runtime | `./gradlew :core:harness:worldsLedger` |

A ratchet runs after the push, and its failure lands one commit late: the
change compiles, the tests that cover the behaviour pass, and the build goes
red on a file nobody was thinking about. Re-recording belongs in the change
that made the artefact stale. A follow-up leaves a red commit in the history
for whoever bisects through it.

The ledger is the one that surprises, because what moves it is rarely what
the change was about. Adding a constant to a promise catalogue is an API
change: the catalogue is an exported enum. Adding a component to a record
removes its canonical constructor, which breaks anything compiled against
it. The ledger's report says what is GONE, and that is worth reading before
a consumer finds out.

<!-- skill: dbo-recorded-projections -->
```yaml
name: dbo-recorded-projections
applies-when: >-
  Changing a promise constant or citation, a public or protected signature
  in an exported package, a skill-block or marked region in a constraints
  document, a diagram source, or a harness class that builds a runtime; or
  when a build says a projection disagrees with its source.
reference: docs/arc42-002-constraints/working-rules/recorded-projections.md
```
**Rules**
- MUST re-record every generated artefact a change makes stale, in the same
  change: `promiseProjection` for the catalogue, `apiLedger` for the ledger,
  `generateSkills` for the skills and trap section, `siteDiagrams` for the
  SVGs, `worldsLedger` for the worlds ledger.
- MUST treat adding a constant to an exported enum, or a component to an
  exported record, as an API change.
- MUST read the ledger's report. What is GONE stops anything compiled
  against it from linking.
- MUST NOT hand-edit a generated artefact.
<!-- /skill -->
