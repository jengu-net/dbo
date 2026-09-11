# The payload seam

*How data crosses the line between a face and the engine: as bytes, framed
rather than rebuilt, parsed once.*

## Why this is its own concept

[The engine and its faces](../engine-and-faces/README.md) says a face translates and does
not act. This is the mechanics of that translation, and it is a concept rather
than an implementation note because getting it wrong is not a performance
problem — it is how a standard's model quietly becomes the engine's model.

The short version: **a payload crosses as `byte[]`, and the shape of every
operation that touches one is the same.** A type is a string, a version is a
string, a payload is bytes. Nothing in that signature names a standard, which is
what keeps the engine free of one.

Every obligation a face has that concerns a payload — encoding and decoding it,
validating it, converting it between versions — has that one shape, and it was
already written down once before anybody noticed it was a pattern.
`PayloadConverter` takes a type name, a payload version to hop from and to, and
`byte[]`. Nothing in that signature is FHIR. It is the shape the others should
take, so they are siblings of it rather than each inventing a scheme.

**The coordinates are the face, the type name, and the payload version.** Not
`Meta.profile`: the shape a resource was authored under and the format its bytes are
stored in are different axes, and conflating them breaks at the first R4→R5 move. An
interface that means one of them says which in its name.

**Bytes are the boundary, not the internal contract.** If every operation takes bytes,
the write path parses and discards once per step — deserialize, validate, extract the
envelope, hash — which is more allocation than holding a model, not less, and would make
a change made for efficiency cost efficiency. So a face mints a document handle the
engine passes back to it and never reads:

```java
public interface Payloads<D> {                  // D is the face's own model
    D read(String typeName, byte[] payload);    // parsed once
    List<String> validate(String typeName, D document);
    byte[] write(D document);
}
```

The engine holds `Payloads<?>` and never names `D`. The public API stays bytes, so §7.3
holds — no model type crosses it — while one request parses once. Byte-in, byte-out
convenience sits on top for callers that genuinely have only bytes, the converter chain
on read being the obvious one.

**The document does not travel, and that is what makes it safe.** One part of a write is
on the far side of `ObjectStore.put`: the engine extracts the searchable envelope, and
`EnvelopeExtractor` takes bytes because it is the engine's and knows no document. Sending
the document after it — in the `PutRequest`, or as an argument every decorator passes
along — puts an opaque handle in a value type or a parameter that is silently lost when
one wrapper forgets it. Worse, it can arrive *beside different bytes*: the isolation
decorator rewrites a person's payload on the way in, so an envelope built from the
document that was handed over would index the very values §14 keeps out of the clear.

So the face remembers instead. `core.face.ReadOnce` holds the document it last read
against the identity of the array it read it from, and hands it back only to a caller
reading *that* array — which the engine is, because the write carries the bytes the face
read rather than encoding the body again. A decorator that rewrites the payload misses,
and the engine reads what it is actually storing. The mechanism cannot be wrong, only
unused, and it is one document per thread, released as it is handed on.

**This is what makes the model a lens rather than a commitment.** Payload is truth and
stored bytes are never rewritten, so a face may parse into whatever represents a
resource best — the HL7 core model, something generated, something that is not FHIR at
all — and a model that round-trips imperfectly still cannot corrupt what is stored,
because what is stored is what arrived. Model choice is therefore reversible in a way it
is not for a store that persists its object graph. That property is worth protecting: it
argues for experimenting freely with representations and against ever letting one become
the persisted form.

**Resources are `byte[]`; streams belong to blobs.** A write is one transaction that
computes an envelope and links the version into a per-object hash chain, all of which
want the whole array — a streaming resource payload would be buffered internally and the
abstraction would be a lie. Blob storage is its own unbuilt front and is where streams
belong, with a contract that admits them honestly.

**Sets are framed, not re-serialised.** A searchset, a history, a transaction and a
portable export are all many resources in one document, and the engine holds their
members as bytes it has already been given. Routing them through the single-resource
codec would parse each member and render it again — N parses to emit one page, and
worse, the bytes that came out would be the model's rendering rather than the ones that
were authored. A face frames instead: it writes the document around members whose
payloads pass through untouched, and takes a document apart into members without reading
them.

