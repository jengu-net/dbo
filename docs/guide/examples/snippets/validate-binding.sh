curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"}'

# The outcome repeats itself at length; this prints the first clause of each
# issue and the element it is about. The element is in `expression` rather than
# in the sentence: a form marking the field somebody typed wrong reads it there
# instead of parsing it back out of prose.
curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Patient",
         "identifier":[{"system":"urn:rl:nid","value":"RL-0002"}],
         "gender":"purple"} ' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    where = ", ".join(issue.get("expression", [])) or "-"
    print(issue["severity"], "|", where, "|", issue["diagnostics"].split(";")[0])'
