token() {
    curl -sf -X POST "http://localhost:8090/t/$1/oidc/token" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d "grant_type=client_credentials&client_id=${3:-tenant-bootstrap}&client_secret=$2\
&purpose_of_use=${4:-}" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}

# The hospital holds people behind the membrane, so its credential says what it
# is for: reading somebody by their national number is a disclosure, and one
# without a stated reason is refused rather than answered.
HOSPITAL=$(token hogwarts hogwarts-secret tenant-bootstrap TREAT)
INSURER=$(token gringotts gringotts-secret)
JURISDICTION=$(token rl rl-secret)
