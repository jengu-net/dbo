---
title: Quickstart (Docker)
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

This is the fastest way to have a store answering. The other route —
[from source, through the development
console](quickstart-karaf.md) — takes about fifteen minutes and gives you a
bundle set you can edit while it runs, a tenant on FHIR R5, and somewhere to
watch the store's own internal process bring that tenant up.

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

That answers without a credential, and deliberately: what a tenant can be
*asked* is public — which types it holds, how they can be searched — so a
consumer can read it and find out whether to bother authenticating. What a
tenant *holds* is not:

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:8090/t/demo/fhir/Patient
```

`401`. Every tenant is its own authority. Ask this one for a token, and keep
it:

```bash
DEMO=$(curl -s -X POST localhost:8090/t/demo/oidc/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials&client_id=tenant-bootstrap&client_secret=demo-secret' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $DEMO" \
  localhost:8090/t/demo/fhir/metadata | head -c 120
```

Now the data is reachable too, on `/t/demo/fhir`. The secret came from the
compose file, which is this world's vault; in a deployment an operator holds it.

## Write something

```bash
curl -s -H "Authorization: Bearer $DEMO" \
  -X POST localhost:8090/t/demo/fhir/Patient \
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
curl -s -H "Authorization: Bearer $DEMO" \
  -G localhost:8090/t/demo/fhir/Patient \
  --data-urlencode "identifier=urn:dbo:demo:mrn|12345"
```

`--data-urlencode` because a token search carries a `|` and curl will not
escape it for you.

## Change it, then read what it was

Put it back with the given name shortened to `A.`, then ask for the history:

```bash
curl -s -H "Authorization: Bearer $DEMO" \
  localhost:8090/t/demo/fhir/Patient/<id>/_history
```

Two versions. The first one still says `Ada`, and nothing you can send over
this interface will make it say anything else.

## Ask for something it does not support

```bash
curl -s -H "Authorization: Bearer $DEMO" \
  'localhost:8090/t/demo/fhir/Patient?favourite-colour=blue'
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

**The secrets are in the compose file.** The key each tenant seals credentials
with, and the secret behind each tenant's bootstrap client, are written beside
the service that uses them — which is fine for a world whose data is invented
and would not be anywhere else. An operator generates and holds these.

**Postgres is the superuser.** The store provisions a database per tenant, so
it needs `CREATE DATABASE` — here that is the shortest way to have it, and in a
deployment it is a role with that right and nothing else.

What is *not* on that list any more is authentication. It used to be, and the
commands above carried no token: a store whose point is who-may-see-what is the
wrong thing to demonstrate with that switched off, because the first thing
somebody runs is the thing they copy.

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
