**Open. Five projections are re-recorded by hand. Next: one task that runs all five.**

# Re-recording runs before the commit

Five artefacts are generated and committed beside their source: the
requirement catalogue, the exported-API ledger, the skills and trap section,
the diagram SVGs, and the worlds ledger. Each has a ratchet, and each
ratchet speaks one commit late: the change compiles, the behaviour's tests
pass, and the build goes red on a file nobody was thinking about.

The [recorded projections rule](../../arc42-002-constraints/working-rules/recorded-projections.md)
asks for the re-record in the same change. Nothing runs it.

## Steps

1. One Gradle task, `reRecord`, that runs all five in order, so the rule is
   one command.
2. A pre-commit hook that runs it when a source of any of the five is
   staged, and stages the result. The hook is installed by the build, not
   by a person remembering to.
3. The rule's re-record bullet names the one task.
