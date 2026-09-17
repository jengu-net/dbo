curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Observation" \
    --data-urlencode "subject=Patient/$bones" \
  | python3 -c '
import sys, json
for entry in json.load(sys.stdin).get("entry", []):
    print(entry["resource"]["code"]["text"])'
