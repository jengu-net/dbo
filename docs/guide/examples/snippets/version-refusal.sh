curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Coverage" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Coverage","status":"active","kind":"insurance",
         "beneficiary":{"reference":"Patient/x"}}'
