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

`dbo-console:watch` registers the set, and `dbo-console:up` finishes by calling
it. The logging binding and any fragment are never watched: re-reading the
binding is how the container goes quiet, and a container that has gone quiet
cannot tell you so. It derives it from what is installed rather than
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

`-Pdbo.karaf.logging=dbo` assembles the console on the distribution's own
arrangement instead: slf4j-api, `dbo-logging`, SPI-Fly, and **no features
service**. It works — Karaf boots, the shell comes up, `dbo-console:up` brings
the store up, and the log is the product's own writer.

It is featureless because subtracting pax-logging from a feature-based Karaf
does not converge. pax-logging exports `org.slf4j` at 1.7 *and* 2.0 at once and
Karaf's plumbing is built across both, so every boot feature reaches for it:
`wrap` reinstalls it as a dependency and quietly undoes the substitution, and
dropping `wrap` moves the failure to `management`, then `jaas`. So the startup
set is written out directly — the whole runtime in start-level order, resolved
at build time in a list you can read.

What that costs, beyond `log:set` and `log:tail`: no `feature:*` commands, no
`kar`, no `instance`, no `management`/JMX. What it keeps: the shell over ssh,
`bundle:*` including the watcher, `config:*`, `service:*`, `package:*`, and the
`dbo-*` commands.

Four things had to be true, each a way Karaf differs from the distribution:
SPI-Fly must be the **framework extension** (the dynamic bundle carries no ASM
and imports it from a container that has none); the Felix **Log Service**
supplies `org.osgi.service.log`, which only pax-logging otherwise exports; the
**JCL bridge** carries Karaf's JAAS modules into slf4j; and `karaf/slf4j-compat`
re-exports slf4j-api's own packages at 1.7 versions, because pax-url-aether —
the `mvn:` handler `bundle:watch` reads through — wants `org.slf4j.spi;[1.7,2.0)`
and supplying the real 1.7 API beside the 2.x one puts two class spaces in one
wiring and dies on `ILoggerFactory`.

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

**It binds, and Karaf does not yet stand up without pax-logging.** `dbo-logging`
becomes the binding and reports container failures as `ERROR dbo.container - …`
in the product's own format — which is how everything below was found rather
than guessed.

Settled, each one a way Karaf differs from the distribution:

- **SPI-Fly must be the framework extension.** The extension embeds ASM and the
  weaver; the dynamic bundle embeds neither and imports them from a container
  that has neither, so it never resolved and nothing provided the
  `osgi.serviceloader` extender — which silenced the very thing that would have
  said so. It attaches fine from Karaf's startup set.
- **`org.osgi.service.log`** is imported by Karaf's metatype, config and features
  bundles and comes only from pax-logging in a stock Karaf. The Felix Log Service
  supplies it.
- **Karaf's plumbing wants slf4j 1.7.** pax-url-aether — the `mvn:` handler
  `bundle:watch` reads through — imports `org.slf4j.spi;[1.7,2.0)`. Supplying the
  real 1.7 API beside the 2.x one is wrong: pax-url then took `org.slf4j` from
  2.0.18 and `org.slf4j.impl` from the 1.7 binding and died with a loader
  constraint violation on `ILoggerFactory`, two class spaces in one wiring.
  `karaf/slf4j-compat` re-exports the host's own packages at 1.7 versions
  instead — one class space, both ranges satisfied.
- **The bundle cache hides configuration changes.** Start levels and ordering in
  `startup.properties` apply at install; editing them under a populated `data/`
  changes nothing and looks like a failed experiment.

Open, and the reason this is not finished:

- Boot features reach for pax-logging because it exports both slf4j generations
  at once. `wrap` pulls it back as a dependency, and with it present Karaf boots
  and the binding is NOP; drop `wrap` and the resolution failure moves to
  `management` → `jaas` → the next thing. The cascade suggests the way through is
  a **featureless assembly** — the shell listed directly in the startup set,
  with the features service out of the picture — which is the minimal assembly
  the plan document has wanted all along.
- With the compat fragment attached, slf4j finds no provider and defaults to NOP.
  That worked before the fragment existed, so the fragment is implicated and
  unexplained.

Use the default posture.

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

**It binds, and it does not yet finish booting.** `dbo-logging` is the binding:
container failures arrive as `ERROR dbo.container - …` in the product's own
format, which is how each of the following was found. Four blockers are cleared
and one remains.

Cleared, and each one a way Karaf differs from the distribution:

- **SPI-Fly must be the framework extension**, not the dynamic bundle. The
  extension embeds ASM and the weaver; the dynamic bundle embeds none of it and
  imports them from a container that has neither. It attaches from Karaf's
  startup set, so the objection that an extension cannot attach in Karaf was
  wrong.
- **`org.osgi.service.log`** is imported by Karaf's own metatype, config and
  features bundles and in a stock Karaf comes only from pax-logging. The Felix
  Log Service provides it.
- **pax-url-aether wants slf4j 1.7.** It is the `mvn:` handler `bundle:watch`
  reads through, and it imports `org.slf4j.spi;[1.7,2.0)` — the gap pax-logging
  papers over by exporting both generations at once. The 1.7 API rides along
  beside the 2.x one.
- **The 1.7 API needs a binding to resolve at all**, declaring a mandatory
  `org.slf4j.impl` import. The no-op binding is the right one: pax-url's logging
  should be silent rather than wrong.

Remaining: resolution is clean and no boot feature installs. Twenty-five bundles
— the startup set exactly — and the ssh port never opens, with nothing logged.
`featuresBoot` is well formed and `features.core` resolves. Use the default
posture until this is understood.

**Reassembly is destructive.** `:karaf:console` re-unpacks Karaf, which drops
`data/` — installed bundle state and the ssh host key with it. It refuses to
run while a console is up rather than leaving that to be remembered; stop the
console, reassemble, start it again, `dbo-console:up`.

**Ports.** 8090 serves FHIR, 8101 is the Karaf ssh port, and the console user
is `karaf`/`karaf`. All loopback, all development-only.
