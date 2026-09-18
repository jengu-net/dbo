# The same credential, minted without saying what it is for.
NO_REASON=$(token hogwarts hogwarts-secret)

curl -s -G -H "Authorization: Bearer $NO_REASON" "$HOGWARTS/Patient" \
    --data-urlencode "identifier=urn:rl:nid|RL-0001" | python3 -c '
import sys, json
print(json.load(sys.stdin)["issue"][0]["diagnostics"])'
