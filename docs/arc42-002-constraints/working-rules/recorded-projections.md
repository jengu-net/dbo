# Recorded projections

Nine artefacts are generated from something else and committed beside it.
Each has a ratchet that fails the build when what is committed disagrees
with its source.

| Artefact | Source | Re-record |
|---|---|---|
| The requirement catalogue | promise constants and `@Proving` citations | `:core:harness:promiseProjection` |
| Each user story's table of legs | the Story constant that story declares | `:core:harness:promiseProjection` |
| The exported-API ledger | public and protected signatures in exported packages | `:core:harness:apiLedger` |
| The reach ledger | production classes nothing else in production names | `:core:harness:reachLedger` |
| The promise citations | promise codes spelled out in the tree's prose | `:core:harness:promiseCitations` |
| The worlds ledger | harness classes that construct a runtime | `:core:harness:worldsLedger` |
| The module map | the build's own project dependencies | `moduleMap` |
| The skills and the trap section of `CLAUDE.md` | skill-blocks and the marked region in the constraints documents | `generateSkills` |
| The diagram SVGs | `.lini` sources and `.desc` descriptions | `siteDiagrams` |

**`./gradlew reRecord` runs all of them**, which is the command to reach for,
and the one the rule below means. The diagrams join it only when their
compiler is installed, and it says so when it is not.

**A pre-commit hook runs what your staged files make stale**, and stages the
result. The build installs it, so a fresh checkout has it without anybody
remembering. A staged document pays a python script; a staged source pays a
build; `DBO_SKIP_RERECORD=1` skips it.

Four baselines under `config/` are not on this list. A test writes them as it
runs, so they are re-recorded by running that test rather than by a task.

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
  change. `./gradlew reRecord` does all of them, and the pre-commit hook the
  build installs does the ones your staged files touch.
- MUST treat adding a constant to an exported enum, or a component to an
  exported record, as an API change.
- MUST read the ledger's report. What is GONE stops anything compiled
  against it from linking.
- MUST NOT hand-edit a generated artefact.
<!-- /skill -->
