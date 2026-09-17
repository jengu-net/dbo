OWNER_KEY=$(printf 'hogwarts-owns-this-key-32-bytes!' | base64 | tr -d '\n')

curl -s -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H "X-Owner-Key: $OWNER_KEY" \
    "$ADMIN/archive" -o hogwarts.archive -w '%{http_code} %{size_download} bytes\n'
