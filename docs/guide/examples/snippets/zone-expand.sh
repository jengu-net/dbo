curl -s -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet/\$expand?url=urn:rl:wards:vs" | python3 -c '
import sys, json
expansion = json.load(sys.stdin)["expansion"]
print(expansion["total"], "concepts")
for concept in expansion["contains"]:
    print(" ", concept["code"], concept["display"])'
