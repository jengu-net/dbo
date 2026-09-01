# dbo-conventions — generated skills

> **Nothing in this folder is hand-edited.** Every skill is projected
> from a skill-block in a constraints document by
> `scripts/generate-skills.py`. To change one, edit the block in its
> source document and run `./gradlew generateSkills`.

These are the rules that a green build does not enforce. This store's
characteristic defect compiles, resolves, publishes and then dies on
first use, so the rules that catch it are the ones nothing else will.
Each skill is a thin projection: when it applies, what it requires,
and a link to the document that argues it.

## Skills

- **dbo-runtime-proof** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-reachability** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-comments** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-recorded-projections** ← `docs/arc42-002-constraints/working-rules.md`
- **dbo-promise** ← `docs/arc42-002-constraints/working-rules.md`

## Installing

**All of them**, which is the useful form because the set grows as
documents gain skill-blocks: add a plugin marketplace pointing at this
folder, or at the repository. The manifest is
`.claude-plugin/marketplace.json`.

**One of them:** install the single `skills/<name>/` directory.
