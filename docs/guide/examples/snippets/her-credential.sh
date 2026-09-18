curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/credentials \
    -d "{\"login\":\"pomfrey\",\"secret\":\"a-strong-secret\",\"personId\":\"$person\"}"

curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"ward-console","secret":"console-secret",
         "scope":["user/Patient.read","user/Observation.read"],
         "redirect_uris":["https://ward.example/cb"]}'