```java
public interface PayloadFraming {
    record Member(String typeName, byte[] payload, MemberFacts facts) {}

    /** What a document looks like around its members. */
    record Frame(byte[] prologue, byte[] separator, byte[] epilogue) {}

    Frame frame(String frameType, FrameFacts facts);   // total, self, links: known before iterating
    void member(Member member, OutputStream out);      // wrapper + payload, straight through

    Stream<Member> unframe(InputStream document);      // transaction, batch, import
}
```

`frameType` is the face's word for what kind of document this is; `MemberFacts` is what
the engine knows about a member and the face says in its own vocabulary — which is the
ancestor-rendering obligation above, not a second one.

**The face describes the document; the caller writes it.** Taking a list of members and
returning a document would hold a whole page in memory, which contradicts the reason a set
is streamed at all. Returning a sink for the caller to fill would be better and still
wrong in kind: it is a lifecycle, and something with a lifetime spanning a loop is
something a face could hold a cursor in. So the face says what goes around the members —
a prologue, a separator, an epilogue — and how one member is spelled, and the caller
writes the prologue, then a separator before every member but the first, then the
epilogue. The only state is which member is first, and that is loop bookkeeping rather
than format knowledge: the separator comes from the face, so nothing above learns that
one format wants commas and another wants newlines.

Writing a member to a stream rather than returning its bytes is what keeps the payload
from being copied on its way out — the face writes its wrapper and then the stored bytes
straight through. Memory is one member, not one page, and the bytes a reader receives are
the bytes that were authored.

**Unframing may hand back a sequence, and the difference is the point.** The reason a face
must not pull is that a lazy read over a search is an open cursor inside a transaction, so
a face that pulls decides how long that transaction lives and what becomes of it when the
face throws halfway down a page. Unframing reads a document the caller already has — a
request body, an archive being imported — where there is no cursor and no transaction to
outlive. The rule is not that a face never pulls; it is that a face never reaches into the
store, and saying which keeps the seam honest instead of merely symmetrical.

That also puts enrichment where it has to be. Decrypting an identifying element reads the
vault, and a face must not — so the membrane sits in the engine's loop, between the
converter and the frame, and the face is handed a member that is already what a reader
should see.

**The engine's half of it is an ordinary stream.** A row arrives as bytes, which is the
unit the whole path wants, and each step is a map over it:

```java
Frame frame = face.frame("searchset", facts);
out.write(frame.prologue());
try (Stream<Member> rows = engine.page(query)) {     // cursor-backed; closes with the stream
    rows.map(converters::upgrade)                    // per object, pure
        .map(membrane::reveal)                       // reads the vault, so it is the engine's
        .forEach(separatedBy(frame.separator(), member -> face.member(member, out)));
}
out.write(frame.epilogue());
```

Nothing in that stream belongs to the face — it holds the cursor, the transaction and the
order, and the face is the terminal. Handing the stream over instead of the sink is the
same mistake in a different position, and `java.util.stream` makes it a natural thing to
type. Three hazards come with it and are worth naming: the stream owns a cursor and must
be closed, so try-with-resources is not optional; `.parallel()` is one word away and is
wrong twice over on a cursor in a transaction and on results whose order is a page; and
checked exceptions do not pass through `map`, so how a conversion failure travels is a
decision rather than a discovery. Back-pressure needs no mechanism — the terminal writes
to a blocking output, so the pipeline pulls no faster than the reader drains.

**And the outward facade has to admit it.** `FhirStoreFacade.search` and `historyBundle`
return a `String` today, so a page is assembled whole before a reader sees a byte of it.
Streaming behind a facade that materialises would buy nothing: the streaming form writes
into an output rather than returning a document, which keeps bytes at the edge as §7.3
requires and makes a search page, a portable archive and a stream between tenants one
path rather than three.

