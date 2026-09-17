---
title: The tenant's authority
eyebrow: Guide
standfirst: >-
  Every tenant runs its own issuer, with its own keys and its own clients — so
  a credential is for a tenant, and there is no central token service whose
  misconfiguration is everybody's problem.
template: essay.html
---

You have been using this since the quick start without being told what it was.
Every command in this guide gets a token first, and every one of those tokens
came from the tenant it was about to talk to.

## Each tenant is its own issuer

```bash
--8<-- "docs/guide/examples/snippets/issuer.sh"
```

```
http://0.0.0.0:8090/t/hogwarts/oidc
http://0.0.0.0:8090/t/gringotts/oidc
```

Two issuers, two key sets, two sets of clients. That is why
[Isolation](isolation.md) could show the hospital's token answering `401` at the
insurer rather than `403`: it is not a weaker credential there, it is not a
credential there.

It also means a tenant can be moved, or presented on its own domain, without
re-keying — the issuer is the tenant's own configuration and the keys live in
its database, alongside everything else it owns.

## Getting a token

```bash
--8<-- "docs/guide/examples/snippets/token.sh"
```

A client-credentials exchange, which is the machine half of this. `tenant-bootstrap`
is the credential the deployment holds for each tenant, and its secret is the
one the deployment already decided — in a cluster an operator generates it and
keeps it in a vault, and the store is told what it is rather than inventing one
nobody could present.

Ask without a token and the answer is plain:

```bash
--8<-- "docs/guide/examples/snippets/no-token.sh"
```

```
401
```

## A credential holds what it was given

Registering one says what it may do:

```bash
--8<-- "docs/guide/examples/snippets/worker-credential.sh"
```

```
200
```

That client holds `work` and nothing else, and
[Reaching data through a run](runs.md) is the chapter that uses it — including
the part that matters, which is that the same credential is refused when it
reads a record the direct way.

**Scopes are the SMART grammar**, so `system/*.read`, `user/Patient.read` and
the rest mean what they mean elsewhere rather than being this store's private
vocabulary. Two are deliberately outside it — erasure and work — because the
most consequential acts should not be reachable by a credential that happens to
hold a broad grant.

## Machines and people arrive differently

A machine presents a client credential and holds what it was registered with.

A person does not have a client credential. They authenticate through a
ceremony — their zone's, or a broker it names — and the token they end up with
carries the scopes their role grants, which is
[Who may act](who-may-act.md). The authority validates both kinds and is the
only thing that decides what a request may do.

## What you would otherwise have written

A central identity service, and the argument about whether a customer may have
their own — resolved by saying no, and then by building a per-customer
exception three years later.

Signing keys in a shared secret store, so the blast radius of one leak is every
tenant.

And an "act as" feature for support, which is the one that turns up in the
incident report.
