curl -sf -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0001"
