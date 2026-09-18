curl -s -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/harry" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient","id":"harry"}'
