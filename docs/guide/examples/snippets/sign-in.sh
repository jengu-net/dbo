# The authorization code arrives where a browser would be sent, so the
# redirect is read rather than followed.
code=$(curl -s -o /dev/null -D - -X POST \
    http://localhost:8090/t/hogwarts/oidc/authorize/login \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "client_id=ward-console&redirect_uri=https%3A%2F%2Fward.example%2Fcb\
&login=pomfrey&password=a-strong-secret" \
  | sed -n 's/.*[?&]code=\([^&[:space:]]*\).*/\1/p' | tr -d '\r')

HUMAN=$(curl -sf -X POST http://localhost:8090/t/hogwarts/oidc/token \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=authorization_code&code=$code\
&redirect_uri=https%3A%2F%2Fward.example%2Fcb\
&client_id=ward-console&client_secret=console-secret" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
