curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?identifier=urn:rl:nid%7CRL-0001" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry","James"]}]}'
