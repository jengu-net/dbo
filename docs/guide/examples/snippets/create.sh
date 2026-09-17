curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0001"}],
         "name":[{"family":"Potter","given":["Harry"]}]}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'
