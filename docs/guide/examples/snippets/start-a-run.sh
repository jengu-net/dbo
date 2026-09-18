started=$(curl -s -X POST -H "Authorization: Bearer $PORTER" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/step/hogwarts.admission.admit \
    -d "{\"inputs\":{\"patient\":\"Patient/$id\"}}")
printf '%s\n' "$started"

run=$(printf '%s' "$started" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["run"])')

# Two names for one run, and they answer different questions. The id
# addresses the context; the key is what the rest of the work model is
# asked by — including the trail.
key=$(printf '%s' "$started" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["key"])')

# The context is a path, so it is resolved against the host already being
# talked to rather than used as it stands.
CONTEXT=http://localhost:8090$(printf '%s' "$started" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["context"])')
