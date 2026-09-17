curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0003"}],
         "birthDate":"not-a-date"}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}'
