**Open, and measured. An application holding both Spring Boot assemblies gets
one container, which is the design — and only one assembly's framework
properties, which is not. Both autoconfigurations declare `EmbeddedRuntime`
with `@ConditionalOnMissingBean` and neither declares an order, so the second
one's dials are dropped in silence. Measured in the worker sample's own test:
`poll: 500ms` configured, 2.01s observed, which is the activator's default.**

# Two assemblies, one runtime, one set of properties

## What this is

`core/dbo-embedded` unions every `META-INF/dbo/bundles.index` on the
classpath so that an application holding both assemblies gets **one**
framework rather than two. That part works, and it is worth the design: two
Felix instances each hold a copy of every bundle, and for the element bundle
that is a parsed set of FHIR definitions measured at 100–215 MB, held twice.

The container is also where each assembly puts its configuration.
`DboServerProperties.asFrameworkProperties()` carries the mount, the tenant
directory, the management spec, the admin connection, the key and the issuer.
`DboWorkerAutoConfiguration` carries `dbo.runner.poll.millis` and
`dbo.runner.hold.millis`. Each builds `EmbeddedRuntime` with its own map:

```java
@Bean(initMethod = "start", destroyMethod = "close")
@ConditionalOnMissingBean
public EmbeddedRuntime dboEmbeddedRuntime(DboWorkerProperties properties) { … }
```

Both are `@ConditionalOnMissingBean`, so the second to be processed does not
run. Neither carries `@AutoConfigureBefore` or `@AutoConfigureAfter`. So one
assembly's properties reach the container and the other's are discarded, and
which one depends on autoconfiguration order.

## What it costs, by case

| first | consequence |
|---|---|
| the server | the runner's poll and hold fall back to the activator's defaults — the configured `dbo.worker.poll` does nothing |
| the worker | the server half gets no mount, no tenant directory, no management spec, no admin connection and no key |

The second case is the alarming one on paper and is probably not what happens:
the observed order puts the server first. That is exactly why it is worth an
item rather than a shrug — the benign case is the one that occurs, and nothing
decides it.

## What was measured

`samples/spring-boot-worker-app`'s test boots the serving application and the
worker in one context, and its `application-test.yaml` sets `poll: 500ms`.
The retry interval in that run:

```
01:34:34.816  01:34:36.827  01:34:38.837  01:34:40.847  01:34:42.857
```

2.011 seconds apart, six times. `StepRunner`'s default is two seconds. The
configured value reached a properties object, was turned into a framework
property, and was thrown away with the `EmbeddedRuntime` bean that Spring
never created.

**Nothing failed.** The worker polls, performs, and the test's other
assertions pass — at four times the interval it was told. A dial that is read,
converted and discarded is the shape of a configuration nobody can trust
without measuring it, which is worse than one that is refused.

## The fix, as it looks now

The property maps have to be collected rather than owned. `dbo-embedded` can
declare a plain contribution interface — it names no framework and must not
start — and each assembly registers one:

```java
public interface FrameworkContribution { Map<String, String> properties(); }
```

The `EmbeddedRuntime` bean then takes every contribution rather than one
properties class, and stays `@ConditionalOnMissingBean` so an application may
still build its own. Whichever assembly creates the runtime, both
contributions are applied, and the answer stops depending on order.

**What it needs beside the change** is the test that would have caught this:
one context holding both assemblies, asserting that a value only the worker
configures and a value only the server configures are both in the running
container. Neither module's own tests can see it — each boots one assembly,
which is the arrangement that made this invisible.

## What is deliberately not proposed

**Not an ordering annotation.** Declaring the server first would make the
benign case the certain one and leave the mechanism unchanged: one assembly's
configuration would still be discarded, and the next dial added to the worker
would still vanish.

**And not a second container.** That is the thing the union of bundle indexes
exists to prevent, at 100–215 MB a copy.

## What proves it

```
./gradlew :samples:spring-boot-worker-app:test
```

and read the interval between `lane cycle failed` lines in the report against
the `poll` in `application-test.yaml`. Until the fix, they disagree.