**Converters stay per object for the same reason.** A converter is a pure function from
one payload to one payload, which is what lets it compose into this loop, be tested on a
single object, and be reordered. A stream-to-stream converter would add nothing the loop
does not already give and would invite an implementation that carries state between
items, or worse, one that drives the cursor.

Byte-exact members are the point rather than an optimisation. This store's posture is
that a payload is truth, and re-rendering a resource to put it in a page would make the
copy a client reads differ from the copy that was stored, in whitespace and key order at
least, and in whatever a model normalises at worst.

**And a set is where a stream is honest.** The caveat above — that resources are
`byte[]` because a write is one transaction over a whole array — does not extend to
documents made of members. An export of a million resources is a sequence of bounded
things, so framing and unframing may be offered over streams as well as arrays without
the abstraction lying about what it can do. The single resource stays an array; the set
is where the stream belongs, along with blobs.

**Stamping and rendering the ancestors are two acts, and they happen at different
times.** The store's authority statement — these bytes were accepted as version N of
this object, at this time, under this handling — is a claim about custody at a moment,
so it is made at **write** and nowhere else. A read-time stamp would attest what is
being held now rather than what was accepted and kept unchanged since, and the chain
that links a version to the one before it could not exist at all.

Putting the ancestors *into a document a reader receives* is the other act, and it
belongs at **read**, because the alternative is freezing facts into bytes that are never
rewritten. Doing it at read used to mean parsing every member and rendering it again.
It does not have to: a face can stream the stored bytes through, emitting as it reads
and overriding the ancestor slots as they pass. No object graph, one pass, and correct
on a payload that already carries an `id` — replace rather than insert is trivial when
re-emitting a parse, where it is treacherous when editing a string. That is the same
shape as reading a payload's hash without materialising it, and the two belong beside
each other on the face for the same reason: only the structure's owner can walk it
cheaply.

So a reader receives exactly the stored form, and the ancestor slots, which were never
the author's.

**And the model may not be the normaliser, which is a measured answer rather than a
preference.** Putting the ancestors in by reading a payload into the element model and
writing it back is the obvious way to set two fields, and it loses data: a model writes
what the version's StructureDefinitions describe, so an element the version does not
define is dropped — a Patient stored carrying `instantiatesCanonical` under R4 comes back
without it. Nothing is lost in storage, since the payload is kept as it arrived, but a
reader is handed less than was written, silently. That is
REQ-DBO-CORE-DECLARED-TRUTH-FORM's own round trip, failed: a face that cannot pass
it may not declare a normalized truth form, and this one cannot.

So a read copies the stored document **token for token** — every field, in the order it
was written, numbers spelled as they were spelled, including the elements this version
has never heard of — and replaces `id` and `meta.versionId` as they pass. Replace rather
than insert, because a payload may already carry either and appending a second would
produce a document with two ids. Framing then passes a member's payload through the same
one body, so a page is the stored documents with the store's own slots filled in.

**What the stored form is, though, is declared rather than assumed.** Byte-for-byte what
the author wrote is the default and not a law:
REQ-DBO-CORE-DECLARED-TRUTH-FORM already says a type's authoritative representation —
payload or normalized form — is its personality's to declare. Two things in the store
already rely on that. A CodeSystem is stored as concepts and reassembled on the way out,
so no byte of the submitted document survives as such; and a tenant with personal-data
isolation stores ciphertext where the author wrote a name. A universal byte-identity rule
would already be false in both.

Where a type declares a normalized form, the store normalises on **write**, a read is
plainly pass-through, and the ancestors may be stamped at write as well — because what
the author wrote was never the promise for that type. Where it does not, the payload is
kept as authored and the ancestors are applied on the way out. One mechanism, two
declarations, and the difference is written down per type rather than discovered.

**Normalising is forgiveness, and forgiveness has one condition.** It makes "is this a
change?" exact rather than approximate, so two clients spelling one resource differently
stop producing two versions. The condition is that a normaliser must never silently drop
what it does not understand — an unknown extension, an element from a later ballot. Payload
is truth and stored bytes are never rewritten, so a renderer that loses something loses it
permanently, silently, and the discovery arrives years later. That is testable rather than
hopeful: round-trip a resource carrying an unknown extension and a later version's element
and require both to survive. A face that passes may declare a normalized truth form; one
that does not, may not.

