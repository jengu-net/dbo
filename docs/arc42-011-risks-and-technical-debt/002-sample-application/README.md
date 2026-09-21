**Open. The actor surface exists and two scenes run over it, each having caught an assumption an in-JVM scene would have let stand. The map said HTTP belonged only in the participant's chapter; that is changed, on this evidence. Next: the guide's chapters, one at a time, over the surface.**

# The sample application

The guide's map entry says the guide is the sample application's story, and
there is no sample application. This item builds it; item 001's last step
rewrites the guide over it.

## What it is

A root-level Gradle module on the guide world, using the store the way a
product would.

- It declares a tenant and its types, writes a step service, registers it,
  and receives work over a lane.
- A second, smaller module is an external participant: another process that
  joins a step somebody else runs, over a lane rather than the store's
  surface.
- Its stories are its tests, run against the tree world by the third verify
  phase, and each story cites the promises it proves.
- A guide chapter includes a region of the sample's source. A chapter that
  names a sample class the sample no longer has fails the site build, the
  way a drifted diagram does.
- `using-dbo.md` becomes the sample's README, and the `dbo-using` skill
  projects from there.

## What proving through it changes

A promise is proven today by a test that builds the conditions it needs and
then asserts. The conditions are synthetic: a tenant declared for this class,
a record written through the store's own API, a round driven by hand. That is
fast to write and it drifts, because nothing makes the conditions resemble
the ones a deployment produces. A test can go on passing over an arrangement
no product would ever be in.

Proving through the sample inverts it. The test does what an actor does, and
the promise is read afterwards from what the store did about it.

**The surface is the actor's vocabulary, not the promise list.** Declare a
tenant, take a credential, write a record, search, start a run, claim it,
report it, introduce a step, provision a person, export, import, ask to be
forgotten. That is tens of methods across nine stories, and it does not grow
with the number of promises, because most promises are not things anybody
calls.

**Most promises are system reactions.** Nobody calls "write a trail entry".
Somebody admits a patient, and the entry is there or it is not. The envelope
derived from the payload, the change event committing with the row, the
version kept, the purpose landing on the record — each is a consequence of
one write, and a consequence is read, never invoked. The guide already shows
the ratio: ninety-nine published commands cover sixty-five promises, and a
good half of those commands are looks at what the store did rather than
things a reader does.

**What no actor reaches stays where it is.** Forty-seven promises are proven
only by classes that build a runtime of their own, and they are the ones with
no action behind them: a tenant refusing to come up, a node saying what it
serves, a claim about what a sweep left. Nobody does those, so no surface
should pretend to. `config/worlds-ledger.txt` already carries a reason per
class, and that set is the declared boundary of this approach rather than a
gap in it.

**The parts stay separate.** The sample hands out what an integrator holds —
a runner here, an outside participant there — rather than one object that
does everything. The point of the module is to look like the code somebody
writes.

**It reaches the store the way a product does.** Over the surface, holding a
token, because an integrator of a multi-tenant store is across a network from
it — and because a scene that reached the store in-JVM could take shortcuts no
product has. The map said HTTP belonged only in the participant's chapter and
has been changed, on the evidence: the first two scenes written over the
surface each caught an assumption an in-JVM scene would have let stand. One
asserted that a read returns what a write sent, and the hospital holds its
people behind the membrane, so it does not. The other asserted that starting
work confers a reader, and a worker holds `work` and not `system/*`, so it
does not. Both are the store keeping a promise and a test assuming otherwise,
which is the drift this whole approach exists to catch.

What stays in-JVM is what only runs there: a lifecycle listener, an observer,
an embedded boot. Those are things a host does, not things an actor does.

## Steps

1. ~~The module.~~ `sample/` holds the sources and the tenant specs the
   guide's world is made of. The harness used to compile those sources from
   inside the documentation tree; now they are a module's own, and the
   chapters include them from there.
2. ~~A step service performed over a lane.~~ The runner an integrator writes
   — a lane to one tenant, a runner, the step registered on it — authors a
   run through the same door a chapter uses, takes it and closes it, proven
   against a world built from the tree.
3. ~~The external participant, joining with a capability of its own.~~
   `sample/participant/` is another organisation's process: a lane, a runner
   and a step service carrying its own declaration, with no store on its
   compile path. It introduces the step beside its candidacy, the hospital
   authors a run of it by posting a `Task` to the face, and the laboratory
   performs it. The same document from the laboratory's own credential is
   refused, which is what an introduction granting nothing looks like from
   the outside.
4. ~~The actor surface.~~ Done. `TheWorld` holds the world's addresses and
   hands out the parts; `Surface` is one tenant's front door in an actor's
   vocabulary — sign in, carry somebody's token, write, read, search, change,
   forget, start a run — with no method for anything the store does in
   reaction. `Answer` is carried whole, because half of what a story asserts
   is a refusal. Two scenes use it and both earned their keep on the first
   day; they are the evidence for the paragraph above.
5. Each further chapter of the guide, in the order item 001 gives, over that
   surface.
6. Promises move onto it as the stories grow, one story at a time, and a
   harness class whose conditions the surface now produces is deleted rather
   than ported. Nothing moves that the ledger says needs a world of its own.

The include needs no ratchet of its own. A chapter naming a file the sample
does not have already fails the site build, because the snippet extension
checks its paths, and a chapter naming a method the store does not have
fails the compile.
