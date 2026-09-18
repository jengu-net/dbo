# The work is done. This is the synchronous half of the work model: whoever
# started the run performed it inline and says so, rather than a runner
# claiming queued work off a lane.
curl -s -X POST -H "Authorization: Bearer $PORTER" \
    "http://localhost:8090/t/hogwarts/run/$run/done" | python3 -c '
import sys, json
print("holder", json.load(sys.stdin)["holder"])'
