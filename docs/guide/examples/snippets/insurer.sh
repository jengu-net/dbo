curl -sf -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry"]}]}'
