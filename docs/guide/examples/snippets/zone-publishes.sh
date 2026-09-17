curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/CodeSystem" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"CodeSystem","url":"urn:rl:wards","version":"1",
         "status":"active","content":"complete",
         "concept":[{"code":"dai","display":"Dai Llewellyn Ward"},
                    {"code":"spell","display":"Spell Damage"},
                    {"code":"creature","display":"Creature-Induced Injuries"},
                    {"code":"potion","display":"Potion and Plant Poisoning"}]}'

curl -s -o /dev/null -w '%{http_code}\n' -X POST -H "Authorization: Bearer $JURISDICTION" "$ZONE/ValueSet" \
    -H 'Content-Type: application/fhir+json' \
    -d '{"resourceType":"ValueSet","url":"urn:rl:wards:vs","version":"1",
         "status":"active","compose":{"include":[{"system":"urn:rl:wards"}]}}'
