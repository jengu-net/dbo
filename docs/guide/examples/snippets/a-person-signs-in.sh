curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Person" \
    -H 'Content-Type: application/fhir+json' \
    -d "{\"resourceType\":\"Person\",
         \"identifier\":[{\"system\":\"urn:rl:nid\",\"value\":\"RL-POMFREY\"}],
         \"name\":[{\"family\":\"Pomfrey\",\"given\":[\"Poppy\"]}],
         \"link\":[{\"target\":{\"reference\":\"Practitioner/$matron\"}}]}"
