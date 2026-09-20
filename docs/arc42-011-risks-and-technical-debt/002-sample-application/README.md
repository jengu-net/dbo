**Open. Waits for nothing; starts when item 001 has reached its last step.**

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

1. The module, one tenant, one type, one step service, one story.
2. The external participant, joining that step from its own process.
3. The include and its ratchet in the site build.
4. Each further chapter of the guide, in the order item 001 gives.
