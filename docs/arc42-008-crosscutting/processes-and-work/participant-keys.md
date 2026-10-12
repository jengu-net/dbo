# A participant's keys, in a form others can make

The form a participant's identity is kept in, and the text forms its public
halves and signatures travel in. These are promises: a process beside the
store, written in any language, makes and reads them with standard tools. Why
a participant holds keys at all is [participants](participants.md).

## Why the form is promised

**Somebody other than the store has to hold the identity first.** An
appliance is installed by something — an installer, a supervisor, an agent
the operator runs — and that process is there before the store first starts,
while it is being reinstalled, and when it fails to start. It is also the
process best placed to say so, and a statement nobody can check is a rumour.
So it must be able to make the participant's keys before the store exists,
and sign as the participant while the store is not running, without the JVM
and without an encoding of its own.

**Whichever side starts first makes the identity, and the other adopts it.**
Neither has to be told which came first.

## The directory, version 1

```
<dir>/FORMAT          one line: dbo-participant-keys/1
<dir>/sealing.pem     X25519 private key, PKCS#8 PEM, mode 0600
<dir>/signing.pem     Ed25519 private key, PKCS#8 PEM, mode 0600
```

PKCS#8 PEM is what OpenSSL writes by default, so a shell makes the whole
identity in three lines:

```sh
openssl genpkey -algorithm X25519  -out sealing.pem
openssl genpkey -algorithm ed25519 -out signing.pem
echo dbo-participant-keys/1 > FORMAT
```

**No public halves are kept.** They are derived from the private ones and so
can never disagree with them.

**`FORMAT` is written last, and it is what makes the directory one.** The
store writes each key beside its place, readable by its owner alone, and moves
it into place whole; a directory without `FORMAT` was never finished.

**A directory is adopted, made, or refused:**

| The directory | What the store does |
|---|---|
| holds `FORMAT` naming this version and both keys | adopts them |
| is missing, or empty | makes the identity there, in this form, then adopts it |
| holds anything else — one key missing, no `FORMAT`, a version it does not read, a key of the wrong kind, a file that is not PEM | refuses to start, naming what is wrong |

Making over a directory that holds something would be replacing an identity
silently, and the participant would find out when the tenant stopped
recognising it.

A host names the directory as `dbo.lane.keys.dir`; the Spring worker as
`dbo.worker.substrate.keys-dir`. Either stands instead of the two base64
private-key properties, never beside them: two would be two identities for one
participant, and naming both is refused.

## The text forms

The recipes below run with OpenSSL 3 and a POSIX shell. `b64u` is base64url
without padding:

```sh
b64u() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
```

**A public half is an OKP JWK** whose `crv` is `X25519` or `Ed25519` and whose
`x` is the raw 32-byte key, in `b64u`. The last 32 bytes of the DER public key
are that key:

```sh
x=$(openssl pkey -in signing.pem -pubout -outform DER | tail -c 32 | b64u)
```

**Its `kid` is its RFC 7638 thumbprint**: SHA-256 over the required members in
lexicographic order with no whitespace, in `b64u`:

```sh
printf '{"crv":"Ed25519","kty":"OKP","x":"%s"}' "$x" | openssl dgst -sha256 -binary | b64u
```

A JWK is read whatever its member order or spacing, and with or without a
`kid`, so one written by another tool reads as the same key.

**A signature is raw Ed25519**, 64 bytes, in `b64u`:

```sh
openssl pkeyutl -sign -rawin -inkey signing.pem -in message.bin | b64u
```

## A signed statement

What a participant says while the store is not running — being installed,
reinstalled, failing to start — travels as:

```json
{"statement": "<text>", "kid": "<the signing key's thumbprint>", "signature": "<b64u>"}
```

**What is signed is the text behind a prefix**: the UTF-8 bytes of
`dbo-statement/1` and a newline, followed by the statement.

```sh
printf 'dbo-statement/1\n%s' "$statement" > said.bin
openssl pkeyutl -sign -rawin -inkey signing.pem -in said.bin | b64u
```

A participant signs other things with the same key — each ask on a lane,
each link of a run's trail — and the prefix keeps a signature over one from
being presented as a signature over another.

**The side deciding whether to admit the participant checks it.** It already
holds the signing key the participant asked to be admitted with, and a
statement verifies when its `kid` is that key's thumbprint and its signature
holds over the prefixed text. In this store that is
`SignedStatement.parse(json).verifiedBy(SigningKey.parse(jwk))`. The store keeps
no pending admissions of its own; deciding to admit is the host's.

## Test vectors

`core/harness/src/test/resources/participant-keys/` holds an identity made by
OpenSSL alone with these recipes, and `v1.vectors.properties` beside it its
`x`, its `kid` and the signature over a fixed statement, for both halves. The
store is tested against them, and where OpenSSL is installed the recipes are
run as well and each side verifies what the other signs, so the page and the
store cannot drift apart unnoticed.
