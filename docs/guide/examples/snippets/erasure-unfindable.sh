curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-FORGET" \
  | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("entry",[])), "found")'
