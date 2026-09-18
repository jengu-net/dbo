curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"rights-desk","secret":"desk-secret","scope":["erasure"]}'

RIGHTS=$(token hogwarts desk-secret rights-desk)

curl -s -X POST -H "Authorization: Bearer $RIGHTS" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/erasure \
    -d "{\"subject\":\"Patient/$forgettable\"}"
