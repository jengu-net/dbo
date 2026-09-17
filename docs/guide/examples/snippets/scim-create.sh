SCIM=http://localhost:8090/t/hogwarts/scim/v2

curl -s -o /dev/null -w '%{http_code}\n' -X POST \
    -H "Authorization: Bearer $HOSPITAL" -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/oidc/admin/clients \
    -d '{"client_id":"staff-directory","secret":"directory-secret","scope":["scim"]}'

DIRECTORY=$(token hogwarts directory-secret staff-directory)

curl -s -X POST -H "Authorization: Bearer $DIRECTORY" \
    -H 'Content-Type: application/scim+json' "$SCIM/Users" \
    -d '{"schemas":["urn:ietf:params:scim:schemas:core:2.0:User"],
         "externalId":"HOG-0042","userName":"mmcgonagall",
         "name":{"familyName":"McGonagall","givenName":"Minerva"},
         "active":true}' | python3 -c '
import sys, json
user = json.load(sys.stdin)
print("externalId", user["externalId"])
print("userName  ", user["userName"])
print("active    ", user["active"])'
