**Open. The user stories' authored prose is cut: the four worst went 16.8,
16.1, 16.1 and 15.1 per thousand to 11.0, 9.8, 8.5 and 11.7. The promise text
turned out not to need the same pass — measured whole it is 7.5 per thousand,
where the working rules are 7 — and the one cut worth making was a promise
reciting its neighbours rather than a style fix. Next: the crosscutting
concepts and their pattern pages.**

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

## What the promise text measured

7.5 contrast phrases per thousand over the whole catalogue's 16,040 words. The
working rules themselves run at 7 and the crosscutting concepts at 9 to 10, so
the body of promise text was already inside the band the stories were being cut
down to.

By area, and the spread is the interesting part:

| | per thousand |
|---|---|
| `IDN` identification | 17.8 → 15.1 |
| `CONT` container | 15.5 |
| `ZONE` jurisdiction | 13.5 |
| `PDI` personal data | 10.6 |
| `PROC` distributed work | 7.5 |
| `VER`, `CORE`, `SHAPE` | 5.8, 5.8, 3.3 |
| `SYNC`, `TERM`, `SCIM`, `FEED`, `SCAL` | 0 |

**And density is the wrong instrument on a promise.** It was built for pages. A
one-sentence promise carrying one clause reads as 77 per thousand — the top of
the sorted list is simply the shortest promises, and every one of them earns
its contrast: *never a data migration*, *never a process restart*, *never a
second source*. Each names the thing a reader would otherwise assume, which is
what the rule permits.

**So the reading found one cut, and it was not a style cut.**
`IDN_IDENTIFICATION_IS_REACHABLE` recited six of its neighbours in their own
words — candidates rather than an answer, evidence never a match, anonymity
refused rather than bound — and then said its own thing in the last sentence.
Five of the area's thirteen contrasts were borrowed. A promise that restates
its neighbours has no status of its own to lose, so what is left is the part
only it makes: that the surface exists, and that its scope is not the resource
grammar's.

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
2. ~~The promise text, which is three quarters of those pages.~~ Read and
   measured, and the answer was that it does not want the cut. See below. One
   promise was cut, for restating six others rather than for its contrasts,
   and it moved the requirement catalogue and one story's joins — not the API
   ledger, which records names and signatures and never the words.
3. The crosscutting concepts and their pattern pages.
4. The guide chapters, unless item 002 rewrites them first. Which chapters
   there are to cut is settled by the item on asking the store a question.
5. Everything else under `docs/`, then delete this item.
