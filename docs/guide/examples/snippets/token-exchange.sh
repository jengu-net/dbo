# The exchange, written once because the chapter performs it several times.
# Everything that varies between them is passed in; what does not is the
# client doing the acting and the endpoint it asks.
token_exchange() {
    local form="grant_type=urn:ietf:params:oauth:grant-type:token-exchange"
    form="$form&client_id=night-ledger&client_secret=ledger-secret"
    for part in "$@"; do form="$form&$part"; done
    curl -sf -X POST http://localhost:8090/t/hogwarts/oidc/token \
        -H 'Content-Type: application/x-www-form-urlencoded' -d "$form" \
      | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}
