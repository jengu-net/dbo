---
title: History and concurrency
eyebrow: Guide
standfirst: >-
  Every write appends a version, nothing is overwritten, and two writers cannot silently lose each other's work.
template: essay.html
---

!!! note "Not written yet"

    This page is a placeholder so the shape of the guide can be reviewed. It
    describes what belongs here, not what the store does — for that, the
    chapters that are written are the ones in this menu that do not carry this
    notice.

## What this chapter will cover

- the history bundle and reading one version by number
- ETag and If-Match: refusing a write made against a version that has moved
- what a deletion is, and why the versions before it still read
- moved out of Records, which currently carries this

## What you would otherwise have written

Every chapter ends with this section: the thing you would have built yourself
if the store did not do it, and what owning that would have cost you.
