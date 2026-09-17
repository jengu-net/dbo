curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"night-ledger","secret":"ledger-secret",
         "scope":["user/Patient.read"]}'

ACT=$(token_exchange "subject_token=$HUMAN" "scope=user%2FPatient.read")
claims "$ACT"
