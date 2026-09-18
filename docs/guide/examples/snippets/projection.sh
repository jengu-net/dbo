curl -s http://localhost:8090/t/rl-on-r4/fhir/metadata \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'

curl -s http://localhost:8090/t/rl/fhir/metadata \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["fhirVersion"])'
