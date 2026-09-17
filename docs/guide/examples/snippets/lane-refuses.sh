curl -s -w '\n' -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/work/rummage -d '{}'

curl -s -w '\n' -H "Authorization: Bearer $HOSPITAL" \
    http://localhost:8090/t/hogwarts/work/poll

curl -s -w '\n' -X POST -H "Authorization: Bearer $HOSPITAL" \
    -H 'Content-Type: application/json' \
    http://localhost:8090/t/hogwarts/work/poll -d '{}'
