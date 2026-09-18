# The hospital keeps its trail at `writes`, so an ordinary read of a patient
# leaves nothing behind. A read made through a RUN is not ordinary traffic —
# it is somebody being handed a document to do a named piece of work with —
# so it is recorded whatever the level, and the trail answers from either end:
# by the run, for what this work opened, and by the document, for who read it.
curl -s -G -H "Authorization: Bearer $HOSPITAL" "$HOGWARTS/AuditEvent" \
    --data-urlencode "run=$key" --data-urlencode "action=R" | python3 -c '
import sys, json
for entry in json.load(sys.stdin).get("entry", []):
    e = entry["resource"]
    print(e["action"], e["entity"][0]["what"]["reference"],
          "by", e["agent"][0]["who"]["identifier"]["value"])'
