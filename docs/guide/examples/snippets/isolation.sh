curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $HOSPITAL" "$GRINGOTTS/Patient/$id"

curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $INSURER" "$GRINGOTTS/Patient/$id"
