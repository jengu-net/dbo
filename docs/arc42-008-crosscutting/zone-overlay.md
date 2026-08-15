# Zone overlay (§17)

Jurisdiction is a governing artifact in healthcare, not deployment
trivia. Three authentication-critical things are jurisdictional — the
identifier domains people are resolved by, the national identity brokers,
and the terminology — and one is organizational: which broker a given
organization contracts. The zone overlay makes jurisdiction first-class:
**a zone is a tenant whose declarations are records**, and dependent
tenants consume them at bring-up.

## 17.1 Declarations are records in the zone tenant

The zone tenant (e.g. `ee`) carries a dbo-native `zone` domain:

- **ZoneBroker** — {code (`tara`, `eeid`), issuer, clientId,
  subjectStripPrefix, assuranceLevel}. Secrets are NEVER in the record:
  the machinery resolves them from custody by broker code. Estonia
  declares two: TARA (government and official municipal healthcare) and
  eeID (private sector).
- **ZoneIdentifierDomain** — {use (`person-primary`, …), system}: the
  official identifier system URIs from the national terminology (for
  Estonia, the patient-identifier-domain ValueSet at TEHIK, profiled in
  the EE base IG). Subject resolution and PDI person-systems read them
  from here; dbo code carries none.

Because declarations are records they are versioned, audited, exported,
and — on real chains — STREAMED down the existing §6/#14 SYNC machinery:
the zone chain and the content chain are the same mechanism.

## 17.2 The set is jurisdictional; the choice is organizational

A dependent tenant declares `"zone": "ee"` and selects its broker by zone
code (`"broker": "eeid"` — the authentication contract is organization-
level; the zone's default applies when absent). It may further restrict
what it ACCEPTS: `"acceptedBrokers": ["tara"]` — a policy, not a
capability claim.

## 17.3 The hub is per-zone and its sessions accumulate

Each zone runs one identity hub with N upstreams. The session records
WHICH broker performed each ceremony (`amr`) and **accumulates**: when a
tenant's acceptance policy is not satisfied by the existing session, the
hub runs the REQUIRED broker's ceremony and adds it — the strictest
tenant is satisfied without invalidating anyone else's session.
Cross-broker reuse is the DEFAULT (the identity is equally verified, and
the reusing tenant pays no ceremony fee); restriction is the tenant's
declared choice. Assertions carry the session's `amr`; tenant authorities
re-verify acceptance before minting — defence in depth over the hub's
enforcement.

## 17.4 Consumption and ordering

Tenant runtimes resolve their zone at bring-up: the zone tenant must be
up first, and a dependent arriving earlier simply fails bring-up loudly
and retries on the next scan — the same self-healing the spec directory
already has. Single-zone deployments may keep the environment-level
broker configuration (§16.2); zone declarations override it when present.

## 17.5 What stays outside

Cross-zone trust between hubs; zone-level RoleGrant templates;
multi-zone production topology (the placement discussion owns it);
terminology content itself (already the SYNC chains' cargo).
