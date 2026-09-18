curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/metadata"  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
curl -s -H "Authorization: Bearer $INSURER" "$GRINGOTTS/metadata" | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
