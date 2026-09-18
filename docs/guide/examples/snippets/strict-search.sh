curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/Patient?favourite-colour=blue"
