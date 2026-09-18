curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}'

# The outcome repeats itself at length; this prints the first clause of
# each issue, which is the part naming what was wrong and where.
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"} ' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"].split(";")[0])'
