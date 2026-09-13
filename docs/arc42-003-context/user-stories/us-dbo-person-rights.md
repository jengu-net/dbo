# US-DBO-PERSON-RIGHTS — what a person can ask for, and what being forgotten actually does

> Liis Tamm was recorded in the clinic in
> [US-DBO-CLINICAL-RECORD](us-dbo-clinical-record.md). This is what she can
> ask for afterwards.
>
> The shape of the answer is the point. Her identifying data was
> encrypted under a key of her own before it ever reached the engine, so
> **erasure is not a delete**: destroying that key makes every copy of it
> pseudonymous at once — the history, the archives, the appliance that
> replicated it last week — without anybody chasing rows across systems
> that may not even be reachable. What is left is a record that says
> something happened and cannot say to whom.
>
> Two things surprised the engineer who wrote this down, and both are
> the store being stricter than expected. A credential that may write
> every type in the clinic still reads Liis back **pseudonymously**,
> because what a recipient sees is declared rather than inferred from
> how much they can write. And looking her up by her national identifier
> is refused outright until the caller says what it is *for*.

## The scene

Ines's platform holds a request desk where patients ask for things. What it
does not hold is a second copy of anybody's identity, and what it must never
become is a system where the person who can write the most can also see the
most.

## Reading her is not the same as writing her

The clinic's own application credential may write every type there is. It
reads Liis back without her name, and with her birth date generalised to the
year rather than removed.

Generalised rather than absent is deliberate. A reader who may not identify
her may still legitimately need to know roughly when she was born, and a
store that answers "nothing" to that has made every such reader ask somebody
who can see everything.

The strict mode is the **default**, not something a surface opts into. A door
that has not thought about disclosure cannot leak by saying nothing.

## Looking somebody up is an act with a reason

Searching for Liis by her national identifier is not a query, it is an
identification. The store refuses it until the caller states a purpose, and
names the codes that would work rather than simply saying no.

That refusal is what makes the trail worth reading afterwards. "Somebody
looked her up" is not an answer anybody can act on; "somebody looked her up
for treatment, at 03:14, with this credential" is.

## Erasure is its own authority

The application credential that may write every type in the clinic **cannot**
erase anybody. Destroying the key that makes a person legible is the most
consequential thing this store does, and it is not something a broad write
grant should quietly include.

Asking with no credential at all is refused before anything is looked up, so
the request desk cannot be used to find out whether somebody is a patient
here.

## It is asked for and answered like any other work

The request comes back as a run, keyed by the person. Asking twice is the same
request rather than a second erasure — which matters, because the second ask
usually comes from somebody who did not see the answer to the first.

The run has milestones it can stop at, so a half-finished erasure says how far
it got instead of leaving somebody to guess.

## Afterwards

Her name is gone, and so is the coarse birth date that survived while she was
merely undisclosed. **Being erased and being undisclosed are different
states**, and only one of them is reversible by a better credential.

The trail survives. It still says something happened and can no longer say to
whom, which is what lets the clinic show it handled her request without that
proof itself becoming a copy of what she asked to remove.

## Staff leave differently

A clinician who leaves is deactivated rather than deleted, because who worked
here on a date is a fact about that date, and a signature that refers to
nobody is worse than one that refers to somebody who has left.

## Joins

The promises this story rests on, projected from the catalogue rather than
written here: a story claims no evidence, and a leg is what its promise's own
citations say it is.

<!-- story:begin — generated from the promise catalogue; do not edit. Regenerate: ./gradlew :core:harness:promiseProjection -->

