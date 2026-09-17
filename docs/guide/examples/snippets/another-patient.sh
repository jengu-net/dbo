other=$(curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-90-Granger" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["entry"][0]["resource"]["id"])')
