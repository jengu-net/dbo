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
dbo-console:up
```

which installs the bundle set, starts it, and watches what is worth watching.
Commands are grouped by subsystem the way Karaf's own are — `dbo-console:` for
the console's own lifecycle, `dbo-tenant:` for what the node is serving — so
each scope is also a subshell you can enter and complete inside.
Safe to repeat: installing a location that is already installed returns the
existing bundle, so it is also how a session picks up a container that is
already running.

## The loop

`./gradlew dev` publishes the bundle set to `~/.m2`, which is where Karaf's
`bundle:watch` is looking. A republished bundle is picked up in about a second,
updated and refreshed in place. Nothing else has to happen for a change to
reach the running container.

`dbo-console:watch` registers the set, and `dbo-console:up` finishes by calling it. It derives it from what is installed rather than
from a list, so a new bundle needs nothing remembered, and it skips the ones
carrying a heavy embedded stack — a publish rewrites every jar, and re-reading
a hundred megabytes on each one buys nothing when the change is almost never in
there. It prints what it skipped and what the line was; `--all` and
`--max-embedded` move it.

Expect `dbo-core` to behave like a restart: everything imports it, so
refreshing it cascades through the whole set.

The console's own commands live in `deploy/`, which Karaf re-deploys on change
— so developing a command has the same loop as developing a bundle.

## Seeing what a tenant is

```
dbo-tenant:list
dbo-tenant:capability-list dev
```

`dbo-tenant:list` reads the service registry, so it answers "what is serving",
not "what was declared" — a spec that failed to come up is absent here, and the
log is where that belongs.

`dbo-tenant:capability-list` flattens the tenant's CapabilityStatement into one
row per fact: a category, a path, a value.

```
TYPE   | NAME                          | VALUE
server | fhirVersion                   | 4.0.1
server | security.description          | Bearer JWT from this tenant's own authority (/oidc); ...
entity | Patient.conditionalCreate     | true
entity | Observation.conditionalCreate | false
entity | Patient.searchParam           | 30 (token 12, string 9, date 4, reference 3, uri 2)
```

Those two `conditionalCreate` rows are the point of the command: nothing
configures them. They fall out of the spec declaring Patient by identifier and
Observation as store-assigned, and a store-assigned id has nothing to key a
conditional write on. The table is where a declaration in a spec file becomes
visible as a promise to clients.

A second argument names one capability and narrows to it. A summarised row
opens into what it summarised; a stated one prints bare, so a single fact can
be read by eye or by a script without a table to cut apart.

```
dbo-tenant:capability-list dev Observation.conditionalCreate   ->  false
dbo-tenant:capability-list dev Patient.searchParam.identifier  ->  token
dbo-tenant:capability-list dev Patient.searchParam             ->  a table of the 30
```

Both arguments complete on TAB. Tenants come from the registry rather than a
cached list, because they come and go while the console is open. Capability
names come from the statement of the tenant already typed — the union across
tenants would offer facts a tenant does not have — so nothing completes until
a tenant is on the line. The whole tree is offered, both the names that open
into a table and the individual ones that print a value.

`--search-params` expands every entity at once instead of one. Karaf wants
options before arguments, so it is
`dbo-tenant:capability-list --search-params dev`.

It reads the tenant's own `/metadata` rather than the store facade. The facade
can render a statement too, but its single-argument form is the one that does
not know which operations were actually registered, and the security block is
added at the serving edge — so the registry would report less than the tenant
actually promises. `/metadata` is the one path the guard exempts, so this needs
no token.

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
pax-logging rather than installing `dbo-logging` and its slf4j-api host. So
`log:tail` and `log:set` work here — including changing a level while it runs,
which the product's own arrangement cannot do at all, since `DboLogging` reads
its level into a `static final` once and has no per-logger filtering. The cost
is that a fault in the real logging arrangement is invisible here.

`-Pdbo.karaf.logging=dbo` assembles the console with the distribution's
arrangement instead — slf4j-api, `dbo-logging`, and SPI-Fly as a dynamic bundle
rather than the framework extension the distribution uses, because an extension
can only attach at framework init.

**It does not currently complete boot.** Every bundle installs, including
`features.core`, slf4j-api, SPI-Fly and `dbo-logging`, and the framework reaches
start level 100 — but no boot feature comes up and the ssh port never opens.
Nothing says why, which is the point worth recording: the diagnostics that would
report it are Karaf's, and they are what the posture replaces. Karaf 4.4.11's own
bundles import `org.slf4j;version="[2.0,3)"`, which slf4j-api 2.0.18 satisfies,
so the version ranges are not the obstacle. Use the default posture until this is
understood.

**Reassembly is destructive.** `:karaf:console` re-unpacks Karaf, which drops
`data/` — installed bundle state and the ssh host key with it. It refuses to
run while a console is up rather than leaving that to be remembered; stop the
console, reassemble, start it again, `dbo-console:up`.

**Ports.** 8090 serves FHIR, 8101 is the Karaf ssh port, and the console user
is `karaf`/`karaf`. All loopback, all development-only.
