curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Bundle","type":"batch","entry":[
      {"resource":{"resourceType":"Patient",
                   "identifier":[{"system":"urn:rl:nid","value":"RL-0011"}],
                   "name":[{"family":"Abbott"}]},
       "request":{"method":"POST","url":"Patient"}},
      {"resource":{"resourceType":"Observation","code":{"text":"no status"}},
       "request":{"method":"POST","url":"Observation"}}]}' \
  | python3 -c '
import sys, json
for entry in json.load(sys.stdin)["entry"]:
    print(entry["response"]["status"])'
