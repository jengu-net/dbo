curl -s -w '\n' -H "Authorization: Bearer $INSURER" "$GRINGOTTS/CodeSystem/\$lookup?system=urn:rl:wards&code=spell"

curl -sf -G -H "Authorization: Bearer $INSURER" "$GRINGOTTS/ValueSet" --data-urlencode "url=urn:rl:wards:vs" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])), "value sets")'
