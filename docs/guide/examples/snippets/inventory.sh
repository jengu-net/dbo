ADMIN=http://localhost:8090/t/hogwarts/admin

curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" | python3 -c '
import sys, json
held = json.load(sys.stdin)["types"]
for one in held:
    if one["domain"] != "definitions":
        print("%-10s %-20s %s" % (one["domain"], one["name"], one["total"]))
print("definitions:", sum(o["total"] for o in held if o["domain"] == "definitions"))'
