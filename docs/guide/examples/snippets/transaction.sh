curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Bundle","type":"transaction","entry":[
      {"fullUrl":"urn:uuid:admitted",
       "resource":{"resourceType":"Patient",
                   "identifier":[{"system":"urn:rl:nid","value":"RL-0009"}],
                   "name":[{"family":"Bones","given":["Susan"]}]},
       "request":{"method":"POST","url":"Patient"}},
      {"resource":{"resourceType":"Observation","status":"final",
                   "code":{"text":"height"},
                   "subject":{"reference":"urn:uuid:admitted"}},
       "request":{"method":"POST","url":"Observation"}}]}' \
  | python3 -c '
import sys, json
for entry in json.load(sys.stdin)["entry"]:
    print(entry["response"]["status"])'
