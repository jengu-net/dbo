curl -s -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/1"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient/$id/_history/99"
