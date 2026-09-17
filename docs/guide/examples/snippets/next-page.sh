curl -s -H "Authorization: Bearer $HOSPITAL" \
    "$(printf '%s' "$next" | sed 's|http://0.0.0.0:8090|http://localhost:8090|')"
