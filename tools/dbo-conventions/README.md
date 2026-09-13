# dbo-conventions — generated skills

> **Nothing in this folder is hand-edited.** Every skill is projected
> from a skill-block in the document that owns the text, by
> `scripts/generate-skills.py`. To change one, edit the block in its
> source document and run `./gradlew generateSkills`.

Two kinds, and the difference matters when you read one. Most are
**rules a green build does not enforce**: this store's characteristic
defect compiles, resolves, publishes and then dies on first use, so
the rules that catch it are the ones nothing else will. One is a
**reference** for somebody building on the store rather than in it —
what it already provides, and what an application would otherwise
write itself. Each skill is a thin projection: when it applies, what
it says, and a link to the document that argues it.

## Skills

- **dbo-runtime-proof** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-reachability** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-comments** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-recorded-projections** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-promise** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-using** ← `docs/using-dbo.md`

## Installing

**All of them**, which is the useful form because the set grows as
documents gain skill-blocks: add a plugin marketplace pointing at this
folder, or at the repository. The manifest is
`.claude-plugin/marketplace.json`.

**One of them:** install the single `skills/<name>/` directory.