**Two hashes, and only one of them is the face's.** A digest over the stored bytes is
what a history chain needs — it detects a version edited underneath the store, it needs
no parse, and nothing cheaper exists. A hash over what a document *means*, so that two
spellings of one resource agree, is a different function: only the face knows which
differences do not matter, and it is the face that provides it. FHIR fixes element order
and drops whitespace for exactly this, because signatures needed it, so there is a
published rule to implement rather than a convention to invent. One name doing both jobs
would be the mistake — a semantic hash cannot detect tampering it is designed to ignore.

That second one is not hypothetical and it is not new: `Names.flatten` in `dbo-maintenance`
replaces carriage returns and newlines with spaces, and comparing that is how a re-import
into the same tenant decides an object is unchanged. So the engine already defines what
counts as the same document, with the crudest rule there is, in a module that should not
be deciding it. It moves to the face, where a rule can improve — dropping insignificant
whitespace, fixing element order — without an engine module learning what a format is.

**Why it is genuinely the face's**, in one example rather than a principle: FHIR treats
decimal precision as significant, so a generic canonicaliser folding `1.0` into `1` would
be quietly wrong about a lab value. No engine can know that. The face does.

**Normalising FHIR needs the schema, which is why the tool matters.** Element order is
defined by a StructureDefinition, decimal precision is significant — `1.0` is not `1` for
a lab value — and a primitive extension pairs `x` with `_x` and must move with it. No
general-purpose JSON library knows any of that. One can normalise *JSON* and, doing so,
quietly claim to have normalised FHIR: reorder what the specification ordered, fold a
precision that carried meaning, separate a primitive from its extension. That is exactly
the silent loss the round-trip condition above exists to catch, and it is why the
normaliser has to come from something that reads the definitions.

HL7's own **element model** (`org.hl7.fhir.rX.elementmodel`) is that something: it parses
against StructureDefinitions rather than against a hand-written grammar, so it knows what
a version defines rather than what a document happens to contain. It is also free of any
server framework's shape, which makes it a second and independent argument for the
direction in §7.7 — the model behind a face coming from the reference implementation
rather than from a wrapper over it.

Not the generic JSON utilities from the same project. `org.hl7.fhir.utilities.json` is a
capable general parser and model — it even preserves comments — but it has no FHIR
knowledge and no canonical mode, so it can normalise a document's syntax and nothing
about its meaning. Reaching for it because it carries the right organisation's name would
be the mistake this paragraph exists to prevent.

Practical note for whoever builds it: the shared stack exports `org.hl7.fhir.r4b.elementmodel`
and `org.hl7.fhir.r5.elementmodel` but **not** `org.hl7.fhir.r4.elementmodel`, whose classes
are embedded and unexported. An R4 normaliser needs that export added, which is a change to
a fat bundle's manifest and so a change the container test is the judge of.

**One function, not a family.** The temptation is to give the face a hash per question,
so it is worth writing down which questions exist and what already answers them.

| Question | What answers it | Whose |
|---|---|---|
| have these bytes been altered since I stored or attested them? | exact digest over those bytes | the engine's — normalising anything blinds it |
| is this write actually a change? | canonical hash, ancestor slots excluded | **the face's** — the one function it provides |
| which object is this? | the envelope's extracted identifiers | already the engine's, and better: an index answers ranges, a hash only equality |
| would a reader see any difference? | `versionId` | already the engine's, and cheaper: a counter is ordered, a hash is not |

Two of those are hashes worth refusing. Both would shadow a mechanism that is already
better at the question, and both would need maintaining in step with it.

**The axis that is real is which form, not which question.** A hash over a stored payload
and a hash over a revealed one are different values, because personal-data isolation means
the stored form carries ciphertext. And a hash over revealed content **is identifying
data**: persist or expose one and it becomes a correlation vector, the same person in two
tenants producing the same value, which is what §14 exists to prevent. A digest is not
anonymisation. The vault already holds an HMAC identifier index rather than a digest for
exactly this reason, so the rule has precedent rather than needing one: anything hashing
revealed content is keyed with the tenant's own key, or it does not exist.

