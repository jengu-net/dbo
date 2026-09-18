curl -s -o /dev/null -w '%{http_code}\n' "$BLOBS/$blob"

curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" \
    "$BLOBS/01a00000-0000-7000-8000-00000000dead"
