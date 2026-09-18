curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" \
    -H 'If-Match: W/"1"' \
    "$HOGWARTS/Patient/$stale" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$stale\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0008\"}],
         \"name\":[{\"family\":\"Prewett\",\"given\":[\"Fabian\"]}]}"

curl -s -o /dev/null -w '%{http_code}\n' -X PUT \
    -H "Authorization: Bearer $HOSPITAL" \
    -H 'If-Match: W/"2"' \
    "$HOGWARTS/Patient/$stale" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$stale\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0008\"}],
         \"name\":[{\"family\":\"Prewett\",\"given\":[\"Fabian\"]}]}"
