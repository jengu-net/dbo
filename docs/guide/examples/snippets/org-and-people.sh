curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Organization" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Organization",
         "identifier":[{"system":"urn:rl:org","value":"hogwarts"}],
         "name":"Hogwarts Hospital"}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Practitioner" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Practitioner",
         "identifier":[{"system":"urn:rl:nid","value":"RL-POMFREY"}],
         "name":[{"family":"Pomfrey","given":["Poppy"]}]}'
