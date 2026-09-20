**Open. Next: run the prose reviewer over the user stories, the highest count.**

# The specification is cut to the house style

The [prose rule](../../arc42-002-constraints/working-rules/comments.md)
asks for the thing stated without a contrast, and for history to stay in
the commit message and the decision record. Measured as contrast phrases
per thousand words, the user stories run at 9 to 13, the crosscutting
concepts at 9 to 10, and the working rules at 7.

The `prose-reviewer` agent returns the cuts for a page. This item runs it
over the tree, one section per change, and the count is the measure of
done.

## Steps

1. The user stories.
2. The crosscutting concepts and their pattern pages.
3. The guide chapters, unless item 002 rewrites them first.
4. Everything else under `docs/`, then delete this item.
