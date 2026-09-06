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
allow+='|JenguRepo'                # the publishing repository's Gradle name
allow+='|jengu\.repo\.'             # its credential properties
allow+='|check-branding'           # prose that names this check

# The allowlist is matched against the LINE, after the path and line number,
# never against the path. Every Java source sits under cloud/jengu/dbo, so a
# path match would allow anything a Java file says — and did, for a test
# fixture that named a sibling repository.
if hits=$(grep -rniE 'jengu' \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=.claude --exclude=check-branding.sh \
        . 2>/dev/null | grep -vE "^[^:]+:[0-9]+:.*($allow)"); then
    echo "Unexpected jengu references — neutralise them or add them to the allowlist:" >&2
    echo "$hits" >&2
    exit 1
fi

# An issue is a moment: superseded by later ones, dead when a tracker moves,
# and often not openable by a reader of this repository at all. Code and the
# specification tree describe the state the store is in, so a comment that
# needs its ticket to make sense has not said what it means yet. Two-to-four
# digits, which passes over package coordinates like `hl7.fhir.r4.core#4.0.1`
# and prose like "event #1". docs/tasks/ is exempt: those documents exist to
# carry a topic between its issues and its concepts, and they are deleted when
# their issues close.
# A TODO or a FIXME is the exception, and the only one in the code: it is a
# statement about work outstanding rather than about the store, so the issue
# holding that work is the useful thing to name. The exemption covers the
# marker's own line and the five after it, which is a comment block's worth.
issue_hits=$(grep -rlE '(jengu-platform|jengu-infra|dbo)?#[0-9]{2,4}' \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
        --exclude-dir=tasks --exclude=check-branding.sh --exclude=CLAUDE.md \
        . 2>/dev/null \
    | xargs -r awk '
        FNR == 1 { todo = 0 }
        /TODO|FIXME/ { todo = 6 }
        {
            if ($0 ~ /(jengu-platform|jengu-infra|dbo)?#[0-9][0-9][0-9]?[0-9]?/ && todo == 0)
                print FILENAME ":" FNR ":" $0
            if (todo > 0) todo--
        }')
if [ -n "$issue_hits" ]; then
    echo "Issue references found — state the constraint instead:" >&2
    echo "$issue_hits" >&2
    exit 1
fi

# Decision records belong to the moment they were written and are superseded by
# later ones; this repository documents the state it is in. A comment or a
# document that leans on one is a comment that did not say what it meant, and
# the record it leans on lives in another repository a reader here cannot open.
if hits=$(grep -rnE 'ADR [0-9]{3,4}' \
        --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle \
        --exclude=check-branding.sh . 2>/dev/null); then
    echo "Decision-record references found — state the constraint instead:" >&2
    echo "$hits" >&2
    exit 1
fi

echo "branding: clean"
