curl -s -H "Authorization: Bearer $PORTER" "$CONTEXT/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
print(*[r["type"] for r in rest["resource"]], sep="\n")'
