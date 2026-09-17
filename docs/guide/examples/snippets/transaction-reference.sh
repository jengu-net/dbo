curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    --data-urlencode "_count=50" | python3 -c '
import sys, json
for entry in json.load(sys.stdin).get("entry", []):
    resource = entry["resource"]
    if resource.get("code", {}).get("text") == "height":
        print(resource["subject"]["reference"])'
