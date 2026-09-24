# A FHIR server, because an application added one dependency

This is an ordinary Spring Boot application. It has a `main`, a
`application.yaml` and no code about containers — and it serves the tenants in
[`../sample-world`](../sample-world) on its own port, through its own filter
chain, because `dbo-spring-boot-server` is on its classpath.

Everything a reader is looking for is in three files:

| | |
|---|---|
| [`ServerApplication.java`](src/main/java/cloud/jengu/dbo/samples/server/ServerApplication.java) | a bare `@SpringBootApplication`. Nothing else. |
| [`application.yaml`](src/main/resources/application.yaml) | `mount: servlet`, where the tenants are, and which one holds this deployment's own history |
| [`build.gradle.kts`](build.gradle.kts) | one line naming the dependency |

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
serve the same world on purpose, so the only difference between them is how
the store is reached — see [item
026](../../docs/arc42-011-risks-and-technical-debt/026-two-samples-tell-one-story/README.md).

Its companion is [`../spring-boot-worker-app`](../spring-boot-worker-app),
which performs this tenant's work and holds no store at all.