**And it is computed for a comparison, never stored.** Both sides of a comparison are
hashed under whatever rule is current, so improving the rule is always safe. Persist one
— as a dedup key, an idempotency record — and improving the rule changes the answer for
objects already stored, with every stored key becoming incomparable and nothing raising
its voice. If it ever must be persisted it carries the rule's version beside it, the way
a payload carries `payload_version`.

Worth knowing before anyone calls it free: normalising order means a canonical hash
cannot stream purely, because keys are buffered per object level and sorted before
digesting. That is memory proportional to an object's width rather than to the document,
and far less than a model — but it is not nothing, and the claim should survive being
measured.

**A face is a service, and a capability is ordered rather than held.** These functions are
passive: a caller asks the face for one when it needs it, and what stands behind it — a
pooled parser, a thread, a cache — is the providing bundle's business and nobody else's.
The thing to ask is the face itself, registered in the service registry under its code,
with capabilities looked up by type the way `DeclaredFace` was built for. That is also
what turns the closed list of versions into a question with an answer: a tenant declaring
a face the container has no service for is refused because nothing provides it, not
because a validator names two strings.

Three things follow from the provider being free behind the service, and each is a way to
get it wrong.

*A capability is not held across service dynamics.* A personality bundle can be stopped or
updated — the development console does that on every republish — and a field still
holding a capability from the previous revision is an object with a dead classloader
behind it, failing later as something that looks like anything but that. Ask per
operation, or track the service.

*And both of those are worth catching rather than documenting.* A connection pool does not
rely on being told to close connections; it records where one was taken and warns when it
is held too long, with the stack trace of the caller that took it. The same applies here,
in two forms, because the two hazards fail differently.

A cursor-backed stream is an ordinary resource leak: warn when one is held past a
threshold, and — the commoner case — use a {@code Cleaner} to warn when one is collected
having never been closed at all. A dropped stream happens more often than a slow one, and
a timer alone never sees it.

A held capability is not a resource leak but a correctness one, and no timer can see it:
nothing observes that a reference is still out there. What is observable is the moment it
goes stale, so invalidate on unregistration and complain at next use, naming where the
capability was obtained. That fires when the bug becomes real rather than when a clock
guesses, and it needs no threshold to tune.

Two constraints on the warning itself. It may name the code site, the age and the kind of
thing; it may not name what was being read. A search carries `identifier=system|value`,
and a diagnostic that helpfully quoted the request would put in a log the thing §14
encrypts in the payload. And capturing a stack trace is not free, so this is gated and off
by default, in the same shape as the rest of the logging knobs.

*Internal concurrency must not become visible concurrency.* Writing a member goes into the
caller's stream, so whatever a provider does behind the scenes it returns having written,
in the order it was called. The engine owns order and back-pressure because a page is
ordered and the reader is the brake; a provider that parallelised its way to out-of-order
entries would be correct alone and wrong in the pipeline.

*Nothing outlives the call.* A face may use a thread to compute; it may not keep work
running past the call that asked, because then its lifetime stops being the engine's to
reason about. That is the translator-not-an-actor rule in the one form it does not
currently spell out.

**They are version-scoped, which is why they are capabilities.** A codec, a validator and
a converter know what a version defines and nothing about a tenant, so they sit in set 1
and `DeclaredFace` fits them exactly. Envelope extraction does not: it depends on the
declared types, so it stays per tenant. That is the same line drawn twice — the half of a
personality that is per version, and the half that is per tenant.

## Where the detail is

- **What a face owes the engine, and the three sets it owes them in** — [the
  engine and its faces](../engine-and-faces/README.md).
- **Why the stored bytes are the truth, and what is derived from them** —
  [records you can rely on](../records-you-can-rely-on/README.md).
- **What a FHIR client sees of all this** — [the FHIR face](../the-fhir-face/README.md).
