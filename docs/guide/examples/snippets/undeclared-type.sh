curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","status":"final",
         "code":{"text":"house points"}}'
