# Releasing

A release is a `v*` tag. The pipeline does the rest: the suite runs, jars
publish, images build multi-arch, and a signed Maven Central bundle is
uploaded and left for a human to release.

Nothing publishes unless the whole suite is green on exactly that commit.

## One-time setup

None of this is in the repository, and all of it is needed before the first
public release.

### Maven Central

1. **Claim the namespace.** Register at [central.sonatype.com](https://central.sonatype.com)
   and add the namespace `cloud.jengu`. Verification is a DNS `TXT` record on
   `jengu.cloud` containing the code the Portal gives you. The namespace
   covers every `cloud.jengu.*` group, so this is done once.
2. **Generate a signing key.** Central rejects unsigned artifacts.

   ```
   gpg --full-generate-key                 # RSA 4096, no expiry or a long one
   gpg --list-secret-keys --keyid-format=long
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   gpg --armor --export-secret-keys <KEYID>
   ```

   The key must be on a public keyserver or validation fails: Central checks
   the signature against the published public key.
3. **Set the repository secrets.**

   | Secret | Value |
   |---|---|
   | `SIGNING_KEY` | the full ASCII-armored private key, `-----BEGIN` line included |
   | `SIGNING_PASSWORD` | its passphrase |
   | `CENTRAL_TOKEN` | the user token from the Portal's account page, as `Bearer` |

### Container images

GHCR needs nothing — `GITHUB_TOKEN` covers it, and the images land under the
repository's own namespace.

Docker Hub is a mirror and is **optional**: without its secrets the job skips
those tags and pushes only to GHCR and the LAN registry.

| Secret | Value |
|---|---|
| `DOCKERHUB_USERNAME` | a user with push rights to the `jengu` organisation |
| `DOCKERHUB_TOKEN` | an access token, not the account password |

## Cutting a release

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Then:

1. Watch the `build` job. If the suite fails, nothing else runs.
2. `central` uploads a bundle and stops. Go to
   [the deployments page](https://central.sonatype.com/publishing/deployments),
   check the validation result, and press publish. **This step is deliberately
   manual: Central is immutable, and a released version cannot be withdrawn,
   only superseded.**
3. `images` pushes `X.Y.Z` and moves `latest`. `latest` follows releases only —
   a main-branch push never moves it, because a user who typed no tag should
   not receive an arbitrary commit.

If the bundle fails validation, fix and tag `v0.1.1`. A rejected deployment
can be dropped from the Portal, but a *published* one cannot.

## Verifying a bundle without publishing

The bundle is reproducible locally, and building one is the cheapest way to
find a metadata problem before a tag exists:

```bash
./gradlew centralBundle -Pdbo.version=0.1.0
unzip -l build/central/dbo-central-bundle.zip
```

Without `SIGNING_KEY` in the environment the bundle builds unsigned — fine for
checking layout and POM contents, and Central would reject it. Every release
tag runs with the key, and the workflow fails early and loudly if the key is
missing rather than discovering it after the upload.

## Version numbering

`0.x` while requirement areas remain specified-but-unbuilt (see the
[status page](docs/plans/implementation-status.md)). The wire contracts that
`1.0` would freeze are the FHIR surface, the tenant authority's endpoints, the
`TenantRegistration` CRD group and the archive format.
