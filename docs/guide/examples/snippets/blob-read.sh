curl -s -D - -o /dev/null -H "Authorization: Bearer $HOSPITAL" "$BLOBS/$blob" \
    | grep -iE '^content-type|^content-length'
