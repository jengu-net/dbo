docker compose -f docs/guide/examples/compose.yaml exec -T db \
    psql -U postgres -d tenant_hogwarts -tAc \
    "SELECT convert_from(payload,'UTF8') FROM state.r5_data WHERE type='Patient' LIMIT 1" \
  | python3 -c '
import sys, json
stored = json.loads(sys.stdin.read())
print(*sorted(stored), sep="\n")'
