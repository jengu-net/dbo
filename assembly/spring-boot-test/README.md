# Testing an application built on these assemblies

One dependency, one annotation, and a bean that holds the deployment.

```java
@DboSpringBootTest
@ActiveProfiles("test")
class MyApplicationServesIT {

    @Autowired DboTestContext dbo;
}
```

```yaml
# src/test/resources/application-test.yaml
dbo:
  test:
    world: ../sample-world       # read ONCE, to seed what the test declares
    lane:
      tenant: hogwarts           # only where something here performs work
```

That is the whole of the configuration. A database for this JVM, a key, the
mount, the spec paths, the port and — where a lane is named — a credential the
tenant issued and a lane pointing at it are all **derived**.

## The one rule

**`dbo.test.*` is the only namespace a test author writes.** Everything the
application reads is derived from it and contributed as a property source.

A test that named `dbo.admin.jdbc-url` would be describing a deployment, and
the deployment under test is the application's own. It is also what keeps an
application honest: its configuration stays the shape an integrator copies
rather than the shape a test needed.

| a test says | and gets, without naming it |
|---|---|
| `dbo.test.image` | one Postgres for this JVM, never stopped |
| `dbo.test.world` | `dbo.tenants.directory`, `dbo.management-spec` |
| `dbo.test.lane.tenant` | `dbo.worker.lanes[0].*`, a client with the `work` scope, and a base pointing at the live port |
| — | `dbo.admin.*`, `dbo.auth.kek`, `dbo.mount`, `server.port` |

## Declaring tenants, in memory

The directory is a **bootstrap**. It is read once so a reader has a world to
open; after that the deployment's tenants are what this test says they are.

```java
dbo.declare("katsetenant", """
        {"code":"katsetenant","face":"r5","audit":{"level":"none"},
         "types":[{"name":"Observation","identity":"internal",
                   "handling":"operational"}]}""");
dbo.until("katsetenant", true, Duration.ofMinutes(5));

dbo.retract("katsetenant");
dbo.until("katsetenant", false, Duration.ofMinutes(2));
```

Nothing is written to a disk. `TenantRuntimeManager.declaredFrom` says a
directory is one source among several and names this case as the next — *an
application whose tenants are declared where the rest of its configuration
is* — so a test holds the other end of a seam a host is expected to use rather
than imitating a filesystem.

**Retracting means it.** The read is complete, so what a test stops declaring
is withdrawn. That is also why nothing here answers with an empty set by
accident: empty and unreachable are the same sentence to the sweep that
decides what is missing, and it acts on the first.

## Asking what a tenant serves

```java
var tenantSays = dbo.capability("hogwarts");
assertTrue(tenantSays.serves("Patient"), tenantSays.why("serves Patient"));
tenantSays.interactionsWith("Patient");   // create, read, update…
tenantSays.searchableBy("Patient");       // identifier, name…
```

Text, asked questions — not a `CapabilityStatement`. A typed FHIR resource
needs a populated worker context to parse into, which is the weight item 025
spent itself removing and which a serving node now cannot build at all. A test
wanting the typed resource can add the toolchain itself, where the cost shows.

`why(...)` is short on purpose: group these with `assertAll` and a message
carrying the whole statement would print it once per failure. `text()` is one
call away.

## Acting

```java
var written = dbo.write("hogwarts", "Patient", document);
String id = written.idOrFail();                  // from Location, not the body
dbo.read("hogwarts", "Patient", id);
dbo.search("hogwarts", "Patient", "identifier=urn:rl:nid|RL-1", "TREAT");
dbo.asking("hogwarts").work().by("a-worker").count();
```

### Asking a document

```java
var record = dbo.says(dbo.read("hogwarts", "Patient", id));
record.one("resourceType");        // Optional["Patient"]
record.at("name.family");          // every family name it carries
record.has("name");                // whether it says anything there
```

Paths are written the short way and compiled to the long one: the reader runs
the jsonpath Postgres is handed, where a member is quoted — `$."name"."family"`
— which is right for a store comparing its answer against the database's and
tiresome for a test. Anything starting `$` or `@` passes through, so the full
dialect stays reachable.

`one(...)` refuses where a path selects several, because a test asking for
*the* family name of a document carrying two has asked something the document
does not answer. A path the reader cannot run is refused rather than answered
empty, for the reason that distinction keeps mattering here.

**There is no document builder, deliberately.** A FHIR document in a test is
clearer as a text block — it is what a reader pastes into `curl`, and a fluent
chain would make a sample less like the thing it demonstrates. Changing a
document read from the store is the case a builder would earn, and no test
needs it yet.

**Two credentials, and they are not interchangeable.** `token(tenant)` is for
records; `workToken(tenant)` may act in work. A token admitted at the step
surface is refused by the records door — holding one is deliberately not
holding the store, and a helper handing out one credential good for both would
have undone the split the two surfaces exist to make.

**The id comes from `Location`.** The address is the contract; an id in the
rendered body is a convenience of the renderer, and on a tenant that holds its
people in the vault the body comes back a shell.

**The search query is escaped here.** The pipe between a system and a value is
illegal in a URI and in nearly every token search this store is asked to run.

**And a purpose is a parameter because the store asks for one.** An identifying
search is refused without an HL7 PurposeOfUse code — and refused rather than
answered with an empty page, because an empty page says nobody matches, which
is a different thing.

## Working

```java
dbo.performing();      // step code -> what the container wired
dbo.startWorking();    // begin asking the lane
dbo.workingFor();      // the tenants it performs for
```

Step services are ordinary beans the whiteboard finds, as in an application.
What a test decides is **when** the asking begins, which is why the runner is
left still until the credential exists: a runner polling a lane whose
credential has not been issued fails every cycle into a log nobody is reading.

There is no `runOnce()`. `DboWorker` exposes start and stop and nothing
between, and reaching past it into the runner would test a path no application
takes.

## Naming what a test proves

Beside this, [`core/dbo-proving`](../../core/dbo-proving) binds an assertion to
a promise, so a failure names what the store stopped promising:

```java
@Proving(DboPromises.CONT_EMBEDDED_IN_JVM)
void theWorldIsServed() {
    Proves.that(DboPromises.CONT_EMBEDDED_IN_JVM, tenantSays.serves("Patient"), …);
}
```

It refuses a promise the test did not declare, so `@Proving` stays the
declaration the catalogue reads and the assertion stays the proof.

## What this cannot do

**Keep `dbo.test.*` the same across a module's tests.** A context is cached by
its configuration: tests that agree share one server and one bring-up, and
tests that differ get a second context which Spring does not close. Two tenant
managers then scan, poll and stream over one database — which the store permits
and nothing in a test arbitrates.

**One context has one application configuration.** Booting two applications in
one context — the economy that avoids two JVMs — means only one `application.yaml`
is loaded, and they can actively disagree: a worker declares
`web-application-type: none`, which is true of it alone, while the server half
needs a servlet container.

**And a test's own `application.yaml` shadows the application's.** Use a
profile, or the test proves an application configured by the test rather than
one as it ships.
