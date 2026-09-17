BLOBS=http://localhost:8090/t/hogwarts/blob

curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/pdf' \
    --data-binary 'PDF-ish bytes' "$BLOBS"
