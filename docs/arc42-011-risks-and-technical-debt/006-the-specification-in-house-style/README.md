**Open. The user stories' authored prose is cut: the four worst went 16.8, 16.1, 16.1 and 15.1 per thousand to 11.0, 9.8, 8.5 and 11.7. Next: the promise text, which is the other three quarters of those pages and is code.**

# The specification is cut to the house style

The [prose rule](../../arc42-002-constraints/working-rules/comments.md)
asks for the thing stated without a contrast, and for history to stay in
the commit message and the decision record. Measured as contrast phrases
per thousand words, the user stories run at 9 to 13, the crosscutting
concepts at 9 to 10, and the working rules at 7.

The `prose-reviewer` agent returns the cuts for a page. This item runs it
over the tree, one section per change.

## The count measures two different things

The figures above were taken over whole rendered pages, and a user story is
mostly not authored. `us-dbo-person-rights.md` is 745 words somebody wrote and
2,174 projected from the promise catalogue; the others are similar. So the
9-to-13 was a blend, and splitting it says something more useful:

| | Authored | Projected |
|---|---|---|
| `vendor-change` | 16.8 → 11.0 | 7.3 |
| `person-rights` | 16.1 → 9.8 | 12.4 |
| `two-places` | 16.1 → 8.5 | 6.9 |
| `standard-moves` | 15.1 → 11.7 | 4.1 |
| `built-or-planned` | 13.6 → 6.1 | 7.2 |
| `fleet-health` | 7.6 | 11.1 |
| `edge-roundtrip` | 6.9 | 5.3 |

The prose a person writes was worse than the blend suggested, and the order of
work was wrong: `fleet-health` reads cleanly and its promises are among the
densest. Measured to the generated marker, which is where the authored half
ends:

```bash
python3 - docs/arc42-003-context/user-stories/us-dbo-person-rights.md <<'EOF'
import re, sys
s = open(sys.argv[1]).read()
authored = s[:s.find('<!-- story:begin')]
words = len(authored.split())
found = re.findall(r'rather than|instead of|not a |not the |never a |never the '
                   r'|as against|as opposed to|unlike |it is not |is not that',
                   authored, re.I)
print(f'{len(found) * 1000 / words:.1f} per thousand over {words} words')
EOF
```

## The count is a signal and not a target

What is left in the cut stories is mostly allowed. "A custodian, **not a
reader**", "a consistent snapshot rather than whatever the writer happened to
see mid-stream", "*unresolvable* rather than *invalid*" — each names an
alternative a reader would reach for, in one clause, which is what the rule
permits. A page can sit at eleven with every contrast earning its place.

So a story is done when the contrasts that remain are ones a reader needs,
and the number is how the pages get ordered rather than what they are cut to.

## Steps

1. ~~The user stories, as their authors wrote them.~~ Done: five cut, four of
   them the worst. The five below eleven were read and left, because what they
   contrast is what a reader would ask about.
2. The promise text, which is three quarters of those pages and is **not a
   documentation change**. The words live in `DboPromises`, which is exported:
   cutting them moves the API ledger, the requirement catalogue, every story's
   joins table and the citation projection, in one commit. Sequenced after the
   prose for that reason, and worth its own reading — a promise is a
   specification sentence, and a contrast in one may be load-bearing where the
   same phrase in a story is decoration.
3. The crosscutting concepts and their pattern pages.
4. The guide chapters, unless item 002 rewrites them first. Which chapters
   there are to cut is settled by the item on asking the store a question.
5. Everything else under `docs/`, then delete this item.
