curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:hogwarts:local","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"x","display":"Local"}]}'
