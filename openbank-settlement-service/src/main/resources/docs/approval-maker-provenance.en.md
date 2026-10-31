# Approval maker provenance — Settlement

Four-eyes approval responses from this service include `makerActorKind`, captured when the pending request is created and kept with its approval record. The value can be `HUMAN`, `AI_AGENT`, `SERVICE_ACCOUNT`, `CUSTOMER_PARTY`, or `UNKNOWN`; older or unclassified records may be `UNKNOWN`.

This field helps an operator understand who initiated a request. It is not an authorization decision: the server still enforces the distinct maker and checker identities. The Admin inbox must show an unknown kind honestly instead of guessing from the maker's display name or ID.

## Testing four-eyes under the fail-closed service-account flag

OPA treats any principal whose token is not verifiably a person as a service account (`input.principal.service_account`, fail closed). A plain `@TestSecurity` identity carries no JWT, so it now counts as a machine and loses the human maker and checker grants. `SettlementFourEyesFlowIT` therefore gives its maker and checker `@OidcSecurity` human claims. That is why the module's tests depend on `quarkus-test-security-oidc`.
