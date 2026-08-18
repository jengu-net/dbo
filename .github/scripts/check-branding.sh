#!/usr/bin/env bash
# Branding creeps back one commit at a time. This is the ratchet.
#
# `jengu` is allowed in exactly three kinds of place, and each is a deliberate
# vendor coordinate rather than a leak:
#   - the Maven group and Java package (cloud.jengu.dbo / cloud/jengu/dbo)
#   - the CRD group and its labels (dbo.jengu.cloud)
#   - the registry namespaces, the licence holder and the security address
#
# Anything else is a reference that should have been neutralised: a private
# issue number, a sibling repository, an internal hostname.
set -euo pipefail

# The allowlist IS the list of deliberate coordinates. Adding to it should
# feel like a decision, which is why each entry says what it is.
allow='cloud\.jengu\.dbo'          # Maven group and Java package
allow+='|cloud/jengu/dbo'          # ...as a path
allow+='|cloud\.jengu'             # the Central namespace, in the release runbook
allow+='|dbo\.jengu\.cloud'        # the CRD group and its labels
allow+='|jengu\.cloud'             # the domain that verifies the namespace
allow+='|jengu-net'                # the GitHub org, in image coordinates
allow+='|docker\.io/jengu'         # the registry namespace
allow+='|ghcr\.io/jengu'           # ...and the other one
allow+='|Jengu Net'                # the copyright holder
allow+='|id\.set\("jengu"\)'       # the POM developer id
allow+='|`jengu` organisation'     # the Docker Hub org, in prose
allow+='|check-branding'           # this file

if hits=$(grep -rniE 'jengu' \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
        . 2>/dev/null | grep -vE "$allow"); then
    echo "Unexpected jengu references — neutralise them or add them to the allowlist:" >&2
    echo "$hits" >&2
    exit 1
fi

# Issue references die with the tracker they point at.
if hits=$(grep -rnE '(jengu-platform|jengu-infra|dbo)#[0-9]+' \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
        --exclude=check-branding.sh . 2>/dev/null); then
    echo "Issue references found — a reader of this repository cannot open them:" >&2
    echo "$hits" >&2
    exit 1
fi

echo "branding: clean"
