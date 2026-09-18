curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/role-grants \
    -d '{"role":"matron","organisation":"hogwarts",
         "scopes":["user/Patient.read","user/Observation.read"]}'

curl -s -H "Authorization: Bearer $HOSPITAL" \
    http://localhost:8090/t/hogwarts/oidc/admin/role-grants
