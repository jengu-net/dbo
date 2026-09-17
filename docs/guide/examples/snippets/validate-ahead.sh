curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation/\$validate" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"Observation","code":{"text":"house points"}}' \
  | python3 -c '
import sys, json
for issue in json.load(sys.stdin)["issue"]:
    print(issue["severity"], "|", issue["diagnostics"][:85])'
