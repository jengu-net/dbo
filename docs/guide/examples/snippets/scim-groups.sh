curl -s -X POST -H "Authorization: Bearer $DIRECTORY" \
    -H 'Content-Type: application/scim+json' "$SCIM/Groups" \
    -d '{"displayName":"matron"}' | python3 -c '
import sys, json
print(json.load(sys.stdin)["detail"])'
