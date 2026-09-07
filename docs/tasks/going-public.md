# Going public

**Status** · public since 2026-09-07 and proven from outside; only the artifact host is left, and it is the infrastructure repository's
**Issues** · [#199](https://github.com/jengu-net/dbo/issues/199)
**Concepts** · [RELEASING.md](../../RELEASING.md), [the branding ratchet](../../.github/scripts/check-branding.sh), [working rules](../arc42-002-constraints/working-rules.md)

## What this is

The repository is private, and everything about how it builds and ships was
shaped by that. The suite ran on two self-hosted runners because hosted
minutes are a monthly budget for a private repository under a free-tier
organisation. Jars published to `repo.jengu.cloud`, images to a registry on
the LAN, and both were one machine on the home network: the Synology, reached
from Hetzner over a VPN.

The home network is gone. Both runners are offline in the organisation
(`mini-runner-1`, `mini-runner-2`), so every push to `main` now waits for a
runner that will never come. The Synology went with it, so `repo.jengu.cloud`
answers nothing: jars have nowhere to publish, images have nowhere to land,
and Hetzner production pulls `dbo-server` and `dbo-operator` through that
name.

Going public is not the cosmetic step at the end of this. It is what makes
hosted runners free, which is what makes a pipeline without owned hardware
possible. And rebuilding the Synology's two repositories on Hetzner
production is what gives that pipeline somewhere to publish. The two halves
depend on each other, and this document carries them together.

## Where it stands

- **Done:** steps 1 and 5 to 8. The repository is public, builds on
  GitHub-hosted runners, its images pull anonymously, and a fork's pull
  request has been run and proven harmless.
- **Next:** step 2, in the infrastructure repository. Nothing is left on this
  side but step 3, which is one repository variable.
- **Blocked on:** nothing. The blocker recorded here until 2026-09-07 was the
  `models` tree, and it was not real — see the decision below.

## Sequence

| # | Step | Status |
|---|---|---|
| 1 | **GitHub-hosted runners.** Every `runs-on: self-hosted` became `ubuntu-latest`: GitHub's own machines, unmetered for a public repository. The suite needs Docker for Testcontainers and a privileged container for k3s; hosted Ubuntu has both. The `dependencies` job was removed for GitHub's own automatic submission and then restored, for the reason in the traps below. | **DONE** 2026-09-07 — the first hosted run was green: suite in eighteen minutes, both architectures of all three images on GHCR in four, publish skipped for want of a host |
| 2 | **`repo.jengu.cloud` on Hetzner production.** Today it times out: the Traefik route still terminates the name, and behind it two Services carry hand-written endpoints pointing over a VPN at a NAS that is gone. What has to exist again is the Maven tree at `/repository/` and, if the fleet still wants its own, a registry at `/v2/`. Both are re-publishable from CI, as is the platform's OBR index tree, whose URLs are relative to the root and so valid wherever it is served. | READY — smaller than it looked, see the decision below about the model |
| 3 | **The workflow talks to the rebuilt repository.** Set `ARTIFACT_HOST` to the host; the workflow already speaks TLS to it and probes it before building. New credentials for both halves, so the two systems stop sharing one secret. | READY, needs 2 |
| 4 | **Hetzner production pins an image that exists.** The overlays pin `main-<sha>`; the rebuilt registry is empty. Either re-run `images` for the pinned commit or bump the pin to the first push that lands. | READY, needs 3 |
| 5 | **Neutralise what the ratchet cannot see.** The bench scripts took their machines from the environment or a flag rather than from a default in the tree; the plan document names them by role; the one example address in a Javadoc became a hostname. The ratchet refuses RFC 1918 addresses and the LAN's hostnames now, proven with a probe file it catches and one it lets through. The sibling-repository sweep was done the day before. | **DONE** 2026-09-07 |
| 6 | **History review.** A secrets scanner over every commit, and a read of the first day's `initial import`. The scanner found one thing: the development console's example key, a fixed constant the file itself documents as protecting nothing, appearing nowhere else in history. It is allowlisted by path in a scanner config kept in the tree, so the scan is repeatable and clean. The import was an empty Antora skeleton and a Gradle wrapper; nothing came from elsewhere. | **DONE** 2026-09-07 |
| 7 | **The flip.** Visibility public; secret scanning, push protection, Dependabot alerts and security updates on; the workflow token read-only by default; fork pull requests need approval on a first contribution; `main` cannot be force-pushed or deleted; the six merged branches and the two dead runner registrations removed; the three GHCR packages public. | **DONE** 2026-09-07 |
| 8 | **Prove it from outside.** An anonymous reader gets the repository and pulls all three images in both architectures. A pull request from a fork ran the suite green on a hosted runner with no secrets, skipped `images`, and annotated instead of writing a check run. What remains is the jar publish, which needs the host (steps 2–4). | **DONE** 2026-09-07, but for the jar publish |

**Critical path:** 2, then 3 and 4. Everything on this side is done, and what
is left gates only the jar publish and the fleet's own image pull — neither of
which stops a reader, a contributor or an image consumer today.

## Decisions

**The PII model was never the blocker, and need not live on the artifact
host at all.** This document recorded until 2026-09-07 that the `models` tree
could not be rebuilt from CI and had to come from a backup or the NAS's disk,
because production fetches it at runtime. That was wrong. The model is a
reproducible export of a public HuggingFace model, the platform carries the
script that produces it, and pinning the source revision makes the export
bit-exact. Its tarball is also under GitHub's two-gigabyte per-asset limit,
and a release asset downloads anonymously with no bandwidth limit — so the
one artifact that looked irreplaceable can live somewhere the artifact host
is not, which is what makes step 2 ordinary. Deciding that is the platform's,
not this repository's.

**The Synology's repositories are rebuilt on Hetzner, not replaced by a
hosted service.** GitHub Packages serves Maven only to a caller with a token,
public repository or not, so a stranger building against `cloud.jengu.dbo`
would need an account. Central is blocked on artifact size and stays blocked
(the split it needs is in RELEASING.md and is not this topic). The raw trees
have URLs baked into production runtime configuration and into a workflow that
proves a stranger can build against the driver SPI. And Hetzner already
terminates `repo.jengu.cloud`, so consumers change nothing: the cut-over is a
route pointing at an in-cluster service instead of across a VPN.

**The fleet's own artifact host is one optional variable.** `ARTIFACT_HOST`
names the host that serves the Maven tree and the container registry; unset,
the jar publish is skipped and images go to the public registries only.
Skipped rather than failed, because a destination that does not exist yet is
a known state, and a red X on every push for a known state teaches people to
ignore red Xs. GHCR is the primary image destination and always exists, so
the fleet registry is the third tag of the same build rather than a separate
push that goes first. When the host comes back, setting the variable is the
whole cut-over on this side.

**GitHub-hosted runners, not new self-hosted ones.** The self-hosted runner existed
for a minute budget that public repositories do not have. It also carried the
load-bearing security condition on `images`, which is only needed because a
fork pull request on a self-hosted runner is code execution on the machine.
On hosted runners that condition is defence in depth rather than the whole
defence, and it stays anyway.

**History is kept, not squashed.** The conventions put the journey in commit
messages. Old messages carry private issue numbers, which a public reader
cannot open; that is the accepted cost, and it is what the ratchet keeps out of
the tree rather than out of the log.

**`docs/tasks/` points only at this tracker.** Two live topics carried links
to issues in the consuming platform's private tracker, which a public reader
cannot open. Their platform-side halves moved to the platform's own task
documents on 2026-09-06, and what stays here names the consumer by role
rather than by repository. The ratchet still exempts `docs/tasks/` from the
issue rule, because these documents exist to carry a topic between its issues
and its concepts; the issues they may carry are this repository's.

**`main` is protected against force-push and deletion, and requires no
check.** The task first said the `build` check would be required. A required
status check rejects a direct push whose commit has no passing run yet, and
direct pushes to `main` are how this repository is worked on, so requiring it
would have rejected every push. What a public `main` needs protecting from is
a rewrite or a removal, and that is what is set. The check becomes required
the day the workflow moves to pull requests.

**Order: runners first, flip last.** The workflow change is the only step that
can be proven while still private, and proving it costs a few hosted runs from
the free budget. Flipping first would make the first hosted run public and
unproven at the same time.

## Traps

**A package's visibility is gated one level up.** Every package's settings
page offered Public greyed out as "disabled by organization administrators",
and the reason was the organisation's package-creation setting, which did not
allow public packages at all. Flip that first; the packages follow.

**A dependency-graph snapshot outlives the job that wrote it.** Step 1
removed the `dependencies` job in favour of GitHub's automatic submission,
which looked equivalent. It is not: a snapshot is superseded only by another
under the same correlator, and the correlator is the workflow and job name.
Three automatic runs reported success, said the snapshot was uploaded, and
changed nothing — the graph stayed two days old, and every Dependabot
security update failed against packages the tree no longer had. Keep exactly
one of the two mechanisms, and if the automatic one is ever preferred, the
job has to run once more first.

**The runner is gone, and nothing says so.** A push to `main` queues and sits.
No red X, no failed job. The organisation's runner list is where the truth is.
And a run that queued for the missing runner holds the concurrency group, so
the first hosted run sat pending behind it until it was cancelled by hand.

**`gradle.properties` is dialled for a machine that no longer exists.** Its
comment sizes the test heap against a 2500m runner container. Hosted runners
have more, but the dials stay until a run inside one proves what is needed;
the comment has to stop naming the container it was measured in.

**The branding check runs on the working tree, not the index.** An untracked
`.claude/settings.local.json` that mentions a sibling repository fails it
locally while CI stays clean. Ignore the file rather than allowlist the
content.

**The ratchet allowed whatever a Java file said.** Its allowlist was matched
against the whole grep line, path included, and every Java source sits under
`cloud/jengu/dbo`. A test fixture naming a sibling repository passed for
months. The allowlist now matches the line after its path and number.

**A shared secret opens two systems.** `NEXUS_USERNAME` and `NEXUS_PASSWORD`
authenticate to both the Maven tree and the container registry. RELEASING.md
already says to separate them; rebuilding both halves is the moment it costs
nothing.

## Not doing

- **Maven Central.** Blocked on the size split, unchanged by visibility.
- **A first public release tag.** After the pipeline is proven, not as part
  of proving it.
- **Native arm64 hosted runners** instead of QEMU. Worth measuring later; the
  existing single job with QEMU is what has to be proven first.
- **Cleaning private issue numbers out of history.** See the decision above.
- **A Docker Hub pull-through cache.** It paid for itself on a runner that
  built both architectures every push from a cold cache. Hosted runners are
  different machines with different rate limits, and the need has to be shown
  again before the service is rebuilt.

## Verifying

```bash
gh api orgs/jengu-net/actions/runners -q '.runners[] | "\(.name) \(.status)"'
gh run list -R jengu-net/dbo --limit 3
.github/scripts/check-branding.sh
curl -sS -m 5 -o /dev/null -w '%{http_code}\n' https://repo.jengu.cloud/v2/
curl -sS -m 5 -o /dev/null -w '%{http_code}\n' https://repo.jengu.cloud/repository/maven-releases/
gh repo view jengu-net/dbo --json visibility -q .visibility
```
