curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H "X-Owner-Key: $OWNER_KEY" \
    --data-binary @hogwarts.archive "$ADMIN/import"
