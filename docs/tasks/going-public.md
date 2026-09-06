# Going public

**Status** · not started; CI has no runner since 2026-09-06
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

- **Done:** nothing. The last green run was
  2026-09-06 05:57 UTC, on a runner that has since gone offline.
- **Next:** move the workflow to GitHub-hosted runners (step 1). Nothing else is
  possible while `main` cannot build.
- **Blocked on:** `repo.jengu.cloud` existing again, owned by the infrastructure repository.
  Until then `publish` and `images` fail for every push, and Hetzner
  production cannot receive a new image.

## Sequence

| # | Step | Status |
|---|---|---|
| 1 | **GitHub-hosted runners.** Every `runs-on: self-hosted` becomes `ubuntu-latest`: GitHub's own machines, unmetered for a public repository. The suite needs Docker for Testcontainers and a privileged container for k3s; hosted Ubuntu has both. The `dependencies` job exists only because hosted runners were not in use, so it goes and the organisation's automatic dependency submission is switched back on. Proven by one green run of the whole workflow, images job included, before anything else moves. | NEXT |
| 2 | **`repo.jengu.cloud` on Hetzner production.** Zot and the nginx Maven tree from the infrastructure repository's bootstrap tree, in the cluster behind the Traefik route that already terminates the name. The Maven halves and the platform's OBR index tree are re-publishable from CI, and the OBR index is valid wherever the tree is served because its URLs are relative to the root. The `models` tree is not: the PII model is fetched by the platform at runtime, in production, and has to come from a backup or from the Synology's disk. | BLOCKED by the Synology's disk being reachable for `models`, owner: the infrastructure repository |
| 3 | **The workflow talks to the rebuilt repository over TLS.** `REGISTRY` becomes `repo.jengu.cloud`, the plain-HTTP buildx block goes, and the reachability probe stays. New credentials for both halves, so the two systems stop sharing one secret. | READY, needs 2 |
| 4 | **Hetzner production pins an image that exists.** The overlays pin `main-<sha>`; the rebuilt registry is empty. Either re-run `images` for the pinned commit or bump the pin to the first push that lands. | READY, needs 3 |
| 5 | **Neutralise what the ratchet cannot see.** RFC 1918 addresses in `bench/` and `docs/plans/load-comparison.md`, the `mini` hostname, the LAN registry in the workflow. Then the check refuses private addresses, so they cannot regrow. The sibling-repository sweep is done (2026-09-06): the tree outside `docs/tasks/` is clean, and the one fixture the ratchet missed is renamed. The platform halves of two task documents moved to the platform on 2026-09-06, so `docs/tasks/` points only at this tracker. What is left is the addresses. | READY |
| 6 | **History review.** A secrets scanner over all 588 commits, and a read of the first day's `initial import` for anything that came from elsewhere. The quick pattern scan found nothing, which is a reason to run the real one rather than a result. | READY |
| 7 | **The flip.** Visibility to public; secret scanning and push protection on; Dependabot alerts on; branch protection on `main` requiring the `build` check; fork pull requests require approval before their first run; the two dead runner registrations removed from the organisation. | READY, needs 1, 5, 6 |
| 8 | **Prove it from outside.** A pull request from a non-member fork runs the suite with no secrets and skips `images`; a push to `main` publishes jars and images; a bumped pin rolls out on Hetzner. | needs 7 |

**Critical path:** 1, then 2 and 5 and 6 in parallel, then 3, 4, 7, 8. Step 1 is
the only one that unblocks the day: a few hosted runs while still private cost
minutes from the free budget, and that is cheaper than a `main` nobody can
prove green.

## Decisions

**The Synology's repositories are rebuilt on Hetzner, not replaced by a
hosted service.** GitHub Packages serves Maven only to a caller with a token,
public repository or not, so a stranger building against `cloud.jengu.dbo`
would need an account. Central is blocked on artifact size and stays blocked
(the split it needs is in RELEASING.md and is not this topic). The raw trees
have URLs baked into production runtime configuration and into a workflow that
proves a stranger can build against the driver SPI. And Hetzner already
terminates `repo.jengu.cloud`, so consumers change nothing: the cut-over is a
route pointing at an in-cluster service instead of across a VPN.

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

**Order: runners first, flip last.** The workflow change is the only step that
can be proven while still private, and proving it costs a few hosted runs from
the free budget. Flipping first would make the first hosted run public and
unproven at the same time.

## Traps

**The runner is gone, and nothing says so.** A push to `main` queues and sits.
No red X, no failed job. The organisation's runner list is where the truth is.

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

**The registry probe assumes plain HTTP.** It curls `http://` and treats any
answer as reachable. After step 3 it has to speak TLS or it fails on a
registry that is up.

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
