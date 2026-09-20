**Open. The module owns its sources and the world's specs, and its runner performs a run end to end. Next: the external participant, joining from its own process.**

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
  joins a step somebody else runs, and is the one place HTTP appears.
- Its stories are its tests, run against the tree world by the third verify
  phase, and each story cites the promises it proves.
- A guide chapter includes a region of the sample's source. A chapter that
  names a sample class the sample no longer has fails the site build, the
  way a drifted diagram does.
- `using-dbo.md` becomes the sample's README, and the `dbo-using` skill
  projects from there.

## Steps

1. ~~The module.~~ `sample/` holds the sources and the tenant specs the
   guide's world is made of. The harness used to compile those sources from
   inside the documentation tree; now they are a module's own, and the
   chapters include them from there.
2. ~~A step service performed over a lane.~~ The runner an integrator writes
   — a lane to one tenant, a runner, the step registered on it — authors a
   run through the same door a chapter uses, takes it and closes it, proven
   against a world built from the tree.
3. The external participant, joining that step from its own process.
4. Each further chapter of the guide, in the order item 001 gives.

The include needs no ratchet of its own. A chapter naming a file the sample
does not have already fails the site build, because the snippet extension
checks its paths, and a chapter naming a method the store does not have
fails the compile.
