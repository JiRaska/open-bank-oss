# Operations

## Build & run

```bash
./gradlew :openbank-copilot-service:build
```

HTTP port **8131**. The service is gated off by the `copilot-assistant` feature flag / `copilot.enabled`.

## Logging of proposal tokens

A proposal token id (`ProposalToken.id`, the `{tokenId}` of
`POST /api/v1/copilot/actions/{tokenId}/confirm`) is a one-time capability, so it is never written to
a log in the clear. The token stores (in-memory and Redis), the confirm endpoint and its policy-deny
path log a stable, non-reversible reference instead:

```
token_ref=ptk_<first 12 hex chars of SHA-256(token id)>
```

The same token always yields the same `token_ref`, so the store, confirm and deny lines of one
proposal still correlate. To find the lines for a token id you hold, compute its reference with
`ProposalToken.logRef(id)` and search for that. `ProposalTokenLogRefTest` fails the build if a
production log format reintroduces a raw `token=` / `tokenId=` field.

Note: nothing in `src/main` issues a proposal token today (#5900), so these lines appear only in
tests until the confirm path is wired.
