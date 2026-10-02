# The clinic's application, with the store embedded

This is an ordinary Spring Boot application. It has a `main`, a
`application.yaml` and no code about containers — and it serves the tenants in
[`../sample-world`](../sample-world) on its own port, through its own filter
chain, because `dbo-spring-boot-server` is on its classpath. It is also the
clinic's own software: it asks for work and reads what the work came to, and
the steps that work is made of come from
[`../spring-boot-worker-app`](../spring-boot-worker-app), embedded.

Everything a reader is looking for is in these files:

| | |
|---|---|
| [`ServerApplication.java`](src/main/java/cloud/jengu/dbo/samples/server/ServerApplication.java) | a bare `@SpringBootApplication`. Nothing else. |
| [`application.yaml`](src/main/resources/application.yaml) | `mount: servlet`, where the tenants are, which one holds this deployment's own history, and the embedded worker's lanes |
| [`EnrollingTheWorker.java`](src/main/java/cloud/jengu/dbo/samples/server/EnrollingTheWorker.java) | the worker made known to each tenant as it comes up |
| [`build.gradle.kts`](build.gradle.kts) | the dependency on the store, and the one on the worker |

## What it needs

A Postgres it may create databases in — a database per tenant is how this
store isolates them — and a key the tenants' personal data is sealed under.
Neither is in `application.yaml`, because neither belongs in a repository.

```bash
docker run -d --name dbo-sample-db -p 5432:5432 \
    -e POSTGRES_PASSWORD=sample postgres:17-alpine
```

## Running it

```bash
./gradlew :samples:spring-boot-server-app:run \
    --args='--dbo.admin.jdbc-url=jdbc:postgresql://localhost:5432/postgres
            --dbo.admin.user=postgres
            --dbo.admin.password=sample
            --dbo.auth.kek=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='
```

The first start takes a minute or so and most of it is one thing: a tenant
that takes its face from no root expands a whole FHIR version out of the
specification. Later starts read what the first one wrote.

That KEK is 32 zero bytes, which is fine for something you are about to throw
away and is not a key. A deployment's is a secret it holds in custody.

That is the **embedded** mode: one JVM, the store and the clinic's steps in
it. The worker's beans arrive in this context, its lane is this application's
own port, and as `hogwarts` comes up this application has it issue the client
that lane signs in with.

## Running the worker in a JVM of its own

The same steps, performed somewhere else. Start this application under the
`separated` profile, which leaves its own copy of the steps still but keeps
issuing the worker's credentials, and then start the worker beside it.

**At the edge, over HTTP.** The worker reaches one tenant through its door,
with the client this application had the tenant issue:

```bash
./gradlew :samples:spring-boot-server-app:run --args='<the four above>
            --spring.profiles.active=separated'
./gradlew :samples:spring-boot-worker-app:run      # the edge profile is its default
```

**Beside the store, over its substrate.** The worker reads the deployment's
own database and holds no token at all. It makes its own keys first, keeps the
private halves, and writes the public halves to a file this application reads
under `separated`:

```bash
docker exec dbo-sample-db createdb -U postgres dbo_substrate
./gradlew :samples:spring-boot-worker-app:mintEnrolment
./gradlew :samples:spring-boot-server-app:run --args='<the four above>
            --spring.profiles.active=separated
            --dbo.substrate.url=jdbc:postgresql://localhost:5432/dbo_substrate
            --dbo.substrate.user=postgres --dbo.substrate.password=sample'
./gradlew :samples:spring-boot-worker-app:run --args='--spring.profiles.active=substrate'
```

Either way, a step asked of `hogwarts` is performed in the other JVM.
[`../check-separated.sh`](../check-separated.sh) does exactly this, embedded
and then under each profile in turn, and CI runs all three.

## Asking it something

```bash
curl -s localhost:8080/t/hogwarts/fhir/metadata | head -c 200
```

A capability statement, derived rather than written: the types are the
tenant's declared types, the interactions are what each type's declared
handling permits, and the search parameters are the ones the compiler will
accept.

Reading a record needs a credential the tenant issued, and this application
mints none for you — which is the point. What the tests do instead is ask the
tenant's own authority, which is
[`DboTestContext.token`](../../assembly/spring-boot-test/src/main/java/cloud/jengu/dbo/spring/test/DboTestContext.java).

## What it is not

It is not the distribution. `core/dbo-server` is the store as a thing you
deploy; this is the store as a library inside something you wrote. The two
serve the same world on purpose ([`../sample-world/compose.yaml`](../sample-world/compose.yaml)
is the distribution's), so the only difference between them is how the store
is reached. [The guide](../../docs/guide/index.md) is this application's
story, chapter by chapter.

Its companion is [`../spring-boot-worker-app`](../spring-boot-worker-app),
which performs the clinic's work and holds no store at all. This application
depends on it, so its steps run here, beside the store.

## Its tests are the user stories

[`src/test/java/cloud/jengu/dbo/samples/stories`](src/test/java/cloud/jengu/dbo/samples/stories)
walks every user story on the sample world, at once, in one context of this
application booted as any Spring Boot application is tested:

```bash
./gradlew :samples:spring-boot-server-app:storyTest
```
