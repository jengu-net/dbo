claims() { python3 -c '
import base64, json, sys
payload = sys.argv[1].split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
for name in ("sub", "fhirUser", "act", "scope"):
    if name in claims:
        actor = claims[name]
        print(name.ljust(9), actor["sub"] if name == "act" else actor)' "$1"; }

claims "$HUMAN"
