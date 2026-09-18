curl -s -w '\n' -X POST http://localhost:8090/t/hogwarts/oidc/delegation \
    -H "Authorization: Bearer $HUMAN" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "client_id=night-ledger&scope=user/Patient.read&valid_until=$(( $(date +%s) + 86400 ))"
