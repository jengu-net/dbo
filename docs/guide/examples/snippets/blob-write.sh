BLOBS=http://localhost:8090/t/hogwarts/blob

written=$(curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/pdf' \
    --data-binary 'PDF-ish bytes' "$BLOBS")
printf '%s\n' "$written"

# The key the store assigned, kept for the rest of the chapter.
blob=$(printf '%s' "$written" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["key"])')
