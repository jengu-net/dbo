curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $DIRECTORY" "$HOGWARTS/Patient"

curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $HOSPITAL" "$SCIM/Users"
