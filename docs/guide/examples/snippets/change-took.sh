curl -s http://localhost:8090/t/stmungos/fhir/metadata | python3 -c '
import sys, json
served = [r["type"] for r in json.load(sys.stdin)["rest"][0]["resource"]]
print("Patient" in served, "Observation" in served)'
