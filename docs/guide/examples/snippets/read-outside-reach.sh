curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$other"

curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Observation/$other"
