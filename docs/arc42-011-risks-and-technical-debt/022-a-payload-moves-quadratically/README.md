**Open. A payload moves through the store in time quadratic in its size: 96 KiB
in 42 seconds, 400 KiB in 632. Measured once, while building something else,
and invisible to every suite. Next: find where the square is.**

# A payload moves through the store quadratically

## What is known

Two measurements, taken while proving that work travels over the substrate:

| payload | time |
|---|---|
| 96 KiB | 42 s |
| 400 KiB | 632 s |

A little over four times the bytes for fifteen times the wait. That is the
shape of an `n²`, and at that rate a megabyte is an hour.

## Why nothing has noticed

**Every other suite uses documents a few hundred bytes long**, and at that size
`n²` and `n` are the same picture. The suite that found it is the one that
moves a payload deliberately large, because the design it was proving assumes
large payloads are normal — a document travelling to a participant, a model, an
archive.

So this is not a slow path somebody will trip over gradually. It is invisible
until a consumer sends something real, and then it is not slow, it is stopped.

## What is not known

**Where the square is.** It was measured at the outside, moving a payload
through, and nothing here says which of the layers it crosses is the one
squaring. Candidates, none of them investigated: a copy per step in the
carrier, a re-read per member while framing, a seal or a hash applied to a
growing buffer, or an accumulation in the substrate's own step recording.

**Whether it is one square or several.** Two points fit a parabola and so does
almost anything else. A third and fourth measurement would say whether this is
quadratic or something worse over a short range.

**Whether it reaches the ordinary write path**, or only the path that moves a
payload between places. Those are different defects with different urgency: the
first is every deployment, the second is the ones that move work.

## What to do

1. Take four more points — 8 KiB, 32 KiB, 200 KiB, 800 KiB — and say what the
   curve is, rather than inferring it from two.
2. Say where. A profile of one large move, and the answer is a layer rather
   than a guess from this list.
3. Decide whether the ordinary write path has it too, which is one measurement
   and settles how urgent this is.
4. Then fix it, and keep the measurement as the test — a fix proven by a green
   suite of small documents proves nothing here, which is how it stayed
   invisible.

## Why it is written down now

It was recorded inside the item about work arriving without a poll, in a
paragraph saying it was filed separately with the measurements. It was not: the
numbers existed in one place, and that place was an item marked as built and
due for deletion. So this is the filing that sentence promised, made before the
document holding it goes.
