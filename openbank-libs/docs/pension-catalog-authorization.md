# Pension catalog authorization

The shared `@Authorize` interceptor passes `azp`, `subject`, and
`preferred_username` to OPA only when an endpoint explicitly requests those
attributes and the authenticated principal has a service-account name. Human
requests do not gain these service-token attributes. The values come from the
verified JWT, not request headers or body fields.

The catalog OPA policy permits pension approval-evidence reads by the named
pension service client and excludes this action from generic operator and
compliance read grants. This policy is **advisory by default** in
product-catalog (`AUTHZ_ENFORCE=false`): the approval-evidence GET endpoint's
mandatory gates are `CATALOG_SCOPE_READ` and a verified JWT reader check. The
reader must be the named pension service account or an Admin UI human with a
matching pension approval role and scope. Another service's token with
`catalog:read` cannot read approval evidence even when it omits service-account
name and role claims. With `AUTHZ_ENFORCE=true`, OPA also checks its policy's
caller and claim conditions.

Catalog approval decisions are separately human-only and require the legal or
product-owner role together with its matching scope. The endpoint checks the
requested approval role and stores the verified reviewer identity with the
decision. A successful policy decision does not replace those endpoint checks
or the independent maker/checker rule.

See `openbank-libs/governance/policies/rest.rego` for the policy and
`openbank-libs-runtime/src/main/kotlin/com/openbank/libs/authz/AuthorizeInterceptor.kt`
for the allowlisted attribute extraction.
