# Threat model — openbank-pricing-service

Scope: the deployed pricing service in namespace `pricing`, adopted under GitOps on
2026-09-28 (JiRaska/openbank-pricing#1). Application code lives in
`JiRaska/openbank-pricing`; this model is written from the deployment view (manifests in
`openbank-infra/gitops/components/pricing/`) and names where a code-level review is still owed.

## Assets
- **Price and fee definitions** in `pricing-db` (CNPG, PostgreSQL 18). They decide what
  customers are charged, so integrity matters more than confidentiality.
- **The OIDC client credential** of the dedicated Keycloak client `openbank-pricing-service`
  (ExternalSecret `pricing-service-oidc`, from OpenBao).
- **Authorization decisions** made by the OPA sidecar (`pricing-opa-bundle`: `rest.rego`,
  `pricing_rest_ext.rego`, agents policy and governance data).

## Trust boundaries
1. The pricing console, and admin-ui in namespace `admin-ui`, call the service on :8150.
   The NetworkPolicy `pricing-service-ingress-allow-list` admits only same-namespace pods and
   admin-ui. There is no Ingress for the service, so it has no internet exposure.
2. The service calls `product-catalog.accounts.svc`. That edge is declared in both namespaces'
   generated NetworkPolicies.
3. The service to `pricing-db-rw` connection runs over the CNPG-managed TLS server certificate.
4. Keycloak (`kc.open-bank.tech`) issues the tokens the service validates.

## Threats & mitigations (STRIDE)
- **Spoofing.** A caller could pretend to be staff or another service.
  - Mitigation: every request carries a Keycloak JWT that is validated against the `openbank`
    realm, and the OPA sidecar decides per action.
  - Residual: the two pricing Keycloak clients exist only in the live realm, not in the repo
    realm template (follow-up issue), so their configuration is unreviewed.
- **Tampering.** An unauthorised change to price definitions changes what customers pay.
  - Mitigation: write endpoints go through OPA `allow`. The database is reachable only from the
    namespace, and `pricing-db` now has daily base backups plus WAL archiving with 30-day
    retention, so a bad change can be recovered to a point in time.
  - Owed: a code review that every mutating endpoint is `@Authorize`-guarded. The
    `authz-enforce-pdp-sidecar-parity` gate cannot read this repo's code, so it cannot check it.
- **Repudiation.** A price change could go unattributed.
  - Owed: confirm, in the pricing repo, that mutations emit an audit event with the principal.
    Nothing in the manifests shows an audit publisher.
- **Information disclosure.** Price books are commercially sensitive, not customer PII.
  - Mitigation: there is no public ingress, reads are OPA-gated, and secrets come from
    OpenBao via ExternalSecrets. Gitleaks found nothing in the manifests.
- **Denial of service.** The service had a single database instance and a single replica.
  - Mitigation: `pricing-db` now runs 2 instances with switchover updates, the same
    resilience as every other cluster (ADR-0325).
  - Residual: the service Deployment has one replica.
- **Elevation of privilege.** A policy gap could grant too much.
  - Mitigation: the OPA sidecar was loading two stale rego files with no governance data. It
    now loads the full fleet bundle (`--bundle /bundle`), so the shared REST policy and its
    `data.rules` / agents data apply here as they do elsewhere. Empty input evaluates to
    `allow=false`.

## Residual risk / follow-ups
- Add the `openbank-pricing-service` and `openbank-pricing-console` clients to the Keycloak
  realm template, so they stop being live-only.
- Code-level review in `JiRaska/openbank-pricing`: `@Authorize` coverage and audit events on
  mutations.
- Consider two replicas for the service once its load justifies it.
