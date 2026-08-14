# Implementation roadmap and current state

The living status page: what is built, what it proved, what comes next.
Updated at the close of every slice. The spec lives in the arc42 tree
([index](../README.md)); each slice's full Definition of Ready and closing
proof live on its GitHub issue.

**State as of 2026-08-14: spec phase complete, 14 implementation slices closed,
107 behaviour-named tests, CI green on every commit.**

## Spikes (all closed, verdicts in [arc42-009](../arc42-009-architecture-decisions/README.md))

| Spike | Verdict |
|---|---|
| [#1](https://github.com/jengu-net/dbo/issues/1) DBOS inside Felix | **ADOPT** — instantiable runtime, multi-runtime per JVM, recovery across bundle classloaders; 1 landmine (JDBC DriverManager) |
| [#2](https://github.com/jengu-net/dbo/issues/2) HAPI per personality | **CONFIRMED** — R4+R5 private stacks coexist; 5 landmines (TCCL, eager R5 validation deps, memory, parse-vs-validate, validator-core-is-R5) |
| [#3](https://github.com/jengu-net/dbo/issues/3) Karaf Cellar | **REJECT** — EOL Hazelcast 3.12, first-entry-wins dispatch; build our own routing layer |

## Implementation slices (closed)

| Slice | Delivered | Key REQs claimed |
|---|---|---|
| [#4](https://github.com/jengu-net/dbo/issues/4) Object engine | `dbo-core` (zero-dep API, UUIDv7, sealed IdentityRef) + `dbo-postgres` (single-tx writes, DB-enforced no-merge, state/history/dbos schemas) + non-FHIR test model + Felix packaging test | CORE-* (10), CONT-FRAMEWORK-FREE-CORE, EVT-TRANSACTIONAL-OUTBOX |
| [#5](https://github.com/jengu-net/dbo/issues/5) Feed primitive | ChangeFeed (named consumers, ack/reset/lag), keyset `page()`, **gap-free reads via xid barrier** (§10) | FEED-* (5 of 6) |
| [#6](https://github.com/jengu-net/dbo/issues/6) R4 personality | `dbo-fhir-r4`: extraction from HAPI search-param defs, strict search → Criteria, HAPI validation gate, Bundle link[next]; DATE-as-text-key landmine fixed | VER-PERSONALITY-OWNS-MEANING, SRCH-STRICT/TYPED |
| [#7](https://github.com/jengu-net/dbo/issues/7) Tier-1 search matrix | Modifiers (:exact/:missing/:not/:identifier), sys\| form, one-level chains, `_lastUpdated`, `_summary=count`, `_elements`, `_include`, identity-keyed conditional create — every inventory shape | **SRCH-TIER1-PARITY** |
| [#8](https://github.com/jengu-net/dbo/issues/8) Subscriptions | `dbo-subscriptions` (personality-agnostic) + DBOS delivery: matching-by-search, exactly-once via feed×workflow-id composition, retries, DLQ-as-data, in-process surface | EVT-DURABLE-DELIVERY, EVT-IN-PROCESS-SURFACE |
| [#9](https://github.com/jengu-net/dbo/issues/9) Terminology native form | `dbo-terminology`: concept-per-row, 40k-concept COPY in 534ms, is-a expand, $lookup/$validate-code/$expand; shell honesty (content=not-present) | TERM-* (3), CORE-DECLARED-TRUTH-FORM |
| [#10](https://github.com/jengu-net/dbo/issues/10) R5 personality | `dbo-fhir-common` + `dbo-fhir-r5`; R4+R5 concurrent over one DB, **zero core diffs** | **VER-CONCURRENT-VERSIONS**, VER-VERSION-AGNOSTIC-CORE |
| [#11](https://github.com/jengu-net/dbo/issues/11) Upgrade-on-read | `payload_version` + PayloadConverter chain on reads (history exempt); `R4ToR5Converter`; converter+reindex transition of a live domain | **CORE-UPGRADE-ON-READ**, VER-TRANSITION-BY-CONVERTERS |
| [#17](https://github.com/jengu-net/dbo/issues/17) Tenant runtime wiring | `dbo-tenant`: mandatory `TenantDatabaseProvisioner` OSGi service (returns a DataSource, never credentials) + default database-per-tenant impl (HikariCP embedded); spec files → live tenant service sets (`tenant=` registry properties, `/t/<code>/fhir` on one shared port); retract ≠ erase; explicit deprovision = DROP DATABASE | **CONT-DYNAMIC-TENANT-SERVICES**, **TEN-REGISTRY-SCOPED-ACCESS** |
| [#16](https://github.com/jengu-net/dbo/issues/16) Maintenance | `dbo-maintenance`: one sealed archive, two elements — portable NDJSON (same-tenant re-import = no-op; fresh tenant = restore) and byte-faithful COPY dumps (versions/history/consumers preserved, sequences realigned); AES-256-GCM owner-key envelope (platform cannot read); repeatable-read snapshot with the outbox fence in the manifest (incremental = the §10 feed) | **MNT-*** (all 5; blob element waits for OPS) |
| [#15](https://github.com/jengu-net/dbo/issues/15) SubscriptionTopic delivery | Topic-based subscriptions in the same engine: `TopicSpec`/`TopicSubscription` + composer SPI; R5-native (stored SubscriptionTopic + R5 Subscription → subscription-notification Bundle with SubscriptionStatus, event numbering, id-only/full content); R4 backport (configured topics, criteria=url + filter extension → history Bundle + Parameters status); canFilterBy strictness; delete interactions | **EVT-FHIR-SUBSCRIPTIONS** (complete) |
| [#14](https://github.com/jengu-net/dbo/issues/14) Sync streams | `dbo-sync`: declared content dependencies over the feed — provenance-tagged copies (source id kept), conflict-driven shadowing with live fallback via `reconcile()`, convert-at-apply (R4 zone → R5 leaf), dead-letter + degraded, chains hop-by-hop through each store's own outbox; `FeedItem` now carries `payloadVersion` | **SYNC-*** (all 6) |
| [#13](https://github.com/jengu-net/dbo/issues/13) OSGi packaging | All modules are real bundles: personalities embed private HAPI, subscriptions embeds private DBOS (`lib/` nested jars, DBO-only exports); `EmbeddedContainerIT` boots 8 production bundles + driver in in-JVM Felix and serves a live FHIR flow over HTTP | **CONT-EMBEDDED-IN-JVM**, **CONT-PRIVATE-DEPENDENCIES** |
| [#18](https://github.com/jengu-net/dbo/issues/18) Provisioning operator (Slice B) | `dbo-operator` (plain jar, own pod): `TenantRegistration` CRD (`jengu.cloud/v1alpha1`) + poll reconciler with a **scoped** `dbo_provisioner` role (CREATEDB CREATEROLE, never superuser) → role + database + Secret `tenant-<code>-db` + `dbo-tenants` ConfigMap entry; finalizer deletion policies (Retain = stop serving, keep everything; Delete = erasure-by-drop); `KubernetesSecretProvisioner` (#17 seam from tenant Secrets — pools ride the tenant role's own creds) + `SpecDirSync`; e2e on real k3s with PG outside the cluster (Hetzner topology). Riders: per-database feed-barrier fast path (foreign-db pinner no longer stalls a quiet tenant), chunked `rebuildEnvelopes`, per-database timeouts in BOTH provisioners | **TEN-CREDENTIAL-BLIND-PROVISIONING**, **TEN-DEDICATED-DATABASE-TIER**, TEN-ERASURE-BY-DROP (operational) |
| [#19](https://github.com/jengu-net/dbo/issues/19) Serving deployment (Slice C) | `dbo-tenant-k8s` fat bundle (fabric8 private; `KubernetesSecretProvisioner` as the mandatory service when `dbo.tenant.k8s.namespace` set; serving-pod RBAC = `secrets: get` only — deprovision unrepresentable) + `dbo-server` dist: the STANDARD Felix launcher (`org.apache.felix.main`, auto-deploy over `bundle/`, zero launcher code; bin/felix.jar layout is load-bearing — Felix homes on the jar's parent) + image via the platform registry pattern. Barrier CORRECTED: the R1 in-statement liveness check raced (writer committing between snapshot and stat scan = event lost); now a local horizon (snapshot xmax when write-quiet, taken BEFORE the read snapshot) with cluster-xmin fallback. Cold start measured: ~5.0s dist-boot → first 200 on a current schema | **CONT-FAST-COLD-START** (measured), completes TEN serving story |
| [#12](https://github.com/jengu-net/dbo/issues/12) REST surface | `dbo-rest` (JDK HttpServer, virtual threads, zero deps) over `FhirStoreFacade`: full CRUD w/ ETag/If-Match/If-None-Exist, absolute link[next] paging, OperationOutcome errors (400/409/412/422), `_history`, `$expand`/`$lookup`/`$validate-code`, generated `/metadata`; version-generic (R4+R5 servers) | **SRCH-HONEST-CAPABILITY** |

## REQ coverage summary

Claimed and test-proven: **CORE** complete (incl. truth-form, identity rules,
upgrade-on-read) · **VER** complete except R6 (no ballot personality yet) ·
**SRCH** tier-1 complete (tiers 2/3 deliberately unclaimed) · **FEED** complete
except LEAN-WIRE-OPTION (needs a wire) · **EVT** complete · **TERM** complete · **CONT** complete ·
**SYNC** complete at the
stream-mechanics level (platform-plane orchestration + terminology grain hook
pending) · **MNT** complete (blob element
waits for OPS blob storage) · **TEN** near-complete (registry-scoped access, dynamic services,
credential-blind k8s provisioning, dedicated-database tier, erasure done;
dedicated instances, shared tier, quotas pending) · **WF / SCAL / PROC / OPS**: specified, not
yet implemented.

## Next fronts (unordered candidates, groom before starting)

1. **jengu-infra follow-on PR** — OPEN as
   [jengu-infra#18](https://github.com/jengu-net/jengu-infra/pull/18):
   cloud-init `dbo_provisioner` role, standing `samerole` pg_hba rule for
   tenant roles, operator Deployment/RBAC in the hetzner-prod overlay
   (image built by `build-dbo-operator.yml` → LAN zot / repo.jengu.cloud)
2. **Vault rotation integration** — rotate tenant-role passwords through the
   parked dev-mode Vault; Secrets updated in place, pools recycled
3. **Zone/instance placement** — when zones multiply the databases past the
   one-server comfort zone (~30–50 tenants fits today's Estonian share), a
   `TenantRegistration` grows a placement target; the CRD is the seam

Later horizons: routing layer (SCAL), process catalogue (PROC), R6 ballot
personality, shared-RLS tier, blob storage (OPS).
