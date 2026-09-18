curl -s -X POST http://localhost:8090/t/hogwarts/oidc/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=urn:ietf:params:oauth:grant-type:token-exchange&subject_token=$HUMAN&client_id=night-ledger&client_secret=ledger-secret&scope=user%2FPatient.write"
