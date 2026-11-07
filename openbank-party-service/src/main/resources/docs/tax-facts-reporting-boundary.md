# Tax-facts reporting boundary — party

ADR-0340 B1: `GET /api/v1/reporting/parties/{id}/tax-facts` is the **only** route through which
taxpayer identification numbers (TINs) leave party-service, and it exists for exactly one
reader — `service-account-openbank-tax-reporting`, the statutory-reporting assembly in
openbank-tax-reporting-service.

## Why a dedicated route

The existing AML-profile read is a staff/customer read whose audience is a person reviewing a
file. Tax reporting is a bulk, cross-customer machine-to-machine assembly, so it gets its own
controls instead of widening the staff route:

- a dedicated OPA action (`party.reporting.taxFacts`) whose verb is deliberately not
  `read`/`list`, so no generic read rule accidentally admits anyone;
- an in-code guard on top of `@RolesAllowed`/`@Authorize`:
  `ServiceAccountIdentity.isPrincipal(identity, "service-account-openbank-tax-reporting")`
  checks the **verified** service-account claims (azp bound to the service-account username,
  #12448) — never the principal name alone — so a misconfigured bundle fails closed here
  instead of leaking TINs;
- an audit event (`party.tax-facts.reporting-read`) whose payload carries only the derived
  statuses and residency countries — **never the TINs** that are in the response body.

The response carries the party's holder name, profile version, declaration time, derived
`crsStatus`/`fatcaStatus`, and the declared residencies with their TINs. A party that never
declared a profile answers 404 — the assembly treats that as "not reportable", never as an
error, and the omission itself is the reportable-statement.

## Contract

The route is pact-covered both ways: tax-reporting-service's consumer contract pins the
response shape (including that `taxResidencies` is answered in declaration order), and the
401 leg replays with no caller identity so a regression that leaks the route to any other
ROLE_API caller fails in CI.
