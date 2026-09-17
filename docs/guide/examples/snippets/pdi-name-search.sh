curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "family=Potter" | python3 -c '
import sys, json
print(json.load(sys.stdin)["issue"][0]["diagnostics"])'
