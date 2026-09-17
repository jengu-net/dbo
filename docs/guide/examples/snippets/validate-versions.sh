curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' \
  | grep -o 'administrative-gender|[0-9.]*' | head -1

curl -s -X POST -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0004"}],
         "gender":"purple"}' \
  | grep -o 'administrative-gender|[0-9.]*' | head -1
