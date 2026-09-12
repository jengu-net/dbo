---
title: Quickstart
headline: A store running, in about a minute
eyebrow: Quickstart
standfirst: >-
  A Postgres, the server, and a tenant that has declared what it holds. From
  there: write a record, change it, read what it used to be, and watch a second
  tenant appear because a file did.
template: essay.html
---

You need Docker and nothing else. Every command below is run on every build of
this repository, so if one of them does not work here, that is a defect rather
than a typo in the page.

```bash
git clone --depth 1 https://github.com/jengu-net/dbo.git
cd dbo/quickstart
docker compose up -d
```

The first start takes a minute or so. It is provisioning two tenants from
nothing — a database each, and a terminology baseline each — which is the real
cost of a cold start and worth seeing once.

```bash
curl -s localhost:8090/t/demo/fhir/metadata | head -c 120
```

When that answers, there is a FHIR R4 server on `/t/demo/fhir`.

## Write something

```bash
curl -s -X POST localhost:8090/t/demo/fhir/Patient \
  -H 'Content-Type: application/fhir+json' \
  -d '{"resourceType":"Patient",
       "identifier":[{"system":"urn:dbo:demo:mrn","value":"12345"}],
       "name":[{"family":"Lovelace","given":["Ada"]}]}'
```

The response carries an `id`, a `versionId` of `1`, and a security label saying
`operational`. That label is not decoration: it is the handling the tenant
declared for this type, and it is what the engine enforces from here on.

## Find it

```bash
curl -s -G localhost:8090/t/demo/fhir/Patient \
  --data-urlencode "identifier=urn:dbo:demo:mrn|12345"
```

`--data-urlencode` because a token search carries a `|` and curl will not
escape it for you.

## Change it, then read what it was

Put it back with the given name shortened to `A.`, then ask for the history:

```bash
curl -s localhost:8090/t/demo/fhir/Patient/<id>/_history
```

Two versions. The first one still says `Ada`, and nothing you can send over
this interface will make it say anything else.

## Ask for something it does not support

```bash
curl -s localhost:8090/t/demo/fhir/Patient?favourite-colour=blue
```

`400`. Not an empty result set, and not a quietly broader one. An unsupported
search parameter is refused, because the alternative is a client that believes
it filtered and did not.

## Add a tenant by adding a file

This is the part worth doing slowly. The server reconciles a directory of
tenant specifications, so provisioning is a file appearing:

```bash
sed 's/"demo"/"clinic"/; s/urn:dbo:demo:mrn/urn:dbo:clinic:mrn/' \
  tenants/demo.json > tenants/clinic.json
```

Within a few seconds `localhost:8090/t/clinic/fhir/metadata` answers. It has
its own database. Delete the file and it stops being served, in about two.

## What you just looked at

The tenant is twelve lines of JSON, and it is the whole model:

```json
{ "code": "demo", "face": "r4",
  "types": [ { "name": "Patient", "identity": "identifier",
               "systems": ["urn:dbo:demo:mrn"], "handling": "operational" } ] }
```

`face` is which standard is mapped onto the engine — [the engine has no FHIR in
it](../why/index.md). `identity` says how this type is identified, which decides
what a conditional write means. `handling` is the declaration the engine
enforces: versioned, audited, retained, exportable. And `code` got its own
database, which is [why a forgotten filter returns
nothing](../why/personal-data.md).

## Two things here that a deployment must not copy

**Authentication is switched off.** The distribution refuses to serve without a
working authority, and this compose file sets the explicit flag that says do
not. That is why none of the commands above carry a token. In a deployment the
tenant is its own authority and every request needs one.

**Postgres is the superuser.** The store provisions a database per tenant, so
it needs `CREATE DATABASE` — here that is the shortest way to have it, and in a
deployment it is a role with that right and nothing else.

Both are marked at the point they are made in
[`compose.yaml`](https://github.com/jengu-net/dbo/blob/main/quickstart/compose.yaml).

## Stop it

```bash
docker compose down
```

Nothing is left behind: the database is on a tmpfs, so the next run starts from
nothing exactly as this one did.

<div class="further" markdown>
Next: [why any of this is shaped the way it is](../why/index.md), or
[what operating it actually involves](../docs/arc42-008-crosscutting/running-it/README.md)
in the specification.
</div>
