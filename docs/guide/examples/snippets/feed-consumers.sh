curl -s -X POST -H "Authorization: Bearer $HOSPITAL" "$ADMIN/inventory" | python3 -c '
import sys, json
for one in json.load(sys.stdin)["delivery"]:
    print("%-12s %-18s lag %s" % (one["domain"], one["consumer"], one["lag"]))'
