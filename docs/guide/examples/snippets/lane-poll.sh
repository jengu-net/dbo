RUNNER='{"name":"ward-runner","version":"1","provider":"hogwarts",
         "scope":{"at":"ORGANISATION","code":"hogwarts"}}'

curl -s -w '\n' -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/work/poll \
    -d "{\"participant\":\"ward-runner\",\"identity\":$RUNNER,
         \"steps\":[\"hogwarts.admission.admit\"],\"limit\":5}"
