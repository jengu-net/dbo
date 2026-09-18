doomed=$(curl -sf -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0007"}],
         "name":[{"family":"Fleeting"}]}' \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/2"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$doomed/_history/1"
