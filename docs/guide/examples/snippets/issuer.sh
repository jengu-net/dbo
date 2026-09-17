curl -s http://localhost:8090/t/hogwarts/oidc/.well-known/openid-configuration \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])'

curl -s http://localhost:8090/t/gringotts/oidc/.well-known/openid-configuration \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["issuer"])'
