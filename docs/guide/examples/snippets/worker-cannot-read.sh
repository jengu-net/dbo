curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$HOGWARTS/Patient/$id"
