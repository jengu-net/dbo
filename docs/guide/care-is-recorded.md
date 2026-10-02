---
title: Care is recorded
eyebrow: Guide
standfirst: >-
  The clinic's application asks for steps rather than writing records. A
  patient is registered once however often she arrives, a visit is recorded
  whole or not at all, a correction is refused when the record has moved on,
  and everything is in a trail nobody can edit.
template: essay.html
---

The clinic Ines opened is running, and Maarja is seeing patients in it. Liis
Tamm arrives twice in a fortnight and is one patient both times, because the
number her clinic knows her by decides that and nothing guesses. Her visit lands
whole. None of this is clinical logic: the store does not know what a
temperature is for. It knows what a record is, what makes two writes one
patient, and what it may not quietly forget. The scene is
[US-DBO-CLINICAL-RECORD](../arc42-003-context/user-stories/us-dbo-clinical-record.md),
walked at St Jerome by
[`TheClinicRecordsCareAndAccountsForItIT`](https://github.com/jengu-net/dbo/blob/main/samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicRecordsCareAndAccountsForItIT.java).

## The clinic declares its work

St Jerome keeps its patients under record numbers of its own, and declares
three steps: registering somebody, correcting a record, and recording a visit.

```json
--8<-- "samples/sample-world/tenants/st-jerome.json"
```

A slot says what a run of the step is over. `Patient` is an object handed to
the step, because somebody arriving is not a record yet; `Reference(Patient)`
names a record the clinic holds, and the step is handed that record;
`Observation[]` is several objects, in the order they were sent. `writes` is
what the step may ask the clinic to write, and a record of any other type is
refused before anything is read.

## Asking for a step

The application asks with `DboInitiator`, naming the tenant, the step and what
fills each slot. Registering somebody gives the person as the application has
them:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/AskingForARegistration.java:asking"
```

A visit gives every observation at once, so the step can answer with all of
them as one result:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/AskingForAVisit.java:asking"
```

A correction names the record by reference and gives the record as it should
read:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/AskingForACorrection.java:asking"
```

## Performing it

Each step is a bean in the worker application. It is handed what the run
carries, and answers with what it counted and the records it wants written:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/RecordingAPatient.java:step"
```

The step writes nothing itself; it holds no records credential and no
connection. The clinic writes what it answers with, under the run, through its
own rules — validation, identity, the trail — or refuses it and says why.

A visit is one result of several records, each named for the others by the
`urn:uuid` it was given under:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/RecordingAVisit.java:step"
```

A correction is written over the record **at the version it was decided on**:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/CorrectingARecord.java:step"
```

## Hearing back

The store tells nobody when a run ends: a run is a record, and its end is a
version of it. So the application that asked, asks again, and is answered with
the run as a `Task`:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/HearingBack.java:hearing"
```

What the step counted, and the records the clinic wrote for it, are the run's
outputs:

```java
--8<-- "samples/spring-boot-worker-app/src/main/java/cloud/jengu/dbo/samples/worker/HearingBack.java:reading"
```

The story puts the two together. Liis is registered, and what is learnt is
where she was written:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicRecordsCareAndAccountsForItIT.java:produced"
```

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicRecordsCareAndAccountsForItIT.java:register"
```

Her visit names her by the number the sender knows, and one observation gathers
the other by the name it was given:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicRecordsCareAndAccountsForItIT.java:visit"
```

Her birth date turns out to be wrong by a day, and the correction says which
version it was decided on:

```java
--8<-- "samples/spring-boot-server-app/src/test/java/cloud/jengu/dbo/samples/stories/TheClinicRecordsCareAndAccountsForItIT.java:correct"
```

## Asking what is there

The questions a ward clerk asks — how many of these, what is still open on
this case, who touched this record — are methods of the store's asking
vocabulary rather than searches the application composes:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/CountingTheWard.java:questions"
```

They are asked over the clinic's door, carrying the credential of whoever the
screen is for, so the clinic's authority hears about every one of them.

## Watching the work

An application that must not miss a change reads it as a named, durable
consumer, with `@DboObserver`:

```java
--8<-- "samples/spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/WatchingTheWork.java:watching"
```

The name is the point: an application that was down for an hour resumes where
it left off. What arrives is that something changed, not the record; a
tenant's records are reached through a step like anything else.

## What the store guarantees

- **One patient, however many times she arrives.** Identity is the declared
  identifier and never a resemblance. A second record for somebody the clinic
  already holds by that number is refused, and a namesake with a different
  number is a different patient.
- **What was written is what is read**, an element nothing indexes included,
  because the payload is the record and every projection is derived from it.
- **Every version is kept.** A correction keeps the version that was wrong, and
  a correction decided on a version that has since moved is refused, with the
  clinic's reason on the run.
- **A visit lands whole, or not at all.** A reference that asks for whoever has
  a number is answered once, when the visit is written, and stored as a
  concrete reference; a visit naming somebody nobody has lands nothing.
- **A code means what the clinic's terminology says**, answered from the
  clinic's own concepts; a system the clinic never loaded is unresolvable
  rather than invalid ([terminology](terminology.md)).
- **The store says what it can search**, and refuses a parameter it does not
  implement rather than ignoring it. A record can be checked without writing it
  (`$validate`), and the write agrees with the verdict either way.
- **Every act is in a trail nobody can edit**, including whoever wrote it
  ([the trail](the-trail.md)), and one change feed carries every write once to
  each named consumer.

The [joins table](../arc42-003-context/user-stories/us-dbo-clinical-record.md#joins)
names the test behind each.

## What the store cannot do yet

- **An application cannot read a record's content back through a run.** The
  answer names what the clinic wrote, as `Type/id/_history/version`; reading
  the record itself takes the clinic's records surface, which is where the story
  reads Liis back to prove what was written.
- **A step cannot delete, or create conditionally.** A step answers with
  creates and with updates against a version. *Create her unless somebody with
  this number already exists* is a conditional create on the records surface,
  and the story proves it there; through a step, the second arrival is refused
  by the identity rule instead.
- **Subscriptions deliver, and their failure path is unproven.** Retries,
  backoff and the dead letter are exercised against an engine a test built
  rather than against a tenant.
- **Topic subscriptions are R5 only.** In R4 a topic has nowhere to be declared.
- **Search is tier 1.** Typed per-parameter partitions are specified and not
  built. The store refuses what it cannot do, so the gap is visible.
