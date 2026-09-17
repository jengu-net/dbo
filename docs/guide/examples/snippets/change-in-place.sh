python3 - <<'NARROW'
import json, pathlib
spec = pathlib.Path("docs/guide/world/tenants/stmungos.json")
declared = json.loads(spec.read_text())
declared["types"] = [t for t in declared["types"] if t["name"] != "Observation"]
spec.write_text(json.dumps(declared, indent=2) + "\n")
NARROW
