curl -sf -o /dev/null -X PUT -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Patient\",\"id\":\"$id\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-0001\"}],
         \"name\":[{\"family\":\"Potter\",\"given\":[\"H.\"]}]}"

curl -sf -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history"
