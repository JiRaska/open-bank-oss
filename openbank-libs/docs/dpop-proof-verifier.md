# DPoP proof verifier: sender-constrained access tokens

`com.openbank.libs.security.DpopProofVerifier` is the ADR-0320 P2 primitive for RFC 9449 DPoP. A
resource server uses it to check that a request carries a valid proof of possession of the key
its access token is bound to (`cnf.jkt`). The first consumer is the customer MCP channel (#16590).
Nothing here is activated by itself: no CDI, JAX-RS or Jandex-visible annotation is involved.

## Parts

| Part | Module | Role |
|---|---|---|
| `DpopProofVerifier`, `DpopResult`, `DpopProof`, `DpopPolicy`, `DpopRequest` | libs-domain | Pure decision logic. The only clock is the `now` argument. |
| `DpopProofParser` (port) | libs-domain | JOSE front-end. Must never throw: unparsable input is `null`. |
| `DpopReplayStore` (port), `ReplayClaim` | libs-domain | `jti` replay claim, shaped for `SET key NX PX ttl`. |
| `Jose4jDpopProofParser` | libs-runtime | jose4j adapter. A plain class compiled `compileOnly` against jose4j. |
| `InMemoryDpopReplayStore` | libs-runtime | Bounded single-pod store. A shared (Valkey) store is a later slice. |

## Opting in

libs-runtime ships no JOSE runtime dependency. jose4j reaches services through `quarkus-oidc`, so
a service opts in by constructing `Jose4jDpopProofParser` from its own `src/main` (ADR-0320 rule 2).
A service that never constructs it never loads a jose4j class.

## What a proof must satisfy

The checks run structural first, then request binding, and the replay claim runs last. A proof
rejected for any other reason never uses up its `jti`.

- `typ` is `dpop+jwt`, and `alg` is one of ES256, EdDSA or PS256. `none` and HMAC are refused.
- The `jwk` header holds a public key only, and that key suits the algorithm: P-256 on the curve
  for ES256, RSA of at least 2048 bits for PS256, Ed25519 for EdDSA.
- The signature verifies with that key, and `htm` equals the request method.
- `htu` equals the request URI after RFC 9449 §4.3 normalisation: no query or fragment,
  lower-case scheme and host, no default port, RFC 3986 §6.2.2 percent-encoding. A non-ASCII host
  never matches; clients must send the punycode form.
- `iat` lies between `now - maxAge` and `now + futureSkew` (defaults 300 s and 60 s).
- `ath` equals base64url(SHA-256(access token)).
- The RFC 7638 thumbprint of the `jwk` equals the token's `cnf.jkt`.
- `jti` is present and has not been claimed before.

Anything unparsable, more than one `DPoP` header, or a parser that throws is `MALFORMED`.

## Spending a budget only on bound proofs

`verify` is `check` followed by `claim`. A caller that rate-limits before the replay claim should
call them separately:

- `check(request, now)` runs every check except the replay claim and touches no state. It returns
  `DpopCheck.Rejected(result)` or `DpopCheck.Bound(jkt, jti)`, where `jkt` is the verified proof-key
  thumbprint, equal to the token's `cnf.jkt`.
- `claim(bound, now)` makes the replay claim and returns the same results `verify` would.

`isReplayed(bound, now)` is a non-mutating peek (`DpopReplayStore.isUsed`, default `false`). Call
it before spending a budget, so a replayed proof costs nothing; `claim` remains the authority, and
two concurrent copies of one proof can both pass the peek. Only `check` can create a
`DpopCheck.Bound`: its constructor and `copy` are internal, so `claim` cannot be handed one that
skipped the checks.

Run the limiter between the two. A junk proof, or one carrying someone else's public key, is then
rejected by `check` before it can spend anyone's budget, and the budget is still spent before the
replay store is written to.

## Results callers must tell apart

| Result | Meaning | Typical answer |
|---|---|---|
| `VALID` | Proof accepted and its `jti` claimed. | proceed |
| `MISSING_PROOF`, `MALFORMED`, `BAD_SIGNATURE`, `ALG_NOT_ALLOWED`, `PRIVATE_KEY_IN_JWK`, `HTM_MISMATCH`, `HTU_MISMATCH`, `IAT_OUT_OF_WINDOW`, `ATH_MISMATCH`, `REPLAYED` | The proof is wrong. | 401 `invalid_dpop_proof` |
| `JKT_MISMATCH` | The proof key is not the token's key. | 401 `invalid_token` |
| `RATE_LIMITED` | This key holds too many live proofs (the store's per-key quota). | 429 |
| `REPLAY_STORE_UNAVAILABLE` | The store cannot answer: global capacity reached or backend down. | 503 + metric |

`RATE_LIMITED` is the client's doing. `REPLAY_STORE_UNAVAILABLE` is the platform's. Never fold
either into `VALID` or into `REPLAYED`.

## Replay store limits

- An entry stays live through `now + ttl` inclusive. The TTL is `maxAge + futureSkew + 5 s`
  (365 s by default), so an entry always outlives every `iat` the verifier would still accept.
- `InMemoryDpopReplayStore` holds at most 100 000 live entries in total and 5000 per key. The
  per-key quota is a ceiling of about 13.7 proofs/s sustained per key. Neither limit evicts a live
  entry, because that would reopen its replay window.
- The per-key quota bounds one key, not many: enough distinct keys fill any global cap. **A
  per-(party, client) rate limiter must run before the replay claim.**
