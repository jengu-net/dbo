---
title: Quick start
eyebrow: Guide
standfirst: >-
  The clinic's application started with its worker embedded, one piece of
  work asked of the hospital and its answer read — then the same worker in a
  JVM of its own, once over HTTP and once over the deployment's own database.
template: essay.html
---

You need a JDK, Docker for a Postgres, and a clone of the repository. Every
command on this page is quoted from
[`samples/check-separated.sh`](https://github.com/jengu-net/dbo/blob/main/samples/check-separated.sh),
which CI runs, so a command here that stops working fails a build before it
fails you. The script names a few things with variables, and they mean what
they say: `$DB` is a name for the Postgres container, `$JDBC` is that Postgres
as a JDBC base, `$port` is the port the application listens on, `$base` is the
hospital's address, `http://127.0.0.1:$port/t/hogwarts`, `$mode` is which of
the three ways the worker runs, and `$WORK` is where the logs go. `field`
prints one field of a JSON answer.

## Build both applications

```bash
--8<-- "samples/check-separated.sh:build"
```

## A database

The store keeps each tenant in a database of its own, so it needs a Postgres it
may create databases in:

```bash
--8<-- "samples/check-separated.sh:database"
```

## Embedded: one JVM

Start the clinic's application. The world is read from a path relative to the
application's own directory, so it starts there:

```bash
--8<-- "samples/check-separated.sh:server"
```

Embedded, `profile` and `substrate` are empty. The remaining arguments are the
two things the application needs and that do not belong in a repository: a
Postgres it may create databases in, and a key the tenants' personal data is
sealed under. That key is 32 zero bytes, which is fine for something you are
about to throw away.

The first start takes minutes rather than seconds, and nearly all of it is one
thing: a face root expanding a whole FHIR version out of the specification.
Later starts against the same database read what the first one wrote.

This is all the application tells the store, and nothing else in it is about a
container:

```yaml
--8<-- "samples/spring-boot-server-app/src/main/resources/application.yaml"
```

The tenants are the files in `samples/sample-world/tenants`. The worker's beans
arrive in this application because it depends on the worker application, and
their lane is this application's own port.

### Ask for one piece of work

Work is asked for by whoever the tenant issued a credential for it. As
`hogwarts` comes up, the application has it issue one to its worker, and you
can sign in with the same client:

```bash
--8<-- "samples/check-separated.sh:sign-in"
```

Then ask the hospital to register somebody. `hogwarts.admission.register` is
a step the hospital declares in its own file, taking a patient as an object
and allowed to write a `Patient` and an `Encounter`:

```bash
--8<-- "samples/check-separated.sh:ask"
```

What comes back is a run. The person is not a record yet: the step is handed
them, answers with the person and the stay they arrived for, and the hospital
writes both or neither.

### Read its answer

The run answers the client that asked for it, at its own address:

```bash
--8<-- "samples/check-separated.sh:answer"
```

It is a FHIR `Task`. `status` is `completed` once the hospital has written what
the step answered with, or `failed` with the hospital's reason if it refused
it. Its outputs carry what the step counted and the records it left behind,
each as `Type/id/_history/version`. The bean that did the work is
[`RegisteringAPatient`](work-leaves-and-comes-back.md#a-step-whose-result-is-records),
and it is the whole of what the worker application writes for this step.

## Separated: the worker in a JVM of its own

Start the clinic's application again, under the `separated` profile. It keeps
issuing the worker's credentials and leaves its own copy of the steps still:

```yaml
--8<-- "samples/spring-boot-server-app/src/main/resources/application-separated.yaml"
```

Then start the worker beside it, under `edge` or `substrate`:

```bash
--8<-- "samples/check-separated.sh:worker"
```

Asking and reading the answer are the same commands as above. The check reads
the application's log as well, to be sure the step was performed in the JVM it
was meant for and not in the other one.

### Apart from the store, over HTTP

`edge` is the worker's default profile. Its lane reaches one tenant through its
door, with the client the tenant issued:

```yaml
--8<-- "samples/spring-boot-worker-app/src/main/resources/application-edge.yaml"
```

That credential has `work` and nothing else. A token admitted at the step door
is refused by the records door, and holding one is deliberately not holding the
store.

### Beside the store, over its substrate

`substrate` is for scaling out beside the store. The worker reads the
deployment's own database and holds no token at all; it is admitted by a
signature and handed work sealed to it. The substrate is one more database:

```bash
--8<-- "samples/check-separated.sh:substrate-database"
```

The worker makes its own keys before it first starts. It keeps the private
halves, and writes the public halves to a file the clinic's application reads
under `separated`:

```bash
--8<-- "samples/check-separated.sh:mint"
```

Start the clinic's application with `substrate` set to
`--dbo.substrate.url=$JDBC/dbo_substrate --dbo.substrate.user=postgres
--dbo.substrate.password=sample`, and the worker with `$mode` as `substrate`:

```yaml
--8<-- "samples/spring-boot-worker-app/src/main/resources/application-substrate.yaml"
```

[On the stream](on-the-stream.md) is the chapter about why this is safe on a
plane every tenant's work crosses.

## What is running

Two Spring Boot applications that each added one dependency.
`dbo-spring-boot-server` makes the first serve tenants on its own port, inside
its own filter chain; `dbo-spring-boot-worker` makes a bean implementing
`StepService` a step the second performs. Neither constructs a container, a
runner, a registration or a lane: those are configuration, and the beans are
found.

The [next chapter](a-tenant-opens.md) opens a tenant of its own.
