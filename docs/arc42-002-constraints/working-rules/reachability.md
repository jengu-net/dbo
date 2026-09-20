# Reachability

A harness is the container: it constructs whatever it needs. So a type's
tests prove the type works, and prove nothing about whether the container
constructs it, registers what it writes, or hands it what a test hands it by
hand. Nothing fails when the answer is no.

Three questions, asked at the end of the work.

- **Who constructs this outside a test?** A constructor call that matches
  nothing in production sources is the finding.
- **Where does its own state live?** A surface can be mounted and correct
  while the type it writes is registered for no tenant.
- **What does the container hand it that a test hands itself?** A check that
  resolves through a catalogue, a registry or a credential is only as good as
  the one every caller passes it. A test that supplies one by hand proves the
  rule and not the wiring.

<!-- skill: dbo-reachability -->
```yaml
name: dbo-reachability
applies-when: >-
  Finishing a toolset, service, surface, lane, registered type or capability,
  or reviewing work reported complete because its tests pass.
reference: docs/arc42-002-constraints/working-rules/reachability.md
```
**Rules**
- MUST ask, at the end of the work, who constructs this outside a test. A
  `new X(` matching nothing in production sources is the finding.
- MUST ask where its state lives, and confirm the type it writes is
  registered for every tenant that needs it.
- MUST ask what the container hands each collaborator that a test hands
  itself: the catalogue, registry or credential a check resolves through.
- MUST NOT report a toolset delivered on the strength of its own tests.
<!-- /skill -->
