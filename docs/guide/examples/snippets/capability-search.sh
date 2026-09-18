curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata" | python3 -c '
import sys, json
rest = json.load(sys.stdin)["rest"][0]
for resource in rest["resource"]:
    if resource["type"] == "Patient":
        print(*sorted(p["name"] for p in resource["searchParam"]), sep="\n")'
