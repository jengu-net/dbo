curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"a-porter","secret":"porter-secret","scope":["work"]}'

PORTER=$(token hogwarts porter-secret a-porter)
