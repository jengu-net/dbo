curl -s -X POST -H "Authorization: Bearer $PORTER" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/step/hogwarts.admission.admit \
    -d "{\"inputs\":{\"patient\":\"Patient/$id\"}}"
