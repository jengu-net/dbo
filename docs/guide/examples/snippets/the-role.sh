curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/PractitionerRole" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"PractitionerRole\",\"active\":true,
         \"practitioner\":{\"reference\":\"Practitioner/$matron\"},
         \"organization\":{\"reference\":\"Organization/$org\"},
         \"code\":[{\"coding\":[{\"system\":\"urn:rl:role\",\"code\":\"matron\"}]}]}"
