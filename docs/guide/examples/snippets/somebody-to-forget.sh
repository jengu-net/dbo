forgettable=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/fhir+json' "$HOGWARTS/Patient" \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-FORGET"}],
         "name":[{"family":"Riddle","given":["Tom"]}],
         "birthDate":"1926-12-31"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