| Promise | Says | Status |
|---|---|---|
| `REQ-DBO-PDI-STRUCTURAL-VAULT` | Identifying elements, declared per type/element, live encrypted in the tenant's person vault; the main store holds pseudonymous records and the engine reassembles full resources for authorized reads — isolation is beneath the API, not a caller discipline. | PROVEN |
| `REQ-DBO-PDI-BLIND-OPERATIONS` | Backup and restore are machinery-driven end to end over ciphertext; the operator can run the whole lifecycle without the ability to read personal data, and opening an archive outside the running system is an owner-only act. | PROVEN |
| `REQ-DBO-PDI-PLAINTEXT-IN-FLIGHT-LEAVES-NO-TRACE` | A tenant's database is part of this store's runtime, and personal data passes through it in the clear only in flight — validated, extracted, converted — never landing anywhere the person's key does not cover. The one way it could land is the server logging a statement's parameters, so every database the store is allowed to pin is pinned not to — a managed server keeps that setting for its superuser and hands this store an ordinary role, which is a server to check rather than a store that cannot run. What the sessions will actually see is checked at every bring-up rather than assumed, and an isolated tenant refuses to come up on a database that would write its people down. | PROVEN |
| `REQ-DBO-PDI-EXACT-RESOLUTION` | An exact, purpose-stated lookup on a vault-indexed value — a claimed identifier (system|value) or an indexed contact point — resolves through the vault to the records holding it, served under the caller's disclosure mode. The match runs over keyed hashes and every resolution leaves a value fingerprint in the disclosure trail; after erasure the answer is empty. Anything inexact, unsystemed, or combined with other predicates is refused, never half-answered. | PROVEN |
| `REQ-DBO-IDN-IDENTIFICATION-IS-REACHABLE` | A tenant identifies somebody through a door of its own: claims are presented and resolve to candidates rather than to an answer, a claim nobody verified is evidence for a person to weigh and never a match to act on, no candidate at all is an ordinary answer rather than a failure, a decision is recorded and shown to whoever meets the same near-match next, a binding says how strongly it was made and can be withdrawn without touching the care, and a subject who declared anonymity is refused rather than bound. The door carries its own scope, outside the resource grammar: a grant over the store's resources does not reach the act that de-anonymises somebody. | PROVEN |
| `REQ-DBO-IDN-A-DECISION-IS-EVIDENCE` | A person's conclusion about who somebody is, is kept as evidence rather than applied as a fact. It names who decided, when, and what they were looking at; one that names nobody is refused, because it could never be questioned. Undecided is a state a record lives in rather than a failure. A candidate somebody already declined comes back marked rather than hidden — hiding it would make a wrong decision permanent and invisible — and no machine silently reverses it. Decisions are append-only and scoped to the claims they were about: revising one means recording a new one. | PROVEN |
| `REQ-DBO-IDN-CLAIM-STRENGTH-BOUNDS-THE-CONCLUSION` | What a claim can conclude follows how well it is held. One cryptographically presented claim matching a single subject resolves without anybody looking; a number read off a document never resolves anybody by itself; claims pointing at different people destroy certainty rather than choosing between them; and no match at all is an ordinary answer — the person before their first visit — rather than an error. A revoked document resolves nobody, because it is in somebody else's hands, while a superseded one still finds the person whose records refer to it. A claim naming no issuing system is refused: the same digits are two people in two countries. | PROVEN |
| `REQ-DBO-IDN-ASSURANCE-IS-THE-WEAKER-OF-THE-TWO` | What an identification is worth is the weaker of how somebody authenticated now and how well the identification itself was made. A national eID presented today does not upgrade one made last year from a photocopy, and a weak assertion does not inherit a strong binding. Re-identifying to a higher standard raises it and the history keeps both; withdrawing leaves nothing to inherit; and an identification that established nothing is refused rather than recorded at no assurance. It is per identity, not per subject: two identities on one subject say nothing about each other. | PROVEN |
| `REQ-DBO-IDN-ANONYMITY-IS-DECLARED-NOT-INFERRED` | Anonymous on purpose is something a subject says, not something absence implies. Two unbound subjects are otherwise identical — one expects to be identified and the other must not be — and an intention cannot be stated by an absence, so the declaration is positive, states its basis, and is refused without one. While it stands, binding is refused rather than discouraged; declaring it over a standing identity is refused too, because the identification has to be withdrawn first rather than shadowed. Withdrawal stays available throughout, and a person may lift their own declaration. | PROVEN |
| `REQ-DBO-IDN-BINDING-IS-REVERSIBLE-AND-KEEPS-ITS-EVIDENCE` | Attaching an identity to a subject can be undone, and undoing it takes the identity without touching the care: a wrong binding put one person's records in another's, so withdrawal must always be available and must leave the clinical data alone. What is withdrawn stays answerable — that somebody was identified, and that it was undone, are both facts a regulator may ask about — so events are append-only and a mistaken withdrawal is as recoverable as a mistaken binding. A binding names who made it and why, and one subject's bindings say nothing about another's. | PROVEN |
| `REQ-DBO-IDN-WHAT-A-RECIPIENT-SEES-IS-DECLARED` | What may leave and what this particular recipient may see are different questions, and a tenant answers the second by declaring an audience: which types it is answered about at all, and what a read of one of them reveals. A type outside the declaration is absent rather than refused, because a refusal naming it would tell the recipient it exists. The mode follows the declaration rather than the request — a recipient that could ask for more would make the declaration advice — and an audience nobody declared sees nothing, because a typo in a serving surface and a partner who was removed both want silence. Naming no audience is the tenant working with its own records, and nothing about it changes. | PROVEN |
| `REQ-DBO-AUTH-FEDERATED-HUMANS` | Human authentication is federated to the configured identity broker; the authority resolves the verified national identifier to a Practitioner through the vault index and owns authorization only. Local credentials are an embedded/dev fallback, never the production path. | PROVEN |
| `REQ-DBO-AUTH-PSEUDONYMOUS-TOKENS` | Human tokens carry the practitioner's record id and SMART user scopes — no name, no national code; a captured token identifies no one. | PROVEN |
| `REQ-DBO-AUTH-ON-BEHALF-OF` | Automated processes act in the name of a human via token exchange — subject stays the practitioner, an act claim names the client, scopes attenuate; durable workflows delegate through Delegation records that outlive tokens and are revocable by ending their period. Every delegated mutation is attributable to both the process and the person. | PROVEN |
| `REQ-DBO-AUTH-NO-SUBJECT-ENUMERATION` | No authority answer distinguishes a subject that exists from one that does not — not in what it says, not in how long it takes. The authority is the only party that knows, which is why it must not say. | PROVEN |
| `REQ-DBO-PDI-RIGHTS-AS-OPERATIONS` | Access, portability and restriction are standard machinery operations over the vault join, not per-request projects. | PROVEN |
| `REQ-DBO-PDI-ERASURE-IS-A-RUN` | A person's erasure is asked for as work and answered by a run: the run is the receipt, carrying what was found, how far the erasure got and when. Asking twice finds the run that already exists rather than opening a second account of one erasure, and a person this store never held closes the run saying so — a repeated request is not an error and an unknown subject is not a refusal. A failure releases the run with its reason rather than closing it, because an erasure that read as done is the one outcome the record exists to prevent. | PROVEN |
| `REQ-DBO-PDI-ERASURE-SAYS-HOW-FAR-IT-GOT` | The erasure names the points it passes — the key destroyed, the index removed, the ledger written — so an erasure that stopped between the irreversible half and the half that makes a restore safe is visible as exactly that. It reports what it did rather than that it ran: whether a key was there to destroy tells erased apart from was-never-here, which are different answers to a data subject. | PROVEN |
| `REQ-DBO-PDI-CRYPTO-SHREDDING` | Erasure destroys the person's key: history stays byte-immutable, existing archives stay valid as files, and the person's data is cryptographically gone from live store, history, envelopes and archives at once. | PROVEN |
| `REQ-DBO-PDI-UNFINDABLE-AFTER-ERASURE` | Search indexes derived from personal elements are vault-scoped or rebuilt on shred — an erased person is unfindable, not merely unreadable. | PROVEN |
| `REQ-DBO-PDI-SHRED-LEDGER` | Erasures are recorded without personal data and re-applied on every restore before serving resumes — an old archive cannot silently resurrect an erased person. | PROVEN |
| `REQ-DBO-POL-ERASURE-COMPATIBLE` | Append-only discipline and the right to erasure coexist: shredding never rewrites a record — the record remains, the person evaporates. | PROVEN |
| `REQ-DBO-SCIM-DEPROVISION-IS-A-STATE` | Deactivation sets active=false on the person and the capacity; it is never erasure — that remains the vault's own ceremony with its own audit shape. | PROVEN |
| `REQ-DBO-TEN-ERASURE-BY-DROP` | Dropping a tenant's database and blob storage removes all its data — including durable workflow history and feed state. | PROVEN |

Coverage: {PROVEN=24} — a leg marked PLANNED cites a promise that exists and is not yet cited by any test.
<!-- story:end -->

## What the store cannot do yet

- **Erasure by dropping a tenant is proven, but a partial estate is not.** A
  tenant that lives in two places has its own erasure question, and it is
  answered by the replication lane rather than here.
- **Rights other than erasure and access are not operations.** Rectification
  and restriction are ordinary writes today, distinguishable only by what the
  trail happens to say about them.

## Open decisions

- **Whether a coarse value should survive erasure for statistical use.** It
  does not today, and the argument for changing that is real and has not been
  made.
- **What a purpose code should do beyond being recorded.** It is required and
  logged; nothing yet varies with it.
