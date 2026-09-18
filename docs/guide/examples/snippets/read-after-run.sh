# And the way in closes behind it. The same request that answered a moment ago
# now answers as it would for a run that never existed — which is the
# difference between access granted to a step and access granted once by way
# of one.
curl -s -o /dev/null -w '%{http_code}\n' \
    -H "Authorization: Bearer $PORTER" "$CONTEXT/Patient/$id"
