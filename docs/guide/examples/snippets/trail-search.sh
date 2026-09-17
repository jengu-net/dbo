curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/AuditEvent" \
    --data-urlencode "agent=tenant-bootstrap" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["total"], "by that credential")'
