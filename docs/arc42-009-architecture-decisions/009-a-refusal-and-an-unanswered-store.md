**Status: Resolved.** Reflected in [processes and work](../arc42-008-crosscutting/processes-and-work/README.md).

# A refusal and an unanswered store are different

**VERDICT: a distinct exception, and the line falls where HTTP already draws
it.** Everything a `Lane` or a replication verb throws is settled except
`StoreUnreachableException`, which says the far side never spoke. A remote
lane raises that and no other kind; an in-process lane hands the host's own
store failure through unchanged, because the host holds that store and is the
only party that can say what its failure meant.

The two need opposite recoveries and used to be one exception. A refusal — the
identity did not claim that run, the step was not granted, the action was
never declared — is settled, and asking again is wrong. A store that did not
answer is transient, and asking again is the only way through. A caller that
cannot tell them apart takes the wrong one exactly when it matters: a brief
outage reads as a permissions decision, and a bench stops taking work it is
entitled to until somebody notices a queue standing still.

**The cost of the confusion is worse than a stalled bench**, which is what
made this worth its own decision rather than a note. A participant that backs
off while holding a claim keeps it only until the deadline; then the tenant's
own housekeeping releases the run and another participant takes it. So the
failure does not merely pause one bench — it moves work that was never in
trouble, and the original holder was fine the whole time.

**Why not a marker interface or a code on the existing exception.** Both were
weighed. A marker admits more than one transient type later, which sounds like
room to grow and is really room to disagree: two exceptions both meaning
"try again" is two places to keep a rule. A code on the existing exception
keeps callers writing conditionals where a `catch` would do. One type, named
for the condition, is the smallest thing that makes the distinction
unmissable at the call site.

**4xx is settled, 5xx is unanswered.** Not a convention invented here, which
is the reason to use it: it puts the ambiguous cases on the right side without
anybody guessing. A connection pool exhausted under load answers 500 and is
exactly the brief outage this exists to survive; a step that was never granted
answers 403 and will answer it again for ever. The handler is not asked to
classify its own faults, which it could only do by guessing.

**Retry is safe by construction rather than by luck.** A caller cannot always
know whether an unanswered verb was applied — a connection lost after the
request left is genuinely ambiguous — and does not need to. The participation
verbs are conditional or idempotent because delivery is at-least-once: a claim
retried wins or loses the race it would have run anyway, and a checkpoint
retried writes what it would have written.
