curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"}]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:houses","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"gry","display":"Gryffindor"},
                    {"code":"sly","display":"Slytherin"}]}'
