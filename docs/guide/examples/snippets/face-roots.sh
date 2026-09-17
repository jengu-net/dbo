curl -s -G -H "Authorization: Bearer $FACE_R5" \
    "http://localhost:8090/t/fhir-r5/fhir/StructureDefinition" \
    --data-urlencode "_summary=count" \
  | python3 -c "import sys,json;print('fhir-r5', json.load(sys.stdin)['total'])"

curl -s -G -H "Authorization: Bearer $FACE_R4" \
    "http://localhost:8090/t/fhir-r4/fhir/StructureDefinition" \
    --data-urlencode "_summary=count" \
  | python3 -c "import sys,json;print('fhir-r4', json.load(sys.stdin)['total'])"
