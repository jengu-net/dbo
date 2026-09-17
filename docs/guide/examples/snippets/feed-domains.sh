curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" | python3 -c '
import sys, json
held = json.load(sys.stdin)["types"]
print(*sorted({one["domain"] for one in held}), sep="\n")'
