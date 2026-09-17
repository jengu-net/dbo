curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
    -H "Authorization: Bearer $HUMAN" \
    "http://localhost:8090/t/hogwarts/oidc/delegation/$delegation"

curl -s -X POST http://localhost:8090/t/hogwarts/oidc/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&delegation_id=$delegation&client_id=night-ledger&client_secret=ledger-secret"
