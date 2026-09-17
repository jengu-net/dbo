# Asked about the record, rather than taking whatever the trail happened to
# return first: a tenant's trail holds every write since it came up, and the
# question worth asking is about one patient.
curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/AuditEvent" \
    --data-urlencode "entity=Patient/$id" --data-urlencode "action=C" \
    --data-urlencode "_count=1" | python3 -c '
import sys, json
entry = json.load(sys.stdin)["entry"][0]["resource"]
print("action ", entry["action"])
print("who    ", entry["agent"][0]["who"]["identifier"]["value"])
print("what   ", entry["entity"][0]["what"]["reference"])'
