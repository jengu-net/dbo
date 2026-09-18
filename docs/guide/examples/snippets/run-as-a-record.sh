curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Task/$run" | python3 -c '
import sys, json
task = json.load(sys.stdin)["entry"][0]["resource"]
code = {c["system"]: c["code"] for c in task["code"]["coding"]}
print("process ", code["urn:dbo:process"])
print("step    ", code["urn:dbo:step"])
print("holder  ", task["businessStatus"]["coding"][0]["code"])
for i in task["input"]:
    print("input   ", i["type"]["coding"][0]["code"], "=", i["valueReference"]["display"])'
