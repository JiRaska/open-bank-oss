# Customer app-session authorization

For every `customer.*` action the shared `@Authorize` interceptor adds two
attributes to the OPA input, without the endpoint having to request them:

- `token_azp`: the verified JWT's `azp`, the client the token was issued to.
- `token_act`: `true` when the token carries an `act` claim (token exchange or
  delegation). An unreadable `act` claim is reported as `true`, because it
  cannot prove the token was not delegated.

No other action gains these keys, and the existing opt-in `azp` attribute
(service-account tokens only) is unchanged.

`rest.rego` uses them to prohibit `customer.mcp-proposals.*` (reading and
deciding a payment proposal made by a connected MCP client) unless the token is
the customer's own app session: `token_azp == "openbank-app"` and
`token_act == false`. A token issued to the MCP client itself, an exchanged
token, or a request without the attributes is denied, whichever allow reason
would otherwise match (ADR-0383 D6). Other `customer.*` actions are unaffected.

This is a PDP backstop. customer-edge enforces the same predicate in its own
handler, and with `AUTHZ_ENFORCE=true` (the deployed setting) the policy denies
independently of that code.

See `openbank-libs/governance/policies/rest.rego` for the policy and
`openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz/AuthorizeInterceptor.kt`
for the attribute extraction.
