curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Bundle","type":"transaction","entry":[
      {"resource":{"resourceType":"Patient",
                   "identifier":[{"system":"urn:rl:nid","value":"RL-0010"}],
                   "name":[{"family":"Doge"}]},
       "request":{"method":"POST","url":"Patient"}},
      {"resource":{"resourceType":"Observation","code":{"text":"no status"}},
       "request":{"method":"POST","url":"Observation"}}]}'

curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0010" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])), "entries")'
