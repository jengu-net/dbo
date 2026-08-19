# The development console

A stock Apache Karaf installing the same bundle set as the serving
distribution, so an edit becomes a running container without a restart. It
exists to make the inside of the OSGi container visible while work is
happening — see [the plan](../docs/plans/karaf-console.md) for what it is and
where it stops.

Not a deployment artifact. Production runs the standard Felix launcher.

## Once

```
cp karaf/dev/local.properties.example karaf/dev/local.properties
```

Point `dbo.tenant.admin.*` at a Postgres you own; the console provisions a
database per tenant, so that connection needs `CREATE DATABASE`. Then:

```
./gradlew :karaf:console
```

Add `dbo.dev=true` to `~/.gradle/gradle.properties`. It skips javadoc, which
every library module otherwise builds on every republish.

## Every session

```
karaf/build/dbo-console/karaf/bin/karaf
```

and at the prompt, once:

```
dbo:up
```

which installs the bundle set, starts it, and watches what is worth watching.
Safe to repeat: installing a location that is already installed returns the
existing bundle, so it is also how a session picks up a container that is
already running.

## The loop

`./gradlew dev` publishes the bundle set to `~/.m2`, which is where Karaf's
`bundle:watch` is looking. A republished bundle is picked up in about a second,
updated and refreshed in place. Nothing else has to happen for a change to
reach the running container.

`dbo:watch` registers the set, and `dbo:up` finishes by calling it. It derives it from what is installed rather than
from a list, so a new bundle needs nothing remembered, and it skips the ones
carrying a heavy embedded stack — a publish rewrites every jar, and re-reading
a hundred megabytes on each one buys nothing when the change is almost never in
there. It prints what it skipped and what the line was; `--all` and
`--max-embedded` move it.

Expect `dbo-core` to behave like a restart: everything imports it, so
refreshing it cascades through the whole set.

The console's own commands live in `deploy/`, which Karaf re-deploys on change
— so developing a command has the same loop as developing a bundle.

## Worth knowing

**Tenants are live.** The spec directory is reconciled every two seconds, so a
`*.json` dropped into `karaf/dev/tenants` brings a tenant up and removing it
takes one down. `karaf/dev/tenants/dev.json` is an R4 tenant with a Patient and
an Observation.

**What to look at when something breaks.** `bundle:diag` names unsatisfied
requirements, `package:exports` and `bundle:tree-show` explain who wires to
whom. That is the whole point of the console: the characteristic failure here
resolves at build time and dies on first use, and a red test says only that it
happened.

**The container's own startup line** reports the posture it resolved —
provisioner, authority, bind address, spec directory. If it disagrees with what
you expected, the disagreement is the finding.

**This is not the distribution's logging.** The console keeps Karaf's
pax-logging rather than installing `dbo-logging` and its slf4j-api host: two
providers of `org.slf4j` in one framework is a race, and the binding this
project ships is a fragment plus a framework extension that can only attach at
framework init. So `log:tail` and `log:set` work here, and a fault in the real
logging arrangement is invisible here by construction.

**Reassembly is destructive.** `:karaf:console` re-unpacks Karaf, which drops
`data/` — installed bundle state and the ssh host key with it. It refuses to
run while a console is up rather than leaving that to be remembered; stop the
console, reassemble, start it again, `dbo:up`.

**Ports.** 8090 serves FHIR, 8101 is the Karaf ssh port, and the console user
is `karaf`/`karaf`. All loopback, all development-only.
